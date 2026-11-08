param(
    [Parameter(Mandatory = $true)][string]$InstallRoot,
    [Parameter(Mandatory = $true)][string]$AppDirectory,
    [string]$StorageMode
)
$ErrorActionPreference = 'Stop'

# The installation marker survives replacement of a release directory.
$primaryMarker = Join-Path $InstallRoot 'primary-storage.json'
$requiresPrimary = $StorageMode -eq 'NewStoragePrimary' -or (Test-Path -LiteralPath $primaryMarker)
if (-not $requiresPrimary) { return $false }
if ($StorageMode -ne 'NewStoragePrimary') {
    throw 'BOTGRAM_PRIMARY_STORAGE_REQUIRED: deployment must explicitly require NewStoragePrimary'
}

$settingsPath = Join-Path $AppDirectory 'appsettings.Production.json'
if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    throw 'BOTGRAM_PRIMARY_CONFIG_MISSING: appsettings.Production.json is required'
}
try {
    $settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    # Parser diagnostics can contain the connection string from the input.
    throw 'BOTGRAM_PRIMARY_CONFIG_INVALID: production configuration cannot be read'
}
if ($settings.Storage.Mode -ne 'NewStoragePrimary' -or
    $settings.Storage.ValidationBackend -notin @('RelationalBackend', 'FourComponent') -or
    [string]$settings.Storage.PrimaryAcknowledged -ne 'true') {
    throw 'BOTGRAM_PRIMARY_CONFIG_INVALID: explicit primary storage settings are required'
}
if ([string]::IsNullOrWhiteSpace($settings.Storage.PrimaryConnectionString)) {
    throw 'BOTGRAM_PRIMARY_CONNECTION_MISSING: an explicit PostgreSQL connection is required'
}
$keyDirectory = [string]$settings.Privacy.KeyDirectory
if ([string]::IsNullOrWhiteSpace($keyDirectory)) {
    throw 'BOTGRAM_PRIMARY_KEYS_MISSING: an explicit existing key directory is required'
}
if (-not [IO.Path]::IsPathRooted($keyDirectory)) {
    $keyDirectory = Join-Path $AppDirectory $keyDirectory
}
if (-not (Test-Path -LiteralPath $keyDirectory -PathType Container)) {
    throw 'BOTGRAM_PRIMARY_KEYS_MISSING: the configured key directory does not exist'
}
return $true
