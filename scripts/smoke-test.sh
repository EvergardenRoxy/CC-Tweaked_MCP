#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Smoke test for cct-run on macOS/Linux. Same cases as smoke-test.ps1 (which also runs on PowerShell 7),
# checked with grep so it needs nothing but bash 3.2+ (macOS default) and the cct-run launcher.
#
#   scripts/smoke-test.sh [path/to/bin/cct-run]
#
# Writes cct-run-smoke-report.txt in the current directory. Exit code 0 if every case passed.

set -u
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"

cct="${1:-}"
if [ -z "$cct" ]; then
    for c in "$root/bin/cct-run" "$root/cli/build/install/cct-run/bin/cct-run"; do
        if [ -x "$c" ]; then cct="$c"; break; fi
    done
fi
if [ -z "$cct" ] || [ ! -x "$cct" ]; then
    echo "cct-run launcher not found; pass its path as the first argument" >&2
    exit 2
fi
examples="$root/examples"
report="$PWD/cct-run-smoke-report.txt"

work="$(mktemp -d "${TMPDIR:-/tmp}/cct-run-smoke.XXXXXX")"
odd="$work/dir with space $(printf '\346\265\213\350\257\225')" # two CJK characters, as UTF-8 bytes
out="$work/runs"
mkdir -p "$odd" "$out"
cp "$examples/hello.lua" "$odd/hello.lua"
cp "$examples/storage_monitor.fixture.json" "$odd/fixture.json"
printf 'print("caf" .. string.char(195, 169))' > "$work/utf8.lua"
printf 'print("done")' > "$work/quick.lua"

passed=0
failed=0
{
    echo "cct-run smoke report"
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "os: $(uname -a)"
    echo "locale: LANG=${LANG:-} LC_ALL=${LC_ALL:-}"
    echo "JAVA_HOME: ${JAVA_HOME:-}"
    echo "cct-run: $cct"
    echo
} > "$report"

# check NAME EXPECTED_EXIT PATTERN -- ARGS...
# PATTERN is an extended regex that must match somewhere in stdout ("" = no check).
check() {
    local name="$1" expected="$2" pattern="$3"
    shift 4
    local start end code stdout problem=""
    start=$(date +%s)
    stdout="$("$cct" "$@" 2>"$work/stderr.txt")"
    code=$?
    end=$(date +%s)
    if [ "$code" -ne "$expected" ]; then
        problem="exit code $code, expected $expected"
    elif [ -n "$pattern" ] && ! printf '%s\n' "$stdout" | grep -Eq -- "$pattern"; then
        problem="stdout does not match: $pattern"
    fi
    if [ -z "$problem" ]; then
        passed=$((passed + 1))
        printf 'PASS %-32s exit=%-3s %3ss\n' "$name" "$code" "$((end - start))" | tee -a "$report"
    else
        failed=$((failed + 1))
        printf 'FAIL %-32s exit=%-3s %3ss %s\n' "$name" "$code" "$((end - start))" "$problem" | tee -a "$report"
        { echo "  args: $*"; echo "  stdout:"; printf '%s\n' "$stdout" | head -40 | sed 's/^/    /'
          echo "  stderr:"; head -20 "$work/stderr.txt" | sed 's/^/    /'; } >> "$report"
    fi
}

# JSON escapes the tab separators that print() puts between values, so patterns match a literal "\t".
check version                       0  '"cc_tweaked": "1\.120\.2"'                   -- version
check args-with-space-and-separator 0  '"args:\\ta\\tb c"'                             -- run "$examples/hello.lua" -- a "b c"
check runtime-error                 1  'runtime_error\.lua:4: attempt to index'        -- run "$examples/runtime_error.lua"
check syntax-error                  1  '"phase": "syntax"'                             -- run "$examples/syntax_error.lua"
check fixture-wired-network-monitor 0  '"iron_ingot +104"'                             -- run "$examples/storage_monitor.lua" --fixture "$examples/storage_monitor.fixture.json" --out "$out"
check trace-latest                  0  '"ok":false,"error":"Target .minecraft:nowhere. does not exist"' -- trace latest --out "$out" --kind peripheral_return
check screen-latest-monitor         0  '^    57,$'                                     -- screen latest --out "$out" --source monitor_0
check redstone-timeline-terminate   1  '"redstone_output": 2'                          -- run "$examples/redstone_door.lua" --fixture "$examples/redstone_door.fixture.json"
check scrolled-output               0  '"output": 12'                                  -- run "$examples/long_output.lua"
check timeout                       2  '"status": "timeout"'                           -- run "$examples/stuck.lua" --timeout 2
check too-long-without-yielding     1  'Too long without yielding'                     -- run "$examples/no_yield.lua"
check path-with-space-and-cjk       0  '"status": "ok"'                                -- run "$odd/hello.lua" --fixture "$odd/fixture.json" --out "$odd/runs"
check utf8-stdout                   0  "\"caf$(printf '\303\203\302\251')\""           -- run "$work/utf8.lua"
check usage-error                   64 ''                                              -- run "$examples/hello.lua" --frobnicate
check missing-program               65 ''                                              -- run no-such-file.lua
check startup-time                  0  '"status": "ok"'                                -- run "$work/quick.lua"

echo | tee -a "$report"
echo "$passed passed, $failed failed. Report: $report" | tee -a "$report"
echo "work dir: $work" >> "$report"
[ "$failed" -eq 0 ]
