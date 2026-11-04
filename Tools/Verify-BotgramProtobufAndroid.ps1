param(
    [string]$BuildRoot = 'D:\BotgramBuild\protobuf',
    [string]$SdkRoot = 'D:\BotgramBuild\android-sdk',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17',
    [Parameter(Mandatory)][string]$GeneratedJava,
    [Parameter(Mandatory)][string]$LiteJar
)
$ErrorActionPreference = 'Stop'
# Isolated toolchain check only: no APK integration or device execution.
$out = Join-Path $BuildRoot ('android-check-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $out 'classes'
$d8 = Join-Path $out 'd8'
$r8 = Join-Path $out 'r8'
New-Item -ItemType Directory -Force $classes,$d8,$r8 | Out-Null
$sources = @(Get-ChildItem -LiteralPath $GeneratedJava -Filter '*.java' -Recurse | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'Generated Java sources not found' }
& "$JavaHome\bin\javac.exe" --release 8 -encoding UTF-8 -cp $LiteJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$jar = Join-Path $out 'protocol.jar'
& "$JavaHome\bin\jar.exe" cf $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
$tool = Join-Path $SdkRoot 'build-tools\36.0.0\lib\d8.jar'
$android = Join-Path $SdkRoot 'platforms\android-36\android.jar'
& "$JavaHome\bin\java.exe" -cp $tool com.android.tools.r8.D8 --release --min-api 23 --lib $android --output $d8 $jar $LiteJar
if ($LASTEXITCODE -ne 0) { throw 'D8 failed' }
$rules = Join-Path $out 'keep.pro'
# Preserve every protocol type for this isolated compatibility check.
[IO.File]::WriteAllText($rules, "-keep class org.telegram.botgram.protocol.v1.** { *; }`n-dontobfuscate`n")
& "$JavaHome\bin\java.exe" -cp $tool com.android.tools.r8.R8 --release --min-api 23 --lib $android --pg-conf $rules --output $r8 $jar $LiteJar
if ($LASTEXITCODE -ne 0) { throw 'R8 failed' }
Get-Item (Join-Path $d8 'classes.dex'),(Join-Path $r8 'classes.dex') | Select-Object FullName,Length
Write-Output 'PASS: isolated Java 8 + D8/R8 min-api 23; not an APK/device test.'
