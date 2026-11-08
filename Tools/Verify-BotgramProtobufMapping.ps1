param(
    [string]$DependencyRoot = 'D:\BotgramBuild',
    [string]$BackendRoot = '',
    [switch]$CompileMapperSources,
    [switch]$Blackhole
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $BackendRoot) { $BackendRoot = [IO.Path]::GetFullPath((Join-Path $workspace '../Botgram.Backend')) }
$classes = Join-Path $workspace 'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$required = @('org/telegram/tgnet/TLRPC.class')
if (-not $CompileMapperSources) {
    $required += @('org/telegram/botgram/BotgramProtobufRequestMapper.class', 'org/telegram/botgram/BotgramProtobufResponseMapper.class', 'org/telegram/botgram/BotgramProtobufIdentity.class', 'org/telegram/botgram/protocol/v1/BotgramRpc.class')
}
foreach ($file in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $classes $file))) { throw "Compile Android debug Java classes first: $file" }
}
$lite = Join-Path $DependencyRoot 'protobuf/downloads/protobuf-javalite-3.25.8.jar'
$json = Join-Path $DependencyRoot 'tools/json-20240303.jar'
$android = Join-Path $DependencyRoot 'android-sdk/platforms/android-36/android.jar'
foreach ($file in @($lite, $json, $android)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing pinned host dependency: $file" }
}
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp/protobuf-mapping/' + [Guid]::NewGuid().ToString('N'))))
if (-not $output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = @($classes, $lite, $json, $android) -join [IO.Path]::PathSeparator
$sources = @((Join-Path $BackendRoot 'tests/ProtobufAndroidSmoke.java'))
$sources += (Join-Path $workspace 'Tools/tests/BotgramE2eeMappingTest.java')
$sources += @(Get-ChildItem -LiteralPath (Join-Path $BackendRoot 'tests/host') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
if ($CompileMapperSources) {
    # Interim check only; default mode requires the actual Android-compiled mapper classes.
    $botgram = Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram'
    $sources += @('BotgramApi.java', 'BotgramPremiumState.java', 'BotgramProtobufWallpaper.java', 'BotgramProtobufPrivacy.java', 'BotgramProtobufIdentity.java', 'BotgramProtobufRequestMapper.java', 'BotgramProtobufResponseMapper.java') | ForEach-Object { Join-Path $botgram $_ }
    $generated = Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/protocol/v1/BotgramRpc.java'
    if (-not (Test-Path -LiteralPath $generated)) { throw "Generated protobuf Java source missing: $generated" }
    $sources += $generated
}
& javac --add-modules jdk.httpserver -encoding UTF-8 -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Protobuf mapping host-test compilation failed' }
$testArgs = @()
if ($Blackhole) { $testArgs += '--blackhole' }
# The Android jar is a stub runtime: background dispatch queues raise "Stub!" on stderr
# when TL deserialization touches FileLoader. The assertion result is the process exit code,
# so stderr is captured and reported without being treated as a test failure.
$hostStderr = Join-Path $output 'host-stderr.txt'
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & java --add-modules jdk.httpserver -cp ($output + [IO.Path]::PathSeparator + $classpath) ProtobufAndroidSmoke @testArgs 2> $hostStderr
    $hostExit = $LASTEXITCODE
    if ($hostExit -ne 0 -and (Test-Path -LiteralPath $hostStderr)) { Get-Content -LiteralPath $hostStderr | Write-Output }
    if ($hostExit -ne 0) { throw 'Protobuf mapping rejected an Android TL response or accepted an invalid input' }
    & java -cp ($output + [IO.Path]::PathSeparator + $classpath) BotgramE2eeMappingTest 2> $hostStderr
    $e2eeExit = $LASTEXITCODE
    if ($e2eeExit -ne 0 -and (Test-Path -LiteralPath $hostStderr)) { Get-Content -LiteralPath $hostStderr | Write-Output }
    if ($e2eeExit -ne 0) { throw 'Directional E2EE mapping or original TL wire regression failed' }
} finally { $ErrorActionPreference = $previousErrorAction }
if ($CompileMapperSources) { Write-Output 'Interim source-compiled mapper check only; rerun without -CompileMapperSources after Android Java compilation.' }
Write-Output 'Host-only verification: no APK/device execution; test substitutes are outside Android source sets.'
