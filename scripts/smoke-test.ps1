# SPDX-License-Identifier: MPL-2.0
<#
.SYNOPSIS
    Smoke test for cct-run. Windows PowerShell 5.1 or PowerShell 7+ (Windows, macOS, Linux).

.DESCRIPTION
    Runs the bundled examples through the cct-run launcher, checks exit codes and key JSON fields, and
    writes a report (cct-run-smoke-report.json) to send back to the maintainers. It also records
    Windows-specific behaviour: argument passing through cct-run.bat, the "--" separator, console
    encoding and paths with spaces and non-ASCII characters.

    This file is ASCII only on purpose: Windows PowerShell 5.1 misreads non-ASCII text in UTF-8 files
    without a BOM.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1

.EXAMPLE
    pwsh scripts/smoke-test.ps1 -CctRun C:\tools\cct-run\bin\cct-run.bat

.PARAMETER NoJavaSearch
    Skip listing java.exe files under the usual install folders. The list (paths only) goes into the report
    to help cct-run find the Java runtime that Minecraft launchers install.
#>
param(
    [string]$CctRun = "",
    [string]$Examples = "",
    [string]$Report = "cct-run-smoke-report.json", # note: PowerShell names are case-insensitive, so no $report below
    [switch]$NoJavaSearch
)

$ErrorActionPreference = "Continue" # native commands writing to stderr must not abort the script
$onWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$root = Split-Path -Parent $PSScriptRoot

# --- locate cct-run and the examples ------------------------------------------------------------
if (-not $CctRun) {
    $name = "cct-run"
    if ($onWindows) { $name = "cct-run.bat" }
    $candidates = @(
        [System.IO.Path]::Combine($root, "bin", $name),                                       # unzipped release
        [System.IO.Path]::Combine($root, "cli", "build", "install", "cct-run", "bin", $name)  # source checkout
    )
    foreach ($c in $candidates) { if (Test-Path -LiteralPath $c) { $CctRun = $c; break } }
}
if (-not $CctRun -or -not (Test-Path -LiteralPath $CctRun)) {
    Write-Host "cct-run launcher not found. Pass -CctRun <path to bin\cct-run.bat>." -ForegroundColor Red
    exit 2
}
if (-not $Examples) { $Examples = [System.IO.Path]::Combine($root, "examples") }
if (-not (Test-Path -LiteralPath $Examples)) {
    Write-Host "examples folder not found. Pass -Examples <path>." -ForegroundColor Red
    exit 2
}
$CctRun = (Resolve-Path -LiteralPath $CctRun).Path
$Examples = (Resolve-Path -LiteralPath $Examples).Path

# --- scratch space: a path with spaces and non-ASCII characters (built from code points) ---------
$cjk = [string][char]0x6D4B + [string][char]0x8BD5 # two CJK characters
$work = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "cct-run-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$odd = [System.IO.Path]::Combine($work, "dir with space " + $cjk)
$out = [System.IO.Path]::Combine($work, "runs")
New-Item -ItemType Directory -Force -Path $odd | Out-Null
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Ex([string]$file) { return [System.IO.Path]::Combine($Examples, $file) }

# Program printing the bytes of UTF-8 "e acute": CC: Tweaked strings are bytes, so the screen holds two
# characters, U+00C3 U+00A9. Written with byte escapes so this script stays ASCII.
$utf8Program = [System.IO.Path]::Combine($work, "utf8.lua")
[System.IO.File]::WriteAllText($utf8Program, 'print("caf" .. string.char(195, 169))')
$expectedUtf8 = "caf" + [string][char]0xC3 + [string][char]0xA9

$quick = [System.IO.Path]::Combine($work, "quick.lua")
[System.IO.File]::WriteAllText($quick, 'print("done")')

Copy-Item -LiteralPath (Ex "hello.lua") -Destination ([System.IO.Path]::Combine($odd, "hello.lua"))
Copy-Item -LiteralPath (Ex "storage_monitor.fixture.json") -Destination ([System.IO.Path]::Combine($odd, "fixture.json"))

# --- running cct-run ----------------------------------------------------------------------------
$originalEncoding = [Console]::OutputEncoding

function Invoke-Cct([string[]]$CmdArgs, [System.Text.Encoding]$Encoding, [hashtable]$Env = @{}) {
    [Console]::OutputEncoding = $Encoding
    $saved = @{}
    foreach ($k in $Env.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $Env[$k]) }
    $errFile = [System.IO.Path]::Combine($work, "stderr.txt")
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $lines = & $CctRun @CmdArgs 2> $errFile
    $code = $LASTEXITCODE
    $sw.Stop()
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
    [Console]::OutputEncoding = $originalEncoding
    $stderr = ""
    if (Test-Path -LiteralPath $errFile) { $stderr = (Get-Content -LiteralPath $errFile | Out-String).Trim() }
    return [pscustomobject]@{
        Code    = $code
        Stdout  = (($lines | ForEach-Object { [string]$_ }) -join "`n")
        Stderr  = $stderr
        Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 2)
    }
}

function Parse-Json([string]$text) {
    try { return $text | ConvertFrom-Json } catch { return $null }
}

# A Java 8 home, if one is installed, to check that cct-run switches to an installed Java 17+ by itself.
$java8 = $null
$java8Roots = @(${env:ProgramFiles}, ${env:ProgramFiles(x86)}, "/usr/lib/jvm", "/Library/Java/JavaVirtualMachines") | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
foreach ($r in $java8Roots) {
    foreach ($release in (Get-ChildItem -LiteralPath $r -Recurse -Depth 4 -Filter "release" -ErrorAction SilentlyContinue)) {
        if ((Get-Content -LiteralPath $release.FullName -ErrorAction SilentlyContinue) -match '^JAVA_VERSION="1\.8') { $java8 = $release.DirectoryName; break }
    }
    if ($java8) { break }
}

# --- test cases: name, arguments, expected exit code, check returning $null (pass) or a problem -----
$utf8 = New-Object System.Text.UTF8Encoding($false)
$cases = @(
    @{ Name = "version"; Args = @("version"); Exit = 0; Check = {
        param($r, $j) if ($j.cc_tweaked -ne "1.120.2") { "cc_tweaked = '$($j.cc_tweaked)'" } } },
    @{ Name = "args-with-space-and-separator"; Args = @("run", (Ex "hello.lua"), "--", "a", "b c"); Exit = 0; Check = {
        param($r, $j) if ($j.screen.lines[1] -ne "args:`ta`tb c") { "screen line 2 = '$($j.screen.lines[1])' (expected 'args:<TAB>a<TAB>b c')" } } },
    @{ Name = "runtime-error"; Args = @("run", (Ex "runtime_error.lua")); Exit = 1; Check = {
        param($r, $j) if ($j.error.phase -ne "runtime" -or $j.error.message -notlike "*runtime_error.lua:4:*") { "error = $($j.error | ConvertTo-Json -Compress)" } } },
    @{ Name = "syntax-error"; Args = @("run", (Ex "syntax_error.lua")); Exit = 1; Check = {
        param($r, $j) if ($j.error.phase -ne "syntax") { "phase = '$($j.error.phase)'" } } },
    @{ Name = "fixture-wired-network-monitor"; Args = @("run", (Ex "storage_monitor.lua"), "--fixture", (Ex "storage_monitor.fixture.json"), "--out", $out); Exit = 0; Check = {
        param($r, $j) $row = $j.monitors.monitor_0[1]; if ($row -notlike "iron_ingot*104") { "monitor_0 row 2 = '$row'" } } },
    @{ Name = "trace-latest"; Args = @("trace", "latest", "--out", $out, "--kind", "peripheral_return"); Exit = 0; Raw = $true; Check = {
        param($r, $j)
        $records = @($r.Stdout -split "`n" | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
        $errors = @($records | Where-Object { -not $_.ok })
        if ($records.Count -lt 3 -or $errors.Count -ne 1) { "records=$($records.Count) errors=$($errors.Count)" } } },
    @{ Name = "screen-latest-monitor"; Args = @("screen", "latest", "--out", $out, "--source", "monitor_0"); Exit = 0; Check = {
        param($r, $j) if ($j.size[0] -ne 57 -or $j.size[1] -ne 24) { "size = $($j.size -join 'x')" } } },
    @{ Name = "redstone-timeline-terminate"; Args = @("run", (Ex "redstone_door.lua"), "--fixture", (Ex "redstone_door.fixture.json")); Exit = 1; Check = {
        param($r, $j) if ($j.error.message -ne "Terminated" -or $j.trace_counts.redstone_output -ne 2) { "message='$($j.error.message)' redstone_output=$($j.trace_counts.redstone_output)" } } },
    @{ Name = "scrolled-output"; Args = @("run", (Ex "long_output.lua")); Exit = 0; Check = {
        param($r, $j) if ($j.trace_counts.output -ne 12) { "output records = $($j.trace_counts.output)" } } },
    @{ Name = "timeout"; Args = @("run", (Ex "stuck.lua"), "--timeout", "2"); Exit = 2; Check = {
        param($r, $j) if ($j.status -ne "timeout") { "status = $($j.status)" } } },
    @{ Name = "tick-rate"; Args = @("run", (Ex "stuck.lua"), "--timeout", "3"); Exit = 2; Check = {
        param($r, $j) if ($j.duration.tick_rate -lt 18.5 -or $j.duration.tick_rate -gt 21.5) { "tick_rate = $($j.duration.tick_rate) (expected about 20)" } } },
    @{ Name = "too-long-without-yielding"; Args = @("run", (Ex "no_yield.lua")); Exit = 1; Check = {
        param($r, $j) if ($j.error.message -notlike "*Too long without yielding*") { "message = '$($j.error.message)'" } } },
    @{ Name = "path-with-space-and-cjk"; Args = @("run", ([System.IO.Path]::Combine($odd, "hello.lua")), "--fixture", ([System.IO.Path]::Combine($odd, "fixture.json")), "--out", ([System.IO.Path]::Combine($odd, "runs"))); Exit = 0; Check = {
        param($r, $j) if (-not (Test-Path -LiteralPath $j.run_dir)) { "run_dir not found: $($j.run_dir)" } } },
    @{ Name = "utf8-stdout"; Args = @("run", $utf8Program); Exit = 0; Encoding = $utf8; Check = {
        param($r, $j) if ($j.screen.lines[0] -ne $expectedUtf8) { "screen = '$($j.screen.lines[0])' (stdout decoded as UTF-8)" } } },
    @{ Name = "usage-error"; Args = @("run", (Ex "hello.lua"), "--frobnicate"); Exit = 64; Raw = $true; Check = { param($r, $j) $null } },
    @{ Name = "missing-program"; Args = @("run", "no-such-file.lua"); Exit = 65; Raw = $true; Check = { param($r, $j) $null } },
    @{ Name = "startup-time"; Args = @("run", $quick); Exit = 0; Check = { param($r, $j) $null } },
    @{ Name = "old-java-switch"; Args = @("run", $quick); Exit = 0; Env = @{ JAVA_HOME = $java8 }; SkipIf = (-not $java8); Check = {
        param($r, $j) if ($r.Stderr -notlike "*is too old; using Java*") { "no switch message on stderr: $($r.Stderr)" } } },
    @{ Name = "guide"; Args = @("guide"); Exit = 0; Raw = $true; Check = {
        param($r, $j) if ($r.Stdout -notlike "*cct-run agent guide*") { "guide text missing" } } }
)

# --- run ----------------------------------------------------------------------------------------
$results = @()
Write-Host ("cct-run: " + $CctRun)
foreach ($case in $cases) {
    if ($case.SkipIf) {
        Write-Host ("SKIP {0,-32} (no Java 8 installation found)" -f $case.Name) -ForegroundColor Yellow
        $results += [pscustomobject]@{ name = $case.Name; ok = $true; skipped = $true; problem = $null; exit = $null; expected_exit = $case.Exit; seconds = 0; args = $case.Args; stdout_head = ""; stderr_head = "" }
        continue
    }
    $encoding = $utf8
    if ($case.Encoding) { $encoding = $case.Encoding }
    $caseEnv = @{}
    if ($case.Env) { $caseEnv = $case.Env }
    $r = Invoke-Cct $case.Args $encoding $caseEnv
    $problem = $null
    $j = $null
    if (-not $case.Raw) {
        $j = Parse-Json $r.Stdout
        if ($null -eq $j) { $problem = "stdout is not valid JSON" }
    }
    if (-not $problem -and $r.Code -ne $case.Exit) { $problem = "exit code $($r.Code), expected $($case.Exit)" }
    if (-not $problem) { $problem = & $case.Check $r $j }
    $ok = -not $problem
    $color = "Green"; $tag = "PASS"
    if (-not $ok) { $color = "Red"; $tag = "FAIL" }
    Write-Host ("{0} {1,-32} exit={2,-3} {3,6}s {4}" -f $tag, $case.Name, $r.Code, $r.Seconds, $problem) -ForegroundColor $color
    $head = $r.Stdout
    if ($head.Length -gt 1500) { $head = $head.Substring(0, 1500) }
    $errHead = $r.Stderr
    if ($errHead.Length -gt 1500) { $errHead = $errHead.Substring(0, 1500) }
    $results += [pscustomobject]@{
        name = $case.Name; ok = $ok; problem = $problem; exit = $r.Code; expected_exit = $case.Exit
        seconds = $r.Seconds; args = $case.Args; stdout_head = $head; stderr_head = $errHead
    }
}

# Informational: the same UTF-8 program decoded with the console's original encoding. A mismatch here is
# expected on consoles that are not UTF-8 and tells agents they must set UTF-8 before reading cct-run's stdout.
$r = Invoke-Cct @("run", $utf8Program) $originalEncoding
$j = Parse-Json $r.Stdout
$defaultEncodingLine = $null
if ($j) { $defaultEncodingLine = $j.screen.lines[0] }
$defaultEncodingOk = $defaultEncodingLine -eq $expectedUtf8

# cmd.exe can run the .bat directly (what an agent using cmd would do).
$cmdCode = $null
if ($onWindows) {
    & cmd.exe /c "`"$CctRun`" version >NUL 2>NUL"
    $cmdCode = $LASTEXITCODE
}

# Where do Minecraft launchers keep Java on this machine? (paths only; used to design Java discovery)
$javaCandidates = @()
if ($onWindows -and -not $NoJavaSearch) {
    $roots = @($env:LOCALAPPDATA, $env:APPDATA, ${env:ProgramFiles(x86)}, $env:ProgramFiles) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    foreach ($r in $roots) {
        $found = Get-ChildItem -LiteralPath $r -Recurse -Depth 7 -Filter "java.exe" -ErrorAction SilentlyContinue | Select-Object -First 15
        foreach ($f in $found) { $javaCandidates += $f.FullName }
    }
}

# --- report -------------------------------------------------------------------------------------
$version = Parse-Json (Invoke-Cct @("version") $utf8).Stdout
$codePage = $null
if ($onWindows) { $codePage = (& cmd.exe /c chcp) -join " " }
$skipped = @($results | Where-Object { $_.skipped }).Count
$passed = @($results | Where-Object { $_.ok -and -not $_.skipped }).Count
$failed = @($results | Where-Object { -not $_.ok }).Count
$reportData = [pscustomobject]@{
    schema = "cct-run/smoke-report@1"
    created_at = (Get-Date).ToUniversalTime().ToString("o")
    passed = $passed
    failed = $failed
    skipped = $skipped
    environment = [pscustomobject]@{
        os = [System.Environment]::OSVersion.VersionString
        is_windows = $onWindows
        powershell = $PSVersionTable.PSVersion.ToString()
        powershell_edition = [string]$PSVersionTable.PSEdition
        console_output_encoding = $originalEncoding.WebName
        code_page = $codePage
        cct_run = $CctRun
        java = $version.java
        java_home = $version.java_home
        env_JAVA_HOME = $env:JAVA_HOME
        java_on_path = [string](Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source)
        java_candidates = $javaCandidates
        java8_for_switch_test = $java8
        work_dir = $work
    }
    default_encoding_check = [pscustomobject]@{
        ok = $defaultEncodingOk
        screen_line = $defaultEncodingLine
        note = "stdout decoded with the console's original encoding; cct-run escapes non-ASCII as \uXXXX, so this should be true on any code page"
    }
    cmd_exe_exit = $cmdCode
    results = $results
}
$reportPath = [System.IO.Path]::GetFullPath($Report)
[System.IO.File]::WriteAllText($reportPath, ($reportData | ConvertTo-Json -Depth 6), $utf8)

Write-Host ""
Write-Host ("{0} passed, {1} failed, {2} skipped. Default-encoding UTF-8 check: {3}. cmd.exe exit: {4}" -f $passed, $failed, $skipped, $defaultEncodingOk, $cmdCode)
Write-Host ("Report: " + $reportPath)
if ($failed -ne 0) { exit 1 }
exit 0
