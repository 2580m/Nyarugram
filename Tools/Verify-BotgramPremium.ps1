param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
if (-not ([IO.Path]::GetFullPath($DependencyRoot)).StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Dependencies and test output must remain on D:' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lite = Join-Path $DependencyRoot 'protobuf\downloads\protobuf-javalite-3.25.8.jar'
if (-not (Test-Path -LiteralPath $lite)) { throw "Missing pinned protobuf runtime: $lite" }
$output = Join-Path $DependencyRoot ('tmp\premium-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($output) | Out-Null
$sources = @(
    (Join-Path $workspace 'TMessagesProj\src\main\java\org\telegram\botgram\protocol\v1\BotgramRpc.java'),
    (Join-Path $workspace 'TMessagesProj\src\main\java\org\telegram\botgram\BotgramPremiumState.java'),
    (Join-Path $PSScriptRoot 'tests\BotgramPremiumSmoke.java')
)
& javac -encoding UTF-8 -cp $lite -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Premium test compilation failed' }
& java -cp ($output + [IO.Path]::PathSeparator + $lite) BotgramPremiumSmoke
if ($LASTEXITCODE -ne 0) { throw 'Premium presence/session-state test failed' }
