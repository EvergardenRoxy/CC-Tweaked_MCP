#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Online-server connectivity probe for the cct-live Minecraft network bridge.

Run on the PLAYER's machine while the Minecraft client is joined to the remote server (with the cct-live client and
server mods installed). It talks to the same loopback bridge that `cct-run mcp` uses, so a pass means the whole chain
  probe -> 127.0.0.1 client bridge -> Minecraft connection -> remote server cct-live -> CC:T
works. Python 3.8+, standard library only. Nothing here modifies computers; it only opens its own MCP-style sessions.

  python3 scripts/online-connectivity-test.py --game-dir ~/.minecraft [--computer 5] [--soak 600] [--report out.json]

Without --computer only the link, session and permission-denial checks run (a missing authorization is reported as
SKIP, not FAIL). With --computer, authorize that computer in game first (AI icon beside its GUI, or
`/cctlive network authorize <ID>`), then the lock and read checks run. Exit code: 0 all pass, 1 any FAIL, 2 setup error.
"""
import argparse, json, os, statistics, sys, time, urllib.error, urllib.request, uuid

PROTOCOL = 3
CAPS = {"computers", "status", "screen", "session_open", "session_heartbeat", "session_close",
        "control_acquire", "control_status", "control_release", "menu_grant"}
results = []


def record(cid, name, status, detail=""):
    results.append({"id": cid, "name": name, "status": status, "detail": detail})
    print(f"{status:4}  {cid:4} {name}" + (f"  -- {detail}" if detail else ""), flush=True)


class Bridge:
    def __init__(self, base, token):
        self.base, self.token = base.rstrip("/"), token

    def call(self, method, path, headers=None, token="default", body=None, timeout=15):
        h = {"Accept": "application/json"}
        t = self.token if token == "default" else token
        if t is not None:
            h["Authorization"] = "Bearer " + t
        h.update(headers or {})
        req = urllib.request.Request(self.base + path, method=method, headers=h, data=body)
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        except (urllib.error.URLError, OSError) as e:
            return 0, {"error": str(e)}, time.perf_counter() - start
        ms = time.perf_counter() - start
        try:
            return status, json.loads(raw or b"{}"), ms
        except ValueError:
            return status, {"raw": raw[:200].decode("utf-8", "replace")}, ms


class Session:
    def __init__(self, bridge):
        self.b, self.id, self.lease = bridge, None, None

    def hdr(self, lease=True):
        h = {"X-CCT-Session": self.id}
        if lease and self.lease:
            h["X-CCT-Lease"], h["X-CCT-Lease-Epoch"] = self.lease["lease_id"], str(self.lease["lease_epoch"])
        return h

    def open(self, key=None):
        s, d, _ = self.b.call("POST", "/network/sessions/open", {"X-CCT-Open-Key": key or str(uuid.uuid4())}, body=b"")
        if s == 200:
            self.id = d["agent_session_id"]
        return s, d


def pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except (PermissionError, OSError):
        return True  # exists (or Windows: cannot tell cheaply)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--game-dir", default=os.environ.get("CCT_LIVE_DIR"), help="client game directory (default $CCT_LIVE_DIR)")
    ap.add_argument("--computer", type=int, help="ID of a computer you authorized in game")
    ap.add_argument("--rtt-samples", type=int, default=20)
    ap.add_argument("--burst", action="store_true", help="also test the server rate limiter (sends ~60 requests in 1 s)")
    ap.add_argument("--soak", type=int, default=0, metavar="SECONDS", help="keep a session + lease alive this long, probing every 15 s")
    ap.add_argument("--report", help="write JSON report here")
    a = ap.parse_args()
    if not a.game_dir:
        print("need --game-dir or CCT_LIVE_DIR", file=sys.stderr)
        return 2

    # ---- L1 local discovery -------------------------------------------------------------------------------------
    home = os.path.join(os.path.expanduser(a.game_dir), "cct-live", "client")
    try:
        with open(os.path.join(home, "endpoint.json"), encoding="utf-8") as f:
            ep = json.load(f)
        with open(os.path.join(home, "token.txt"), encoding="utf-8") as f:
            token = f.read().strip()
    except OSError as e:
        record("L1.1", "client endpoint.json/token.txt present", "FAIL",
               f"{e}; join the server first (singleplayer uses cct-live/endpoint.json instead and is not a network test)")
        return finish(a, 2)
    ok = ep.get("transport") == "minecraft-network" and ep.get("url", "").startswith("http://127.0.0.1:")
    record("L1.1", "endpoint is a loopback minecraft-network bridge", "PASS" if ok else "FAIL", ep.get("url", ""))
    if not ok:
        return finish(a, 2)
    if isinstance(ep.get("pid"), int):
        record("L1.2", "bridge owner process alive", "PASS" if pid_alive(ep["pid"]) else "FAIL", f"pid {ep['pid']}")
    br = Bridge(ep["url"], token)

    s, _, _ = br.call("GET", "/network", token=None)
    record("L1.3", "request without token rejected (401)", "PASS" if s == 401 else "FAIL", f"HTTP {s}")
    s, _, _ = br.call("GET", "/network", token="x" * len(token))
    record("L1.4", "wrong token rejected (401)", "PASS" if s == 401 else "FAIL", f"HTTP {s}")
    s, _, _ = br.call("GET", "/network", headers={"Host": "evil.example"})
    record("L1.5", "foreign Host header rejected (403)", "PASS" if s == 403 else "FAIL", f"HTTP {s}")

    # ---- L2 handshake across the real connection -------------------------------------------------------------------
    s, d, _ = br.call("GET", "/network")
    if s != 200:
        record("L2.1", "capabilities over Minecraft connection", "FAIL",
               f"HTTP {s} {d}; 503 = handshake not done / server lacks mod, 426 = protocol mismatch, 403 = server network.enabled=false")
        return finish(a, 1)
    record("L2.1", "capabilities over Minecraft connection", "PASS", f"protocol {d.get('protocol')}")
    record("L2.2", f"protocol == {PROTOCOL}", "PASS" if d.get("protocol") == PROTOCOL else "FAIL", f"got {d.get('protocol')}")
    missing = CAPS - set(d.get("capabilities", []))
    record("L2.3", "server advertises all expected operations", "PASS" if not missing else "FAIL", f"missing {sorted(missing)}")
    record("L2.4", "bridge is read-only", "PASS" if d.get("read_only") is True and d.get("exclusive_control") is True else "FAIL",
           f"read_only={d.get('read_only')} exclusive_control={d.get('exclusive_control')}")

    rtts = []
    fails = 0
    for _ in range(a.rtt_samples):
        s, _, t = br.call("GET", "/network")
        if s == 200:
            rtts.append(t * 1000)
        else:
            fails += 1
        time.sleep(0.1)  # stay under the 20 req/s server limit
    if rtts:
        rs = sorted(rtts)
        p95 = rs[min(len(rs) - 1, int(len(rs) * 0.95))]
        detail = f"n={len(rs)} min={rs[0]:.0f}ms median={statistics.median(rs):.0f}ms p95={p95:.0f}ms max={rs[-1]:.0f}ms lost={fails}"
        record("L2.5", "round-trip latency / loss", "PASS" if fails == 0 and p95 < 2000 else "FAIL", detail)
    else:
        record("L2.5", "round-trip latency / loss", "FAIL", "no successful sample")

    # ---- L3 MCP session ---------------------------------------------------------------------------------------------
    key = str(uuid.uuid4())
    A, B = Session(br), Session(br)
    s, d = A.open(key)
    record("L3.1", "session open", "PASS" if s == 200 else "FAIL", f"HTTP {s} {'' if s == 200 else d}")
    if s != 200:
        return finish(a, 1)
    first = A.id
    s2, d2 = A.open(key)
    record("L3.2", "session open is idempotent for the same open key", "PASS" if s2 == 200 and A.id == first else "FAIL", f"HTTP {s2}")
    s, _, _ = br.call("POST", "/network/sessions/heartbeat", A.hdr(False), body=b"")
    record("L3.3", "heartbeat accepted", "PASS" if s == 200 else "FAIL", f"HTTP {s}")
    s, _, _ = br.call("POST", "/network/sessions/heartbeat", {"X-CCT-Session": str(uuid.uuid4())}, body=b"")
    record("L3.4", "unknown session id rejected (403)", "PASS" if s == 403 else "FAIL", f"HTTP {s}")
    s, d, _ = br.call("GET", "/computers", A.hdr(False))
    comps = d.get("computers", []) if s == 200 else []
    record("L3.5", "list authorized computers", "PASS" if s == 200 else "FAIL", f"HTTP {s}, {len(comps)} authorized")

    # ---- L4 permission denial / protocol hygiene -------------------------------------------------------------------
    s, _, _ = br.call("POST", "/computers/999999/control/acquire", A.hdr(False), body=b"")
    record("L4.1", "acquire of a non-authorized computer refused", "PASS" if 400 <= s < 500 else "FAIL", f"HTTP {s}")
    s, _, _ = br.call("GET", "/computers/999999/status", A.hdr(False))
    record("L4.2", "status without lease refused", "PASS" if 400 <= s < 500 else "FAIL", f"HTTP {s}")
    for path, verb in (("/computers/1/files", "GET"), ("/computers/1/type", "POST"), ("/computers/1/lua", "POST"),
                       ("/computers/1/trace", "GET")):
        s, _, _ = br.call(verb, path, A.hdr(False), body=b"" if verb == "POST" else None)
        record("L4.3", f"write/exec route {verb} {path} unavailable (403)", "PASS" if s == 403 else "FAIL", f"HTTP {s}")
    s, _, _ = br.call("POST", "/network/sessions/heartbeat", A.hdr(False), body=b"payload")
    record("L4.4", "non-empty request body rejected (413)", "PASS" if s == 413 else "FAIL", f"HTTP {s}")
    s, _, _ = br.call("GET", "/computers?bogus=1", A.hdr(False))
    record("L4.5", "unsupported query parameter rejected (400)", "PASS" if s == 400 else "FAIL", f"HTTP {s}")
    if a.burst:
        codes = [br.call("GET", "/network", timeout=5)[0] for _ in range(60)]
        n429 = codes.count(429)
        record("L4.6", "server rate limit answers 429", "PASS" if n429 else "FAIL",
               f"{n429}/60 were 429 (limit is 20 req/s per connection); other codes {sorted(set(codes) - {200, 429})}")
        time.sleep(2)

    # ---- L5 lock + read on an authorized computer ---------------------------------------------------------------------
    if a.computer is None:
        record("L5", "lock/status/screen on an authorized computer", "SKIP", "pass --computer ID after authorizing it in game")
    elif a.computer not in {c.get("id", c.get("computer")) for c in comps}:
        record("L5.0", f"computer {a.computer} appears in the authorized list", "FAIL",
               "authorize it in game (AI icon / /cctlive network authorize); grants last 30 min")
    else:
        cid = a.computer
        record("L5.0", f"computer {cid} appears in the authorized list", "PASS")
        s, d, _ = br.call("POST", f"/computers/{cid}/control/acquire", A.hdr(False), body=b"")
        got = s == 200 and d.get("owned")
        record("L5.1", "acquire exclusive lease", "PASS" if got else "FAIL", f"HTTP {s} {d if not got else ''}")
        if got:
            A.lease = d
            s, d, _ = br.call("GET", f"/computers/{cid}/status", A.hdr())
            record("L5.2", "status with lease", "PASS" if s == 200 else "FAIL", f"HTTP {s}")
            s, d, _ = br.call("GET", f"/computers/{cid}/screen?colors=1", A.hdr())
            record("L5.3", "screen read", "PASS" if s == 200 and "lines" in d else "FAIL", f"HTTP {s}")
            s, d, _ = br.call("GET", f"/computers/{cid}/control", A.hdr(False))
            record("L5.4", "control status reports ownership", "PASS" if s == 200 and d.get("owned") else "FAIL", f"HTTP {s}")
            if B.open()[0] == 200:
                s, _, _ = br.call("POST", f"/computers/{cid}/control/acquire", B.hdr(False), body=b"")
                record("L5.5", "second session cannot take the lock (409)", "PASS" if s == 409 else "FAIL", f"HTTP {s}")
                s, _, _ = br.call("GET", f"/computers/{cid}/status", {**B.hdr(False), "X-CCT-Lease": A.lease["lease_id"],
                                                                     "X-CCT-Lease-Epoch": str(A.lease["lease_epoch"])})
                record("L5.6", "other session cannot reuse my lease credentials", "PASS" if 400 <= s < 500 else "FAIL", f"HTTP {s}")
            old = dict(A.lease)
            s, _, _ = br.call("POST", f"/computers/{cid}/control/release", A.hdr(), body=b"")
            record("L5.7", "release", "PASS" if s == 200 else "FAIL", f"HTTP {s}")
            A.lease = old
            s, _, _ = br.call("GET", f"/computers/{cid}/status", A.hdr())
            record("L5.8", "released lease no longer works (409)", "PASS" if s == 409 else "FAIL", f"HTTP {s}")
            A.lease = None
            if B.id:
                s, d, _ = br.call("POST", f"/computers/{cid}/control/acquire", B.hdr(False), body=b"")
                record("L5.9", "lock handed over after release", "PASS" if s == 200 and d.get("owned") else "FAIL", f"HTTP {s}")
                if s == 200:
                    B.lease = d
                    br.call("POST", f"/computers/{cid}/control/release", B.hdr(), body=b"")
                    B.lease = None

        # ---- L6 soak: leases and heartbeats across idle time ----------------------------------------------------------
        if a.soak and got:
            soak(br, A, cid, a.soak)

    # ---- L7 clean shutdown ---------------------------------------------------------------------------------------------
    for sess in (A, B):
        if sess.id:
            br.call("POST", "/network/sessions/close", sess.hdr(False), body=b"")
    s, _, _ = br.call("POST", "/network/sessions/heartbeat", A.hdr(False), body=b"")
    record("L7.1", "closed session rejected on heartbeat (403)", "PASS" if s == 403 else "FAIL", f"HTTP {s}")
    return finish(a, None)


def soak(br, sess, cid, seconds):
    s, d, _ = br.call("POST", f"/computers/{cid}/control/acquire", sess.hdr(False), body=b"")
    if s != 200 or not d.get("owned"):
        record("L6.1", "soak: acquire", "FAIL", f"HTTP {s}")
        return
    sess.lease = d
    end, n, bad, rtts = time.time() + seconds, 0, [], []
    while time.time() < end:
        time.sleep(15)
        n += 1
        hs, _, _ = br.call("POST", "/network/sessions/heartbeat", sess.hdr(False), body=b"")
        ss, _, t = br.call("GET", f"/computers/{cid}/status", sess.hdr())
        if hs != 200 or ss != 200:
            bad.append(f"t+{n * 15}s hb={hs} status={ss}")
        else:
            rtts.append(t * 1000)
        print(f"      soak {n * 15}s hb={hs} status={ss}", flush=True)
    record("L6.1", f"soak {seconds}s: heartbeat + status every 15 s", "PASS" if not bad else "FAIL",
           f"{len(rtts)}/{n} ok, median {statistics.median(rtts):.0f}ms" if rtts and not bad else "; ".join(bad[:5]))
    br.call("POST", f"/computers/{cid}/control/release", sess.hdr(), body=b"")
    sess.lease = None


def finish(a, forced):
    failed = [r for r in results if r["status"] == "FAIL"]
    print(f"\n{len(results) - len(failed)} not failed, {len(failed)} FAIL "
          f"({sum(r['status'] == 'SKIP' for r in results)} skipped)")
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            json.dump({"time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": results}, f, ensure_ascii=False, indent=2)
    return forced if forced is not None else (1 if failed else 0)


if __name__ == "__main__":
    sys.exit(main())
