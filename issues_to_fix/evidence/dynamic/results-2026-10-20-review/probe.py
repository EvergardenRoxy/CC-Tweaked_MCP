#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Reproduce review observations against a built main distribution; no game access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(argv, **kwargs):
    return subprocess.run(argv, text=True, capture_output=True, check=True, timeout=30, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    launcher = args.launcher.resolve()
    repo = Path(__file__).resolve().parents[4]
    baseline = "25a392102b1ce47c32743564298df0622b04e9ca"
    # Audit documents may be dirty; executable sources must match the recorded baseline.
    source_paths = ["engine", "cli", "mod", "gradle", "build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat"]
    changed = run(["git", "diff", "--name-only", baseline, "--", *source_paths], cwd=repo).stdout
    if changed.strip():
        raise SystemExit("Runtime/build sources differ from the recorded baseline")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.jdk.resolve())
    results = {
        "schema": "cct-run/review-observations@1",
        "source_commit": baseline,
        "runtime_build_sources_match_baseline": True,
        "worktree_status": run(["git", "status", "--porcelain"], cwd=repo).stdout.splitlines(),
        "java": run([str(args.jdk / "bin/java"), "-version"]).stderr.splitlines()[0],
        "observations": {},
        "limitations": ["No Minecraft or third-party peripheral access", "No Windows race reproduction", "Trace observation uses the public engine writer API"],
    }
    with tempfile.TemporaryDirectory(prefix="cct-review-") as tmp:
        work = Path(tmp)
        def mcp(data):
            result = run([str(launcher), "mcp", "--out", str(work / "runs")], input=data, env=env)
            return [json.loads(line) for line in result.stdout.splitlines()]

        loose = mcp("{jsonrpc:'2.0',id:1,method:'ping'}\n")
        assert loose == [{"jsonrpc": "2.0", "id": 1, "result": {}}], loose
        results["observations"]["non_strict_json"] = {"input": "{jsonrpc:'2.0',id:1,method:'ping'}", "responses": loose}

        request = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}}
        batch = [{"jsonrpc": "2.0", "id": 2, "method": "ping"}]
        replies = mcp(json.dumps(request) + "\n" + json.dumps(batch) + "\n")
        assert replies[0]["result"]["protocolVersion"] == "2025-03-26", replies
        assert replies[1]["error"]["code"] == -32600, replies
        results["observations"]["old_protocol_batch"] = {"negotiated_protocol": replies[0]["result"]["protocolVersion"], "batch_response": replies[1]}

        invalid_id = {"jsonrpc": "2.0", "id": True, "method": "ping"}
        valid = {"jsonrpc": "2.0", "id": 3, "method": "ping"}
        replies = mcp(json.dumps(invalid_id) + "\n" + json.dumps(valid) + "\n")
        assert replies[0]["error"]["code"] == -32600 and replies[1]["result"] == {}, replies
        results["observations"]["invalid_id_service_survives"] = {"responses": replies}

        program = work / "probe.lua"
        program.write_text("print('ok')", encoding="utf-8")
        fixture = work / "fixture.json"
        fixture.write_text(json.dumps({"files": {"probe.lua": "fixture version"}}), encoding="utf-8")
        response = json.loads(run([str(launcher), "run", str(program), "--fixture", str(fixture), "--out", str(work / "collision")], env=env).stdout)
        assert response["status"] == "ok" and "fixture_file_overwritten" in json.dumps(response["notes"]), response
        results["observations"]["fixture_collision_not_silent"] = {"status": response["status"], "note_kinds": [x["kind"] for x in response["notes"]]}

        cp = os.pathsep.join(str(x) for x in sorted((launcher.parent.parent / "lib").glob("*.jar")))
        java_source = Path(__file__).with_name("TraceCapacityProbe.java")
        run([str(args.jdk / "bin/javac"), "--release", "17", "-cp", cp, "-d", str(work), str(java_source)])
        trace = work / "trace.jsonl"
        run([str(args.jdk / "bin/java"), "-cp", str(work) + os.pathsep + cp, "TraceCapacityProbe", str(trace)])
        assert trace.stat().st_size > 1024 * 1024
        results["observations"]["trace_writer_exceeds_1_mib"] = {"bytes": trace.stat().st_size, "records_requested": 512, "scope": "public Trace API; not a claim of unbounded dynamic testing"}

    results["distribution_jars"] = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in sorted((launcher.parent.parent / "lib").glob("*.jar"))}
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("5 observations recorded:", args.output)


if __name__ == "__main__":
    main()
