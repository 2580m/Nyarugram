$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$config = Get-Content -LiteralPath (Join-Path $root 'deployment.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$mutex = [Threading.Mutex]::new($false, ('Local\BotgramBackend-' + $config.Port))
$owned = $false
try {
    try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
    if (-not $owned) { exit 0 }
    $env:ASPNETCORE_ENVIRONMENT = 'Production'
    $env:DOTNET_ENVIRONMENT = 'Production'
    $env:DOTNET_CLI_HOME = Join-Path $root 'runtime'
    $env:TEMP = Join-Path $root 'tmp'
    $env:TMP = $env:TEMP
    while (-not (Test-Path -LiteralPath (Join-Path $root 'stop.flag'))) {
        try {
            $requiresPrimary = & (Join-Path $root 'Assert-BotgramPrimaryStorage.ps1') `
                -InstallRoot $root -AppDirectory $config.AppDirectory -StorageMode $config.StorageMode
        } catch {
            $_.Exception.Message | Add-Content -LiteralPath (Join-Path $root 'logs/supervisor.log') -Encoding UTF8
            Start-Sleep -Seconds 5
            continue
        }
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
        $urls = 'http://127.0.0.1:' + $config.Port
        try {
            $lan = Get-NetIPAddress -InterfaceAlias $config.InterfaceAlias -AddressFamily IPv4 -ErrorAction Stop |
                Where-Object { $_.AddressState -eq 'Preferred' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1
            if ($lan) { $urls += ';http://' + $lan.IPAddress + ':' + $config.Port }
        } catch { }
        $arguments = '--urls "' + $urls + '" --DataPath "' + $config.DataPath + '"'
        if ($requiresPrimary) { $arguments += ' --Storage:Mode NewStoragePrimary' }
        try {
            $server = Start-Process -FilePath $config.Executable -ArgumentList $arguments -WorkingDirectory $config.AppDirectory -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $root "logs/$stamp.stdout.log") -RedirectStandardError (Join-Path $root "logs/$stamp.stderr.log")
            [ordered]@{ Pid=$server.Id; Executable=$config.Executable; Urls=$urls; StartedAt=(Get-Date).ToString('o') } |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'process.json') -Encoding UTF8
            $server.WaitForExit()
        } catch {
            $_.Exception.Message | Add-Content -LiteralPath (Join-Path $root 'logs/supervisor.log') -Encoding UTF8
        }
        if (-not (Test-Path -LiteralPath (Join-Path $root 'stop.flag'))) { Start-Sleep -Seconds 5 }
    }
} finally {
    if ($owned) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
