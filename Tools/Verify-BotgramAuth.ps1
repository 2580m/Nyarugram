param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $workspace 'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
if (!(Test-Path (Join-Path $classes 'org/telegram/tgnet/ConnectionsManager.class'))) { throw 'Compile Android Java classes first' }
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot 'tmp/auth-isolation'))
if (!$output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$android = Join-Path $DependencyRoot 'android-sdk/platforms/android-36/android.jar'
$classpath = @($classes, $android) -join [IO.Path]::PathSeparator
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'tests/auth-host') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& javac -encoding UTF-8 -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Auth host test compilation failed' }
& java -cp ($output + [IO.Path]::PathSeparator + $classpath) AuthIsolationSmoke
if ($LASTEXITCODE -ne 0) { throw 'Auth isolation regression failed' }
