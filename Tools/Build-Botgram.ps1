param(
    [string]$DependencyRoot = 'D:\BotgramBuild',
    [ValidateSet('Debug', 'Standalone')][string]$Variant = 'Debug'
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& (Join-Path $PSScriptRoot 'Verify-BotgramM49.ps1')
if (-not ([IO.Path]::GetFullPath($DependencyRoot)).StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Dependencies must remain on D:' }
$env:GRADLE_USER_HOME = Join-Path $DependencyRoot 'gradle'
$env:ANDROID_HOME = Join-Path $DependencyRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:NUGET_PACKAGES = Join-Path $DependencyRoot 'nuget'
$env:DOTNET_CLI_HOME = Join-Path $DependencyRoot 'dotnet'
$env:CARGO_HOME = Join-Path $DependencyRoot 'cargo'
$env:RUSTUP_HOME = Join-Path $DependencyRoot 'rustup'
$env:TEMP = Join-Path $DependencyRoot 'tmp'
$env:TMP = $env:TEMP
$gradle = Join-Path $DependencyRoot 'tools/gradle-8.13/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw "Install verified Gradle 8.13 on D: first: $gradle" }
$arguments = @("-Djava.io.tmpdir=$env:TEMP", '--project-dir', $workspace, '--console=plain', '--no-daemon')
if ($env:HTTPS_PROXY) {
    $proxy = [Uri]$env:HTTPS_PROXY
    $arguments += @("-Dhttps.proxyHost=$($proxy.Host)", "-Dhttps.proxyPort=$($proxy.Port)", "-Dhttp.proxyHost=$($proxy.Host)", "-Dhttp.proxyPort=$($proxy.Port)")
}
$arguments += ":TMessagesProj_AppStandalone:assembleAfat$Variant"
& $gradle @arguments
exit $LASTEXITCODE
