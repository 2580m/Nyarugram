param(
    [string]$DependencyRoot = 'D:\BotgramBuild',
    [string]$UpstreamJar,
    [string]$FilteredJar,
    [string]$AndroidClasses,
    [string]$ProtobufJar,
    [string]$JsonJar
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = [IO.Path]::GetFullPath($DependencyRoot)
if (-not $root.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
Get-ChildItem -LiteralPath $root | Out-Null
$output = Join-Path $root ('tmp/event-socket/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $output | Out-Null
if (-not $UpstreamJar) {
    $UpstreamJar = Join-Path $root 'gradle/caches/modules-2/files-2.1/com.neovisionaries/nv-websocket-client/2.14/1b66d37914b232cef45e56ec8c01907620de9e3a/nv-websocket-client-2.14.jar'
}
if ((Get-FileHash -LiteralPath $UpstreamJar -Algorithm SHA256).Hash -ne 'EED0FB6F5EF5DB17D087039F1E829CFE827363B2863265258A96F0ED323313B7') {
    throw 'Upstream nv-websocket-client 2.14 SHA-256 mismatch'
}
if (-not $FilteredJar) {
    # Mirror the Gradle exclusion for a standalone host test without Android tooling.
    $FilteredJar = Join-Path $output 'nv-websocket-client-2.14-receive-filtered.jar'
    [IO.File]::Copy($UpstreamJar, $FilteredJar)
    $archive = [IO.Compression.ZipFile]::Open($FilteredJar, [IO.Compression.ZipArchiveMode]::Update)
    try { $archive.GetEntry('com/neovisionaries/ws/client/WebSocketInputStream.class').Delete() }
    finally { $archive.Dispose() }
}
$source = Join-Path $workspace 'TMessagesProj/src/main/java/com/neovisionaries/ws/client'
& javac --release 8 -encoding UTF-8 -cp $FilteredJar -d $output (Join-Path $source 'WebSocketInputStream.java') (Join-Path $source 'BotgramReceiveLimits.java') (Join-Path $PSScriptRoot 'tests/BotgramWebSocketSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Event socket host-test compilation failed' }
& java -Xmx128m -cp "$output;$FilteredJar" com.neovisionaries.ws.client.BotgramWebSocketSmoke $UpstreamJar $FilteredJar
if ($LASTEXITCODE -ne 0) { throw 'Event socket host tests failed' }

if (-not $AndroidClasses) {
    $AndroidClasses = Join-Path $workspace 'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
}
if (-not $ProtobufJar) { $ProtobufJar = Join-Path $root 'protobuf/downloads/protobuf-javalite-3.25.8.jar' }
if (-not $JsonJar) { $JsonJar = Join-Path $root 'tools/json-20240303.jar' }
foreach ($required in @($ProtobufJar, $JsonJar,
        (Join-Path $AndroidClasses 'org/telegram/botgram/BotgramSession.class'),
        (Join-Path $AndroidClasses 'org/telegram/botgram/BotgramApi.class'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing host dependency: $required; compile Android Java classes first when required" }
}
$classpath = @($output, $FilteredJar, $ProtobufJar, $JsonJar, $AndroidClasses) -join [IO.Path]::PathSeparator
$hostSources = Join-Path $PSScriptRoot 'tests/event-socket-host'
$production = Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram'
# Explicit sources and an empty source path keep all host substitutes outside Android builds.
& javac --release 8 -encoding UTF-8 -sourcepath $output -cp $classpath -d $output `
    (Join-Path $hostSources 'AndroidUtilities.java') (Join-Path $hostSources 'FileLog.java') (Join-Path $hostSources 'EventSocketHostTest.java') `
    (Join-Path $production 'BotgramServerAddress.java') (Join-Path $production 'BotgramEventSocket.java') (Join-Path $production 'protocol/v1/BotgramRpc.java')
if ($LASTEXITCODE -ne 0) { throw 'Event socket listener host-test compilation failed' }
& java -Xmx128m -cp $classpath org.telegram.botgram.EventSocketHostTest
if ($LASTEXITCODE -ne 0) { throw 'Event socket listener host tests failed' }
Write-Output "Host test output: $output"
