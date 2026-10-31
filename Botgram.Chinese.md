# Built-in Simplified Chinese

The APK includes Simplified Chinese in `TMessagesProj/src/main/res/values-zh/strings.xml`.
Select **Settings → Language → 简体中文**. A fresh install follows a Simplified Chinese
system locale; an existing explicit language selection is preserved. No language-pack
download or backend `langpack` endpoint is required.

Translation source: Telegram translation platform, Chinese (Simplified), Android:
https://translations.telegram.org/zh-hans/android/export
Retrieved 2026-11-01. Credits remain with the Telegram translation contributors.
Export SHA-256: `81A9F9560E6F9604FB2C7C25F96CDB9FA54ED0570EB996856B3C5B67B12C3B56`.

The original export is cached on D: at
`D:\BotgramBuild\downloads\telegram-zh-hans-export.xml`.
Run `Tools/Import-BotgramChinese.ps1` to regenerate the bundled resource from that file.
The importer keeps only resource names used by this app and checks numbered format
arguments against the English strings. Missing or incompatible translations fall back
to the existing English resources. The app's existing Gradle plugin packages the XML
as `assets/localization_zh.bin`; Android locale resource filters do not remove it.

The current and legacy Settings pages omit Money, Telegram Premium, Telegram Business,
and the Help section. Other Settings entries retain their existing behavior.
