param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
if (-not ([IO.Path]::GetFullPath($DependencyRoot)).StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Dependencies and test output must remain on D:' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $workspace 'TMessagesProj\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$lite = Join-Path $DependencyRoot 'protobuf\downloads\protobuf-javalite-3.25.8.jar'
$android = Join-Path $DependencyRoot 'android-sdk\platforms\android-36\android.jar'
$json = Join-Path $DependencyRoot 'tools\json-20240303.jar'
foreach ($file in @((Join-Path $classes 'org\telegram\botgram\BotgramProtobufWallpaper.class'), $lite, $android, $json)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Required compiled class/dependency missing: $file" }
}
$output = Join-Path $DependencyRoot ('tmp\wallpaper-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($output) | Out-Null
$classpath = @($classes, $lite, $android, $json) -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 -cp $classpath -d $output (Join-Path $PSScriptRoot 'tests\BotgramWallpaperSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Wallpaper test compilation failed' }
& java -cp ($output + [IO.Path]::PathSeparator + $classpath) BotgramWallpaperSmoke
if ($LASTEXITCODE -ne 0) { throw 'Wallpaper mapping/TL test failed' }
