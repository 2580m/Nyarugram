param([string]$InstallRoot = 'D:\Botgram\Backend')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
if (-not $root.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Deployment must remain on D:' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source = Join-Path $workspace 'artifacts/backend'
$configPath = Join-Path $root 'deployment.json'
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($path in @($config.Executable, $config.DataPath)) {
    if (-not ([IO.Path]::GetFullPath($path)).StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Existing deployment paths leave the installation root' }
}
$version = (Get-FileHash -LiteralPath (Join-Path $source 'Botgram.Backend.dll') -Algorithm SHA256).Hash.Substring(0,12).ToLowerInvariant()
if ($version -eq $config.Version) { Write-Output "Version $version is already deployed"; exit 0 }
$release = Join-Path $root "releases/$version"
if (-not (Test-Path -LiteralPath $release)) {
    New-Item -ItemType Directory -Path $release | Out-Null
    Copy-Item -Path (Join-Path $source '*') -Destination $release
}
if ((Get-FileHash -LiteralPath (Join-Path $release 'Botgram.Backend.dll') -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath (Join-Path $source 'Botgram.Backend.dll') -Algorithm SHA256).Hash) { throw 'Release hash mismatch' }
$backup = Join-Path $root ('backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup | Out-Null
Set-Acl -LiteralPath $backup -AclObject (Get-Acl -LiteralPath (Split-Path -Parent $config.DataPath))
Copy-Item -LiteralPath $configPath -Destination (Join-Path $backup 'deployment.json')

function Wait-Stopped {
    $mutex = [Threading.Mutex]::new($false, ('Local\BotgramBackend-' + $config.Port))
    try {
        for ($attempt = 0; $attempt -lt 80; $attempt++) {
            $owned = $false
            try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
            if ($owned) { $mutex.ReleaseMutex(); return }
            Start-Sleep -Milliseconds 250
        }
        throw 'Previous supervisor did not stop'
    } finally { $mutex.Dispose() }
}

& (Join-Path $root 'Stop-BotgramBackend.ps1')
Wait-Stopped
# Snapshot only after the process has released the database; never overwrite live data.
if (Test-Path -LiteralPath $config.DataPath) { Copy-Item -LiteralPath $config.DataPath -Destination (Join-Path $backup 'botgram.json') }
try {
    $config.Version = $version
    $config.Executable = Join-Path $release 'Botgram.Backend.exe'
    $config.AppDirectory = $release
    $config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    & (Join-Path $root 'Start-BotgramBackend.ps1')
    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $config.Port + '/health') -TimeoutSec 2
            if ($health.status -eq 'ok') { $healthy = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $healthy) { throw 'Updated backend failed its health check' }
    [ordered]@{ Version=$version; Backup=$backup; Health='ok' } | ConvertTo-Json
} catch {
    & (Join-Path $root 'Stop-BotgramBackend.ps1')
    Wait-Stopped
    Copy-Item -LiteralPath (Join-Path $backup 'deployment.json') -Destination $configPath -Force
    & (Join-Path $root 'Start-BotgramBackend.ps1')
    throw
}
