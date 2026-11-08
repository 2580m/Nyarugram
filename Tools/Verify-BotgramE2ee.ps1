param(
    [string]$DependencyRoot = 'D:\BotgramBuild'
)
$ErrorActionPreference = 'Stop'
$env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' -Djava.io.tmpdir=' + (Join-Path $DependencyRoot 'tmp')).Trim())
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Fail([string]$message) { throw "Botgram E2EE verification failed: $message" }
function Sha256([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-17' }
$javac = Join-Path $javaHome 'bin/javac.exe'
$java = Join-Path $javaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac)) { Fail "javac not found under $javaHome" }

$libsignal = Join-Path $DependencyRoot 'downloads/libsignal-client-0.86.5.jar'
$protobuf = Join-Path $DependencyRoot 'protobuf/downloads/protobuf-javalite-3.25.8.jar'
if (!(Test-Path -LiteralPath $libsignal)) { Fail "missing $libsignal" }
if ((Sha256 $libsignal) -ne 'd300c272dae155b21ae8988f51b6b493001cd2d83f5d2f9ae15e743c7ff971bd') { Fail 'libsignal-client SHA-256 mismatch' }
if (!(Test-Path -LiteralPath $protobuf)) { Fail "missing $protobuf" }
if ((Sha256 $protobuf) -ne '918bcc959cc0f5cb58798cec347a15d41cee9a1b8101c352954fd18d44e2e1c7') { Fail 'protobuf-javalite SHA-256 mismatch' }

$kotlin = Get-ChildItem -LiteralPath (Join-Path $DependencyRoot 'gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.0') -Recurse -Filter 'kotlin-stdlib-2.1.0.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$kotlin) { Fail 'kotlin-stdlib 2.1.0 is not present in the D: Gradle cache' }

$output = Join-Path $DependencyRoot ('e2ee-host/verify-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/protocol/v1/BotgramRpc.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeBase64.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeVault.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeVaultFile.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeAtomicFile.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeHistory.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeCrypto.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeUnilateral.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeCommit.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeJournal.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeSessionPolicy.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeSendPolicy.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeSessionGate.java'),
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/e2ee/BotgramE2eeEnvelopeCodec.java'),
    (Join-Path $workspace 'Tools/tests/BotgramE2eeCoreTest.java')
)
$classpath = "$libsignal;$($kotlin.FullName);$protobuf"
& $javac -classpath $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { Fail 'javac failed' }
& $java -classpath "$output;$classpath" org.telegram.botgram.e2ee.BotgramE2eeCoreTest
if ($LASTEXITCODE -ne 0) { Fail 'core regression failed' }
Write-Host "PASS: Botgram E2EE host core (PIN+device vault, PBKDF2, libsignal, commit boundaries, history, safety code, tamper/replay/identity replacement)"
