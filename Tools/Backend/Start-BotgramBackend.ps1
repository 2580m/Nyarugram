$ErrorActionPreference = 'Stop'
$flag = Join-Path $PSScriptRoot 'stop.flag'
if (Test-Path -LiteralPath $flag) { Remove-Item -LiteralPath $flag }
$shell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $PSScriptRoot 'Run-BotgramBackend.ps1') + '"'
Start-Process -FilePath $shell -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden
