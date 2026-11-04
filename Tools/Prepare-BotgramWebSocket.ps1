param(
    [string]$DependencyRoot = 'D:\BotgramBuild',
    [string]$UpstreamJar,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($DependencyRoot)
if (-not $root.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'WebSocket dependencies and outputs must remain on D:' }
Get-ChildItem -LiteralPath $root | Out-Null
$output = Join-Path $root 'websocket'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$upstreamSha = 'EED0FB6F5EF5DB17D087039F1E829CFE827363B2863265258A96F0ED323313B7'
$sourceSha = '93AC908BCA2638C8ACDCFA1BBD145ACE5FA58D3D266D3762300D6ED6F1DB5D01'
$source = Join-Path $PSScriptRoot 'vendor/nv-websocket-client-2.14/WebSocketInputStream.java'
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $sourceSha) { throw 'Vendor WebSocketInputStream.java SHA-256 mismatch' }
if (-not $UpstreamJar) {
    $cached = Join-Path $root 'gradle/caches/modules-2/files-2.1/com.neovisionaries/nv-websocket-client/2.14/1b66d37914b232cef45e56ec8c01907620de9e3a/nv-websocket-client-2.14.jar'
    if (Test-Path -LiteralPath $cached) { $UpstreamJar = $cached }
    else {
        $UpstreamJar = Join-Path $output 'nv-websocket-client-2.14-upstream.jar'
        if (-not (Test-Path -LiteralPath $UpstreamJar)) {
            Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/neovisionaries/nv-websocket-client/2.14/nv-websocket-client-2.14.jar' -OutFile $UpstreamJar
        }
    }
}
$UpstreamJar = [IO.Path]::GetFullPath($UpstreamJar)
if ((Get-FileHash -LiteralPath $UpstreamJar -Algorithm SHA256).Hash -ne $upstreamSha) { throw 'Upstream nv-websocket-client 2.14 SHA-256 mismatch' }
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$jar = Join-Path (Split-Path $javac) 'jar.exe'
$classes = Join-Path $output ('classes-' + [Guid]::NewGuid().ToString('N'))
Get-ChildItem -LiteralPath $output | Out-Null
New-Item -ItemType Directory -Path $classes | Out-Null
& $javac --release 8 -encoding UTF-8 -cp $UpstreamJar -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'WebSocket override compilation failed' }
$className = 'com/neovisionaries/ws/client/WebSocketInputStream.class'
$compiled = @(Get-ChildItem -LiteralPath $classes -Recurse -File)
if ($compiled.Count -ne 1 -or $compiled[0].FullName -ne (Join-Path $classes $className)) { throw 'Expected exactly one override class' }
$compiled[0].LastWriteTimeUtc = [DateTime]::Parse('2020-01-01T00:00:00Z').ToUniversalTime()
$patched = Join-Path $output 'nv-websocket-client-2.14-botgram.jar'
if ($UpstreamJar -eq $patched) { throw 'The upstream input must not be the output jar' }
Copy-Item -LiteralPath $UpstreamJar -Destination $patched -Force
& $jar uf $patched -C $classes $className
if ($LASTEXITCODE -ne 0) { throw 'WebSocket override jar update failed' }
[PSCustomObject]@{
    JarPath = $patched
    Sha256 = (Get-FileHash -LiteralPath $patched -Algorithm SHA256).Hash
    UpstreamJar = $UpstreamJar
    UpstreamSha256 = $upstreamSha
    VendorSourceSha256 = $sourceSha
}
