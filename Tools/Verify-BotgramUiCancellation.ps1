param([string]$DependencyRoot = 'D:\BotgramBuild')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath((Join-Path $DependencyRoot ('tmp/ui-cancellation/' + [Guid]::NewGuid().ToString('N'))))
if (!$output.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Host test output must remain on D:' }
[void][IO.Directory]::CreateDirectory($output)
$source = [IO.File]::ReadAllText((Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java'))
function Get-JavaMethod([string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing actual method: $signature" }
    $opening = $source.IndexOf('{', $start)
    $depth = 1
    $end = $opening + 1
    while ($depth -gt 0 -and $end -lt $source.Length) {
        if ($source[$end] -eq '{') { $depth++ }
        if ($source[$end] -eq '}') { $depth-- }
        $end++
    }
    if ($depth -ne 0) { throw 'Unbalanced method braces' }
    return $source.Substring($start, $end - $start)
}
$methods = @(
    (Get-JavaMethod 'public static void runOnUIThread(Runnable runnable)'),
    (Get-JavaMethod 'public static void runOnUIThread(Runnable runnable, long delay)'),
    (Get-JavaMethod 'public static void cancelRunOnUIThread(Runnable runnable)')
)
$generated = "package org.telegram.messenger; public final class AndroidUtilities {`n" +
    'private static final org.telegram.botgram.BotgramSessionScope.Queue sessionUiTasks = new org.telegram.botgram.BotgramSessionScope.Queue();' +
    "`n" + ($methods -join "`n") + "`n}"
$encoding = [Text.UTF8Encoding]::new($false)
$utilities = Join-Path $output 'AndroidUtilities.java'
$clock = Join-Path $output 'SystemClock.java'
[IO.File]::WriteAllText($utilities, $generated, $encoding)
[IO.File]::WriteAllText($clock, 'package android.os; public final class SystemClock { public static long uptimeMillis() { return 0; } }', $encoding)
& javac -encoding UTF-8 -d $output $utilities $clock `
    (Join-Path $workspace 'TMessagesProj/src/main/java/org/telegram/botgram/BotgramSessionScope.java') `
    (Join-Path $PSScriptRoot 'tests/BotgramUiCancellationSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Actual UI cancellation method compilation failed' }
& java -cp $output org.telegram.messenger.BotgramUiCancellationSmoke
if ($LASTEXITCODE -ne 0) { throw 'UI cancellation regression failed' }
