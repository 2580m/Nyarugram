param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp/auth-isolation/' + [Guid]::NewGuid().ToString('N'))))
if (-not $output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
& javac -encoding UTF-8 -d $output (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/BotgramSessionScope.java') (Join-Path $PSScriptRoot 'tests/BotgramSessionScopeSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Session scope host-test compilation failed' }
& java -cp $output BotgramSessionScopeSmoke
if ($LASTEXITCODE -ne 0) { throw 'Session scope host tests failed' }
