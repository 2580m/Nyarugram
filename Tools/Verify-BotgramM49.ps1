$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dataPath = Join-Path $workspace 'Shared/botgram-m49.json'
$manifest = Get-Content -LiteralPath (Join-Path $workspace 'Shared/botgram-m49.manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath $dataPath -Algorithm SHA256).Hash
if ($hash -ne $manifest.dataSha256) { throw 'M49 checksum mismatch: update the data and version manifest together.' }
$data = Get-Content -LiteralPath $dataPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (@($data.regions | Where-Object code -ne '001').Count -ne $manifest.selectableRegionCount) { throw 'M49 region count mismatch' }
Write-Output "Verified bundled M49 $($manifest.snapshotVersion): $($manifest.selectableRegionCount) regions, SHA-256 $hash"
