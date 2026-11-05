param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $workspace 'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
if (!(Test-Path (Join-Path $classes 'org/telegram/botgram/BotgramMissedCallState.class'))) { throw 'Build Android debug classes first' }
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp/missed-calls/' + [Guid]::NewGuid().ToString('N'))))
if (!$output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = @($classes, (Join-Path $DependencyRoot 'protobuf/downloads/protobuf-javalite-3.25.8.jar'),
    (Join-Path $DependencyRoot 'android-sdk/platforms/android-36/android.jar'), (Join-Path $DependencyRoot 'tools/json-20240303.jar')) -join ';'
& javac -encoding UTF-8 -cp $classpath -d $output (Join-Path $PSScriptRoot 'tests/BotgramMissedCallSmoke.java')
if ($LASTEXITCODE) { throw 'Missed-call host test compilation failed' }
& java -cp ($output+';'+$classpath) org.telegram.botgram.BotgramMissedCallSmoke
if ($LASTEXITCODE) { throw 'Missed-call host test failed' }
