param([string]$Source = 'D:\BotgramBuild\downloads\telegram-zh-hans-export.xml')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$english = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal)
foreach ($relative in @('TMessagesProj/src/main/res/values/strings.xml', 'TMessagesProj_AppStandalone/src/main/res/values/strings.xml')) {
    $document = [xml][IO.File]::ReadAllText((Join-Path $workspace $relative))
    foreach ($entry in $document.resources.string) { $english[$entry.name] = $entry.InnerText }
}
$translation = [xml][IO.File]::ReadAllText($Source)
$output = New-Object System.Xml.XmlDocument
[void]$output.AppendChild($output.CreateXmlDeclaration('1.0', 'utf-8', $null))
[void]$output.AppendChild($output.CreateComment(' Simplified Chinese from https://translations.telegram.org/zh-hans/android/export ; see Botgram.Chinese.md. '))
$root = $output.CreateElement('resources')
[void]$output.AppendChild($root)
$seen = New-Object 'System.Collections.Generic.Dictionary[string,bool]' ([StringComparer]::Ordinal)
$skipped = @()
function Get-Placeholders([string]$value) {
    $matches = [regex]::Matches(($value -replace '%%', ''), '%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')
    $position = 0
    $tokens = foreach ($match in $matches) {
        $position++
        $index = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { "$position" }
        "$index`:$($match.Groups[2].Value)"
    }
    return (($tokens | Sort-Object -Unique) -join ',')
}
foreach ($entry in $translation.resources.string) {
    $name = $entry.name
    if (!$english.ContainsKey($name)) { continue }
    if ($seen.ContainsKey($name)) { throw "Duplicate translation: $name" }
    $seen[$name] = $true
    if ((Get-Placeholders $entry.InnerText) -cne (Get-Placeholders $english[$name])) {
        $skipped += $name
        continue
    }
    [void]$root.AppendChild($output.ImportNode($entry, $true))
}
$localTranslations = [ordered]@{
    BotgramSignIn = '登录 Botgram'
    BotgramSignUp = '创建账号'
    BotgramSignInInfo = '请输入用户名和密码以继续。'
    BotgramSignUpInfo = '请设置用户名、显示名称和密码。'
    BotgramServer = '服务器地址'
    BotgramDisplayName = '显示名称'
    BotgramCreateAccount = '创建账号'
    BotgramHaveAccount = '已有账号？登录'
    BotgramInvalidCredentials = '用户名或密码错误。'
    BotgramUsernameTaken = '该用户名已被使用。'
    BotgramAuthenticationFailed = '无法登录，请检查填写的信息后重试。'
    BotgramInvalidServer = '请输入 https://主机[:端口] 格式的服务器地址，不含路径。'
    BotgramConnectionFailed = '无法连接或保存登录状态，请重试。'
    BotgramGid = 'GID'
    BotgramGidCopied = '已复制 GID'
    BotgramChangeGid = '修改 GID'
    BotgramGeographicRegion = '地理区域'
    BotgramSelectRegion = '请选择 World 以外的地理区域。'
    BotgramGidInputInfo = '区域代码后填写八位数字，不可为 00000000。提交时会校验是否被占用。'
    BotgramGidInvalid = '请输入八位数字，且不可为 00000000。'
    BotgramGidTaken = '该 GID 已被使用，请更换号码。'
    BotgramGidHelp = 'GID 用于代替手机号码。请选择联合国 M49 地理区域和八位数字。修改 GID 后，账号和聊天记录仍然保留。'
    BotgramKeepGid = '保留 %1$s'
    BotgramLocatingRegion = '正在确认地理区域…若无法定位，将使用 World（+001）。'
    BotgramAccountInfo = 'GID、用户名与简介'
    BotgramSaveLogs = '保存日志'
    BotgramViewLogs = '查看日志'
    BotgramLogsSaved = '日志已保存到所选位置。'
    BotgramNoLogs = '暂无日志记录。'
    BotgramLogsPreviewInfo = '预览最多显示末尾 256 KB，使用“保存日志”可导出完整文件。'
}
foreach ($name in $localTranslations.Keys) {
    $entry = $output.CreateElement('string')
    $entry.SetAttribute('name', $name)
    $entry.InnerText = $localTranslations[$name]
    [void]$root.AppendChild($entry)
}
$destination = Join-Path $workspace 'TMessagesProj/src/main/res/values-zh/strings.xml'
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
$settings = New-Object System.Xml.XmlWriterSettings
$settings.Encoding = New-Object System.Text.UTF8Encoding($false)
$settings.Indent = $true
$writer = [Xml.XmlWriter]::Create($destination, $settings)
try { $output.Save($writer) } finally { $writer.Dispose() }
Write-Output "Imported $($root.ChildNodes.Count) Chinese strings. Incompatible placeholders use English fallback: $($skipped -join ', ')"
