param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot 'tmp/server-address'))
if (-not $output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
& javac -encoding UTF-8 -d $output (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/BotgramServerAddress.java') (Join-Path $PSScriptRoot 'tests/BotgramServerAddressSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Endpoint host-test compilation failed' }
& java -cp $output BotgramServerAddressSmoke
if ($LASTEXITCODE -ne 0) { throw 'Endpoint host tests failed' }
