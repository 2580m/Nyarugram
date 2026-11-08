param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$WorkDirectory = 'D:\BotgramBuild\incoming-call-policy'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Java\jdk-17' }
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }
New-Item -ItemType Directory -Path $WorkDirectory -Force | Out-Null
$policy = Join-Path $root 'TMessagesProj\src\main\java\org\telegram\messenger\voip\IncomingCallPolicy.java'
$plan = Join-Path $root 'TMessagesProj\src\main\java\org\telegram\messenger\voip\IncomingCallNotificationPlan.java'
$test = Join-Path $PSScriptRoot 'tests\IncomingCallPolicySmoke.java'
& $javac -encoding UTF-8 -d $WorkDirectory $policy $plan $test
if ($LASTEXITCODE -ne 0) { throw 'Incoming call host compilation failed' }
& $java -cp $WorkDirectory org.telegram.messenger.voip.IncomingCallPolicySmoke
if ($LASTEXITCODE -ne 0) { throw 'Incoming call policy tests failed' }

# Compile the production pending-call ownership methods with host-only platform stand-ins.
$servicePath = Join-Path $root 'TMessagesProj\src\main\java\org\telegram\messenger\voip\VoIPService.java'
$source = Get-Content -LiteralPath $servicePath -Raw
$methods = [regex]::Match($source, '(?s)public static TL_phone.PhoneCall callIShouldHavePutIntoIntent;.*?public static void clearPendingIncomingCall\(int account, long callId\).*?\r?\n    }')
if (-not $methods.Success) { throw 'Production pending-call ownership methods were not found' }
$pendingDirectory = Join-Path $WorkDirectory 'pending-release'
New-Item -ItemType Directory -Path $pendingDirectory -Force | Out-Null
$hostService = Join-Path $pendingDirectory 'VoIPService.java'
[IO.File]::WriteAllText($hostService,
    'package org.telegram.messenger.voip; public final class VoIPService {' + $methods.Value + '}',
    [Text.UTF8Encoding]::new($false))
$pendingTest = Join-Path $PSScriptRoot 'tests\PendingIncomingCallSmoke.java'
& $javac -encoding UTF-8 -d $pendingDirectory $hostService $pendingTest
if ($LASTEXITCODE -ne 0) { throw 'Pending incoming call host compilation failed' }
& $java -cp $pendingDirectory org.telegram.messenger.voip.PendingIncomingCallSmoke
if ($LASTEXITCODE -ne 0) { throw 'Pending incoming call release tests failed' }
