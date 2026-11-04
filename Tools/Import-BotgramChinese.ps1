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
    BotgramAuthenticatorIntro = '使用验证器应用保护账号。在新设备上登录时，需要输入验证器生成的验证码。'
    BotgramAuthenticatorManage = '管理验证器'
    BotgramAuthenticatorEnrollInfo = '使用验证器扫描下方二维码，或复制密钥手动添加。输入六位验证码，完成绑定。'
    BotgramAuthenticatorSixDigitCode = '六位验证器验证码'
    BotgramAuthenticatorQr = '验证器绑定二维码'
    BotgramAuthenticatorCopyKey = '复制密钥'
    BotgramRecoveryCodesShow = '恢复码'
    BotgramRecoveryCodesSaveTxt = '保存 TXT'
    BotgramRecoveryCodesSaved = '恢复码已保存。'
    BotgramRecoveryCodesSaveFailed = '文件保存失败，请重试并选择可写入的位置。'
    BotgramRecoveryCodesRemaining = '剩余未使用的恢复码 — %1$d'
    BotgramAuthenticator = '验证器应用'
    BotgramAuthenticatorCode = '验证器验证码或恢复码'
    BotgramAuthenticatorInfo = '支持 Google Authenticator、Microsoft Authenticator、Aegis 等 TOTP 验证器。扫描二维码或手动输入密钥，再填写六位验证码确认绑定。离开前请妥善保存恢复码。'
    BotgramAuthenticatorEnabled = '验证器保护已开启。新登录需要账号密码，以及验证器验证码或一次性恢复码。'
    BotgramAuthenticatorEnable = '绑定验证器'
    BotgramAuthenticatorConfirm = '确认验证码并绑定'
    BotgramAuthenticatorDisable = '关闭验证器保护'
    BotgramAuthenticatorDisableInfo = '请输入新的验证器验证码或未使用过的恢复码，以关闭两步验证。'
    BotgramAuthenticatorInvalid = '验证码错误、已过期或已使用。请检查设备时间，或使用未使用过的恢复码。'
    BotgramAuthenticatorWait = '尝试次数过多，请一分钟后重试。'
    BotgramAuthenticatorFailed = '验证器操作未完成，请检查连接后重试。'
    BotgramRecoveryCodesInfo = '下方是 10 个八位数字恢复码。验证器不可用时，在登录页面输入其中一个完整恢复码。每个恢复码使用后即销毁。离开此页面前，请复制或保存到安全位置。'
    BotgramRecoveryCodesRegenerate = '生成新的恢复码'
    BotgramRecoveryCodesReplaceInfo = '此操作会使旧恢复码全部失效。请输入新的验证器验证码或未使用过的恢复码以继续。'
    BotgramRecoveryEmailUnavailable = '服务器尚未配置邮件找回服务。可跳过此步骤，设置不含邮件找回的两步验证密码。'
    BotgramSignIn = '登录 Botgram'
    BotgramSignUp = '创建账号'
    BotgramSignInInfo = '请输入用户名和密码以继续。'
    BotgramSignUpInfo = '请设置用户名、显示名称和密码。'
    BotgramServer = '服务器地址'
    BotgramPort = '端口'
    BotgramInvalidPort = '请输入 1 到 65535 之间的端口号，或留空使用 8521。'
    BotgramDisplayName = '显示名称'
    BotgramCreateAccount = '创建账号'
    BotgramHaveAccount = '已有账号？登录'
    BotgramInvalidCredentials = '用户名或密码错误。'
    BotgramUsernameTaken = '该用户名已被使用。'
    BotgramAuthenticationFailed = '无法登录，请检查填写的信息后重试。'
    BotgramInvalidServer = '请输入不含路径的 HTTP 或 HTTPS 主机地址，端口请单独填写。'
    BotgramConnectionFailed = '无法连接或保存登录状态，请重试。'
    BotgramContactGidInfo = '请输入联系人的 Gram ID，例如 +030 12345678。'
    BotgramContactGidInvalid = '请输入有效的三位 M49 区域代码和八位数字，后八位不可为 00000000。'
    BotgramContactGidNotFound = '未找到使用该 Gram ID 的账号。'
    BotgramGid = 'Gram ID'
    BotgramGidCopied = '已复制 Gram ID'
    BotgramChangeGid = '修改 Gram ID'
    BotgramGeographicRegion = '地理区域'
    BotgramSelectRegion = '请选择 World 以外的地理区域。'
    BotgramGidInputInfo = '区域代码后填写八位数字，不可为 00000000。提交时会校验是否被占用。'
    BotgramGidInvalid = '请输入八位数字，且不可为 00000000。'
    BotgramGidTaken = '该 Gram ID 已被使用，请更换号码。'
    BotgramGidHelp = 'Gram ID 用于代替手机号码。请选择联合国 M49 地理区域和八位数字。修改 Gram ID 后，账号和聊天记录仍然保留。'
    BotgramKeepGid = '保留 %1$s'
    BotgramLocatingRegion = '正在确认地理区域…若无法定位，将使用 World（+001）。'
    BotgramAccountInfo = 'Gram ID、用户名与简介'
    TelegramPremium = 'Nyarugram Premium'
    BotgramPremiumInfo = '当前账号可免费终身使用，开通状态由 Nyarugram 服务器确认。当前可用权益如上所示。'
    BotgramPremiumSubtitle = '专属会员标识与更多应用图标，免费终身使用。'
    BotgramPremiumLifetime = '终身'
    BotgramPremiumLifetimePrice = '0$/终身'
    BotgramPremiumSubscribeLifetime = '以终身 0.00$ 的价格订阅'
    BotgramPremiumBadgeInfo = '开通后，账号会在支持的个人资料和聊天界面中显示 Premium 会员标识。'
    BotgramPremiumIconsInfo = '在设置 → 聊天设置中，选择 Nyarugram 内置的更多应用图标。需要 Android 系统版本及桌面启动器支持更换应用图标。'
    BotgramPremiumActivate = '免费开通'
    BotgramPremiumActive = '已开通 Nyarugram Premium'
    BotgramPremiumFailed = '无法确认 Premium 状态，请重试。没有发生任何付款。'
    BotgramNoGifts = '暂无可用的礼物'
    BotgramWallpaperUnsupported = '目前仅支持纯色和渐变壁纸，暂不支持此壁纸选项。'
    BotgramWallpaperFailed = '壁纸未能保存，已保留原壁纸，请重试。'
    BotgramCallOverlayTitle = '在其他应用上层显示来电'
    BotgramCallOverlayMessage = '为了在 Nyarugram 处于后台或屏幕锁定时直接把来电界面显示在最前面，请允许 Nyarugram 显示在其他应用上层。'
    BotgramSaveLogs = '保存日志'
    BotgramViewLogs = '查看日志'
    BotgramLogsSaved = '日志已保存到所选位置。'
    BotgramNoLogs = '暂无日志记录。'
    BotgramLogsPreviewInfo = '预览最多显示末尾 256 KB，使用“保存日志”可导出完整文件。'
}
foreach ($name in $localTranslations.Keys) {
    foreach ($existing in @($root.SelectNodes("string[@name='$name']"))) {
        [void]$root.RemoveChild($existing)
    }
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
