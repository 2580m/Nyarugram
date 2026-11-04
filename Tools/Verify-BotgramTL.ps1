param([string]$DependencyRoot = 'D:\BotgramBuild', [string]$FixturePath = '', [string]$BackendRoot = '')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $BackendRoot) { $BackendRoot = [IO.Path]::GetFullPath((Join-Path $workspace '../Botgram.Backend')) }
$classes = Join-Path $workspace 'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'org/telegram/botgram/BotgramTransport.class'))) { throw 'Compile Android Java classes first' }
$json = Join-Path $DependencyRoot 'tools/json-20240303.jar'
$android = Join-Path $DependencyRoot 'android-sdk/platforms/android-36/android.jar'
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp/tl-codec/' + [Guid]::NewGuid().ToString('N'))))
if (-not $output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$lite = Join-Path $DependencyRoot 'protobuf/downloads/protobuf-javalite-3.25.8.jar'
$classpath = @($json, $classes, $android, $lite) -join [IO.Path]::PathSeparator
$hostSources = @(Get-ChildItem -LiteralPath (Join-Path $BackendRoot 'tests/host') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& javac -encoding UTF-8 -cp $classpath -d $output (Join-Path $BackendRoot 'tests/TLCodecSmoke.java') @hostSources
if ($LASTEXITCODE -ne 0) { throw 'TL host-test compilation failed' }
if (-not $FixturePath) { $FixturePath = Join-Path $BackendRoot 'obj/tl-fixtures.json' }
& java -cp ($output + [IO.Path]::PathSeparator + $classpath) TLCodecSmoke $FixturePath
if ($LASTEXITCODE -ne 0) { throw 'Original TL codec rejected a response' }
