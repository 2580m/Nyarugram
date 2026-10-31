$ErrorActionPreference = 'Stop'
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'stop.flag') -Value 'stop' -Encoding ASCII
$statePath = Join-Path $PSScriptRoot 'process.json'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id $state.Pid -ErrorAction SilentlyContinue
    if ($process -and $process.Path -eq $state.Executable -and $process.Path.StartsWith($PSScriptRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        Stop-Process -Id $process.Id
    }
}
