$ErrorActionPreference = 'Stop'
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'stop.flag') -Value 'stop' -Encoding ASCII
$statePath = Join-Path $PSScriptRoot 'process.json'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id $state.Pid -ErrorAction SilentlyContinue
    if ($process -and $process.Path) {
        $processPath = [IO.Path]::GetFullPath($process.Path)
        $expectedPath = [IO.Path]::GetFullPath($state.Executable)
        $installPath = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\'
        if ($processPath.Equals($expectedPath, [StringComparison]::OrdinalIgnoreCase) -and
            $processPath.StartsWith($installPath, [StringComparison]::OrdinalIgnoreCase)) {
            Stop-Process -Id $process.Id
        }
    }
}
