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
