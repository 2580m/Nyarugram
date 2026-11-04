param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $workspace 'TMessagesProj\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$android = Join-Path $DependencyRoot 'android-sdk\platforms\android-36\android.jar'
foreach ($required in @(
    (Join-Path $classes 'org\telegram\botgram\BotgramGiftPolicy.class'),
    (Join-Path $classes 'org\telegram\botgram\BotgramNativeGiftRequests.class'),
    $android
)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Compile Android Java classes and prepare the SDK first: $required" }
}
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp\gift-policy\' + [Guid]::NewGuid().ToString('N'))))
if (!$output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test outputs must remain on D:' }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = @($classes, $android) -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 -cp $classpath -d $output `
    (Join-Path $PSScriptRoot 'tests\BotgramNativeGiftRequestsSmoke.java') `
    (Join-Path $PSScriptRoot 'tests\BotgramGiftPolicySmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Gift host-test compilation failed' }
$runtimeClasspath = @($output, $classpath) -join [IO.Path]::PathSeparator
& java -cp $runtimeClasspath BotgramNativeGiftRequestsSmoke
if ($LASTEXITCODE -ne 0) { throw 'Native gift request isolation failed' }
& java -cp $runtimeClasspath BotgramGiftPolicySmoke
if ($LASTEXITCODE -ne 0) { throw 'Gift payment classification failed' }
