$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "D:\AI\DSH\cc-t\CC-Tweaked_MCP\cli\build\install\cct-run\bin\cct-run.bat"
$psi.Arguments = "mcp --out D:\AI\DSH\cc-t\verify\dyn\mcp-runs"
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$p = [System.Diagnostics.Process]::Start($psi)
function SendRecv([string]$label, [string]$line, [bool]$expectReply) {
  $p.StandardInput.WriteLine($line)
  $p.StandardInput.Flush()
  if (-not $expectReply) { Write-Host ("[{0}] (notification, no reply expected)" -f $label); return }
  $read = $p.StandardOutput.ReadLineAsync()
  if ($read.Wait(30000)) { $resp = $read.Result } else { $resp = "<<TIMEOUT>>" }
  if ($null -eq $resp) { $resp = "<<EOF>>" }
  Write-Host ("[{0}] {1}" -f $label, $resp.Substring(0, [Math]::Min(400, $resp.Length)))
}
SendRecv "init" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' $true
SendRecv "initialized" '{"jsonrpc":"2.0","method":"notifications/initialized"}' $false
$deep = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"pad":' + ('[' * 1000000) + ('0' + ']' * 1000000) + '}}'
Write-Host ("deep line bytes: {0}" -f $deep.Length)
SendRecv "deep-nested-1M" $deep $true
SendRecv "after-deep" '{"jsonrpc":"2.0","id":3,"method":"tools/list"}' $true
$huge = '{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{"pad":"' + ('A' * 8000000) + '"}}'
Write-Host ("huge line bytes: {0}" -f $huge.Length)
SendRecv "huge-8MB" $huge $true
SendRecv "after-huge" '{"jsonrpc":"2.0","id":5,"method":"tools/list"}' $true
Write-Host ("alive={0}" -f (-not $p.HasExited))
$p.Kill($true)
