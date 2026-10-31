param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dependencyRootPath = [IO.Path]::GetFullPath($DependencyRoot)
if (-not $dependencyRootPath.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Dependencies must remain on D:' }
foreach ($directory in @('downloads','native','android-sdk','tmp','gradle','nuget')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $dependencyRootPath $directory) | Out-Null
}
# Keep the existing submodule revision pins. Sources live on D:, with junctions at their expected paths.
$modules = @{}
$currentPath = $null
foreach ($line in Get-Content -LiteralPath (Join-Path $workspace '.gitmodules')) {
    if ($line -match '^\s*path\s*=\s*(.+)$') { $currentPath = $Matches[1].Trim() }
    elseif ($line -match '^\s*url\s*=\s*https://github.com/(.+?)(?:\.git)?$') { $modules[$currentPath] = $Matches[1] -replace '\.git$', '' }
    elseif ($line -match '^\s*url\s*=\s*https://chromium.googlesource.com/libyuv/libyuv') { $modules[$currentPath] = 'lemenkov/libyuv' }
}
foreach ($entry in (& git -C $workspace ls-tree -r HEAD)) {
    if ($entry -notmatch '^160000 commit ([a-f0-9]{40})\s+(.+)$') { continue }
    $revision = $Matches[1]; $relative = $Matches[2]
    $repository = $modules[$relative]
    if (-not $repository) { throw "No GitHub source for $relative" }
    $target = [IO.Path]::GetFullPath((Join-Path $workspace $relative))
    if (-not $target.StartsWith($workspace + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe workspace target: $target" }
    if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target).LinkType -eq 'Junction') { continue }
    if ((Test-Path -LiteralPath $target) -and @(Get-ChildItem -LiteralPath $target -Force).Count -gt 0) { continue }
    $name = ($repository -split '/')[-1]
    $source = Join-Path $dependencyRootPath "native/$name-$revision"
    if (-not (Test-Path -LiteralPath $source)) {
        $archive = Join-Path $dependencyRootPath "downloads/$name-$revision.zip"
        & curl.exe --fail --silent --show-error --location --retry 2 --connect-timeout 20 --max-time 300 --output $archive "https://codeload.github.com/$repository/zip/$revision"
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $repository@$revision" }
        & tar.exe -xf $archive -C (Join-Path $dependencyRootPath 'native')
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $source)) { throw "Extraction failed: $source" }
    }
    if (Test-Path -LiteralPath $target) {
        # Only remove an explicitly verified empty placeholder, never recursively.
        if (@(Get-ChildItem -LiteralPath $target -Force).Count -ne 0) { throw "Target is no longer empty: $target" }
        Remove-Item -LiteralPath $target
    }
    New-Item -ItemType Junction -Path $target -Target $source | Out-Null
    Write-Output "Linked $relative -> $source"
}
