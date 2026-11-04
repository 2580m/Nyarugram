param([string]$DependencyRoot='D:\BotgramBuild')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($DependencyRoot)
if(-not $root.StartsWith('D:\',[StringComparison]::OrdinalIgnoreCase)){throw 'Outputs must remain on D:'}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=Join-Path $root ('tmp/wss-faults/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $run | Out-Null
$javaRoot='C:\Program Files\Java\jdk-17\bin'
$upstream=Join-Path $root 'gradle/caches/modules-2/files-2.1/com.neovisionaries/nv-websocket-client/2.14/1b66d37914b232cef45e56ec8c01907620de9e3a/nv-websocket-client-2.14.jar'
if((Get-FileHash -LiteralPath $upstream -Algorithm SHA256).Hash -ne 'EED0FB6F5EF5DB17D087039F1E829CFE827363B2863265258A96F0ED323313B7'){throw 'Upstream nv SHA256 mismatch'}
$filtered=Join-Path $run 'nv-filtered.jar'
[IO.File]::Copy($upstream,$filtered)
$archive=[IO.Compression.ZipFile]::Open($filtered,[IO.Compression.ZipArchiveMode]::Update)
try{$archive.GetEntry('com/neovisionaries/ws/client/WebSocketInputStream.class').Delete()}finally{$archive.Dispose()}
$classes=Join-Path $workspace 'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$protobuf=Join-Path $root 'protobuf/downloads/protobuf-javalite-3.25.8.jar'
$json=Join-Path $root 'tools/json-20240303.jar'
$classpath=@($run,$filtered,$protobuf,$json,$classes)-join [IO.Path]::PathSeparator
$production=Join-Path $workspace 'TMessagesProj/src/main/java'
$sources=@(
    (Join-Path $production 'com/neovisionaries/ws/client/WebSocketInputStream.java'),
    (Join-Path $production 'com/neovisionaries/ws/client/BotgramReceiveLimits.java'),
    (Join-Path $production 'org/telegram/botgram/BotgramEventSocket.java'),
    (Join-Path $production 'org/telegram/botgram/protocol/v1/BotgramRpc.java'),
    (Join-Path $PSScriptRoot 'tests/wss-fault-host/AndroidUtilities.java'),
    (Join-Path $PSScriptRoot 'tests/wss-fault-host/WssFaultHostTest.java'))
$pfx=Join-Path $run 'temporary-localhost.p12'
$env:BOTGRAM_WSS_TEST_PASSWORD=[Guid]::NewGuid().ToString('N')
try {
    & (Join-Path $javaRoot 'keytool.exe') -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 1 -dname CN=localhost -ext SAN=dns:localhost -storetype PKCS12 -keystore $pfx -storepass:env BOTGRAM_WSS_TEST_PASSWORD -keypass:env BOTGRAM_WSS_TEST_PASSWORD *> (Join-Path $run 'keytool.log')
    if($LASTEXITCODE -ne 0){throw "keytool failed; $run"}
    & (Join-Path $javaRoot 'javac.exe') --release 8 -encoding UTF-8 -sourcepath $run -cp $classpath -d $run @sources *> (Join-Path $run 'compile.log')
    if($LASTEXITCODE -ne 0){throw "Compilation failed; $run"}
    & (Join-Path $javaRoot 'java.exe') -Xmx128m -cp $classpath org.telegram.botgram.WssFaultHostTest $pfx *> (Join-Path $run 'execution.log')
    $result=$LASTEXITCODE
    Get-Content (Join-Path $run 'execution.log')
    if($result -ne 0){throw "WSS fault tests failed; $run"}
} finally {
    if(Test-Path -LiteralPath $pfx){Remove-Item -LiteralPath $pfx}
    Remove-Item Env:BOTGRAM_WSS_TEST_PASSWORD
    Write-Output "Evidence: $run; temporary private key deleted; no global trust changes"
}
