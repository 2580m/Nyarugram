param([string]$TestRoot = ('D:\BotgramBuild\tmp\primary-storage-guard-' + [Guid]::NewGuid().ToString('N')))
$ErrorActionPreference = 'Stop'
$guard = Join-Path $PSScriptRoot 'Backend/Assert-BotgramPrimaryStorage.ps1'
$release = Join-Path $TestRoot 'release'
$keys = Join-Path $release 'keys'
New-Item -ItemType Directory -Path $keys -Force | Out-Null
$settingsPath = Join-Path $release 'appsettings.Production.json'
$checks = 0

function Write-Settings {
    param([hashtable]$Settings)
    $Settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $settingsPath -Encoding UTF8
}

function Expect-Rejection {
    param([string]$Code, [string]$Mode = 'NewStoragePrimary')
    $rejected = $false
    try { $null = & $guard -InstallRoot $TestRoot -AppDirectory $release -StorageMode $Mode }
    catch {
        if (-not $_.Exception.Message.StartsWith($Code + ':')) { throw }
        if ($_.Exception.Message.Contains('secret-sentinel')) { throw 'Sensitive input leaked into diagnostics' }
        $rejected = $true
    }
    if (-not $rejected) { throw "Expected rejection $Code" }
    $script:checks++
}

$settings = @{
    Storage = @{ Mode = 'NewStoragePrimary'; ValidationBackend = 'RelationalBackend'; PrimaryAcknowledged = $true
        PrimaryConnectionString = 'Host=127.0.0.1;Port=1;Database=fixture;Username=fixture' }
    Privacy = @{ KeyDirectory = $keys }
}
Expect-Rejection 'BOTGRAM_PRIMARY_CONFIG_MISSING'
Set-Content -LiteralPath $settingsPath -Value '{"secret-sentinel":' -Encoding UTF8
Expect-Rejection 'BOTGRAM_PRIMARY_CONFIG_INVALID'

Write-Settings $settings
if ((& $guard -InstallRoot $TestRoot -AppDirectory $release -StorageMode 'NewStoragePrimary') -ne $true) {
    throw 'Valid primary configuration was rejected'
}
$checks++
$settings.Storage.Mode = 'LegacyOnly'
Write-Settings $settings
Expect-Rejection 'BOTGRAM_PRIMARY_CONFIG_INVALID'
$settings.Storage.Mode = 'NewStoragePrimary'
$settings.Storage.ValidationBackend = 'SnapshotBridge'
Write-Settings $settings
Expect-Rejection 'BOTGRAM_PRIMARY_CONFIG_INVALID'
$settings.Storage.ValidationBackend = 'RelationalBackend'
$settings.Storage.PrimaryAcknowledged = $false
Write-Settings $settings
Expect-Rejection 'BOTGRAM_PRIMARY_CONFIG_INVALID'
$settings.Storage.PrimaryAcknowledged = $true
$connection = $settings.Storage.PrimaryConnectionString
$settings.Storage.PrimaryConnectionString = ''
Write-Settings $settings
Expect-Rejection 'BOTGRAM_PRIMARY_CONNECTION_MISSING'
$settings.Storage.PrimaryConnectionString = $connection
$settings.Privacy.KeyDirectory = ''
Write-Settings $settings
Expect-Rejection 'BOTGRAM_PRIMARY_KEYS_MISSING'
$settings.Privacy.KeyDirectory = Join-Path $release 'missing-keys'
Write-Settings $settings
Expect-Rejection 'BOTGRAM_PRIMARY_KEYS_MISSING'
$settings.Privacy.KeyDirectory = 'keys'
Write-Settings $settings
if ((& $guard -InstallRoot $TestRoot -AppDirectory $release -StorageMode 'NewStoragePrimary') -ne $true) {
    throw 'Existing relative key directory was rejected'
}
$checks++

# A migrated installation stays protected if deployment.json loses its mode.
Set-Content -LiteralPath (Join-Path $TestRoot 'primary-storage.json') -Value '{}' -Encoding UTF8
Expect-Rejection 'BOTGRAM_PRIMARY_STORAGE_REQUIRED' 'LegacyOnly'
Expect-Rejection 'BOTGRAM_PRIMARY_STORAGE_REQUIRED' ''
$legacyRoot = Join-Path $TestRoot 'legacy-installation'
New-Item -ItemType Directory -Path $legacyRoot | Out-Null
if ((& $guard -InstallRoot $legacyRoot -AppDirectory $release -StorageMode 'LegacyOnly') -ne $false) {
    throw 'Explicit legacy installation was incorrectly classified as migrated'
}
$checks++

# Exercise the supervisor's actual entry point with an incomplete release.
$entryRoot = Join-Path $TestRoot 'supervisor'
$entryRelease = Join-Path $entryRoot 'release'
New-Item -ItemType Directory -Path (Join-Path $entryRoot 'logs'), $entryRelease -Force | Out-Null
Copy-Item -LiteralPath $guard -Destination $entryRoot
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Backend/Run-BotgramBackend.ps1') -Destination $entryRoot
@{
    StorageMode = 'NewStoragePrimary'; AppDirectory = $entryRelease
    Executable = (Join-Path $entryRelease 'must-not-start.exe')
    DataPath = (Join-Path $entryRoot 'must-not-create.json'); Port = (Get-Random -Minimum 20000 -Maximum 60000)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $entryRoot 'deployment.json') -Encoding UTF8
$shell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $entryRoot 'Run-BotgramBackend.ps1') + '"'
$supervisor = Start-Process -FilePath $shell -ArgumentList $arguments -WorkingDirectory $entryRoot `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $entryRoot 'stdout.log') `
    -RedirectStandardError (Join-Path $entryRoot 'stderr.log')
try {
    $rejected = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $log = Join-Path $entryRoot 'logs/supervisor.log'
        if ((Test-Path -LiteralPath $log) -and
            (Get-Content -LiteralPath $log -Raw).Contains('BOTGRAM_PRIMARY_CONFIG_MISSING')) {
            $rejected = $true
            break
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $rejected) { throw 'Supervisor did not reject the incomplete primary release' }
    foreach ($name in @('process.json', 'must-not-create.json', 'must-not-create.json.lock')) {
        if (Test-Path -LiteralPath (Join-Path $entryRoot $name)) { throw "Unexpected startup artifact $name" }
    }
    $checks++
} finally {
    Set-Content -LiteralPath (Join-Path $entryRoot 'stop.flag') -Value 'stop' -Encoding ASCII
    if (-not $supervisor.WaitForExit(10000)) { Stop-Process -Id $supervisor.Id }
}
Write-Output "PASS: primary storage startup guard ($checks checks)"
