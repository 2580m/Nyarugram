# 原 Telegram 页面接入说明

## 2026-11-05 验证器界面与恢复码修订

13.1.4 / 72409 的两步验证入口直接使用原 `TwoStepVerificationSetupActivity.TYPE_INTRO`，保留原布局、`tsv_setup_intro` 锁动画和底部按钮。设置、搜索和深链接均进入此介绍页，点击后单独打开二维码、可复制密钥及验证码输入页面。已绑定账号进入验证器管理。

新恢复码为 10 个互不重复的八位数字字符串，保留前导零。登录和管理接口事务性消费对应摘要，同一个码最多成功使用一次。新摘要使用由受保护 TOTP 密钥派生的 HMAC-SHA256 校验，兼容既有 SHA-256 格式恢复码。绑定确认与重新生成后使用原生弹窗展示，提供复制和通过系统文件选择器保存 UTF-8 TXT。离开页面后清理客户端内存中的恢复码。

本次完成后端 HTTP 专项 182 条、PostgreSQL 292 条回归以及 APK 编译与签名检查。真机扫码、视觉布局、复制和文件选择器导出尚未复测。更完整结果记录在 `artifacts/build-info.json` 的 `totpUiRecoveryVerification`，其他隐私功能缺口沿用下一节的说明。

## 2026-11-05 隐私与验证器交付

13.1.3 / 72399 的 standalone Debug APK 已完成编译、v1/v2 签名及包信息检查。隐私设置中的两步验证、搜索及 2sv 深链接使用 Telegram 原生组件构成的 TOTP 页面，支持二维码或密钥绑定、验证码确认、登录校验、关闭和重置一次性恢复码。邮件找回服务尚未配置。自动删除页面加入 156×156、90 帧的原 Hello 鸭子离线素材，来源和格式转换记录见 `Tools/assets/auto-delete-hello.json`。

隐私规则、全局设置、黑名单、账号与默认消息 TTL 参数、会话查询与撤销、联系人删除、网站授权空列表、付款信息清理及通行密钥空列表已接入 typed RPC。黑名单、非联系人 Premium 发信限制、Gram ID 可见性与发现、群邀请及 REST 通话权限检查已覆盖 LegacyOnly 和 PostgreSQL 路径。

尚未完成自动删除消息执行、现有聊天定时器应用、账号闲置清理、通行密钥注册与登录、Passport 安全值、付费消息，以及全局归档和隐藏已读等设置的完整行为联动。TTL 目前只有参数读写，不能视为已生效的删除功能。历史会话缺少设备和创建时间时返回空值。尚未执行真机安装、验证器扫码和页面流畅度复测。

实际结果保存在 `artifacts/build-info.json` 的 `privacySecurityVerification`。手机测试后端 18524 已停机备份并切换到本次 Release 发布物，既有账号、聊天、消息、TL 身份及有效会话已核对保留。

保留原 `LaunchActivity`、`MainTabsActivity`、`DialogsActivity`、`ChatActivity`、消息气泡、群聊创建及资料页面。此前的独立 Botgram Activity、聊天界面、消息缓存和 WebRTC 界面已移除。业务兼容工作集中在后端，而不是另建客户端聊天模型。

## 协议边界

原页面继续消费 TL 类。绑定账号的 RPC 通过 `BotgramProtobufRequestMapper` / `BotgramProtobufResponseMapper` 显式映射到版本化 typed Protobuf；旧会话可保留原 TL JSON pin，但收到旧入口关闭错误不会自动重试写入。P7 联调应新登录，不恢复原生网络回退。认证、能力协商、Gram ID 和 Premium 的 REST 仍使用 JSON；P7 不是禁用所有 JSON。

采用经过 Bearer 认证的 HTTP(S) TL 对象 RPC，**没有实现 MTProto 加密、数据中心和握手协议**，因此未修改的官方 Telegram 客户端不能直接连接。客户端仍需以下接入改动：

- `LoginActivity`：用户名／密码认证，登录成功进入原 `onAuthSuccess` 和页面导航。
- `BotgramLoginView`：作为原 `LoginActivity` 内的 `SlideView` 提供用户名／密码认证，复用原登录页的布局、继续按钮、加载动画、描边输入框及错误弹窗；注册时显示名称输入框。
- `BotgramSession`：按 Telegram 账号槽位隔离令牌，并用 Android Keystore AES-GCM 加密保存。
- `BotgramTransport`：会话固定协议、typed RPC 映射、HTTP 请求、取消绑定、错误回调、前台差分轮询，以及独立的壁纸 revision 同步。
- `ConnectionsManager`：绑定自建后端的账号走上述传输；未绑定账号保留原行为。绑定账号即使会话失效也不回落到 Telegram 传输。
- `ApplicationLoader`：重启后恢复绑定账号的差分轮询。收到的响应仍由原 MessagesController／MessagesStorage 处理。

HTTP 绑定账号不向原生 MTProto 层恢复用户 ID，也不响应原生层缺少 MTProto 密钥触发的登出通知；账号仅因主动登出或后端令牌失效退出。清除后台后启动仍使用本地账号配置和 Keystore 加密保存的有效 Bearer 令牌，不需要再次认证。

HTTP 绑定账号在登录和启动时关闭手机通讯录同步，不创建 Android 通讯录同步账号、不自动读取或写入系统通讯录，也不因缺少同步权限显示提醒或联系人标签感叹号。应用内的联系人列表仍通过后端加载。

Android Java 编译后运行 `Tools/Verify-BotgramAuth.ps1`，验证真实 `ConnectionsManager` 原生登出回调对 HTTP 绑定账号、排队后的绑定变化及未绑定原生账号的隔离行为。此宿主测试不代替真机清除后台、重开及登录界面操作验证。

## 服务端当前支持范围

| 原 App TL API | 状态 |
|---|---|
| 用户资料、按完整用户名查找、解析用户名 | 已实现 |
| 按 GID 查询和添加联系人、私有备注姓名、联系人列表 | 已实现；GID 通过 TL phone 兼容字段传输，不写入系统电话簿 |
| 会话列表、会话详情、历史记录分页／锚点 | 已实现 |
| 单聊文字发送、random_id 去重、已读、同会话回复、常见富文本实体、静音发送 | 已实现 |
| 收藏夹中的基础自发文字与历史 | 已实现；高级收藏夹分类／搜索接口尚未实现 |
| 创建基础群聊、群资料、群成员读取、群文字 | 已实现 |
| updates.getState／getDifference 与差分切片 | 已实现，pts 从 1 开始，避免首条消息被初始化跳过 |
| 附件、语音消息、跨会话引用、定时消息、自定义表情实体、编辑撤回、频道、机器人等 | 尚未实现；返回明确的 501 兼容错误 |
| 原生 phone.*／群通话 API | 尚未实现；原通话页面未替换，也没有接入旧的 WebRTC REST 接口 |

保留页面不等于所有 Telegram 功能已有后端实现。当前重点为原页面的基础文字聊天兼容。服务端对尚不支持的请求返回 `BOTGRAM_RPC_UNSUPPORTED:<方法>`，不伪造操作成功。独立 WebRTC REST 信令仍留在后端供后续工作使用，不能据此宣称原 Telegram 语音通话已经适配。

## 运行与构建

新登录表单的服务器地址与端口初始留空，服务器地址由用户填写。省略协议时使用 HTTPS；端口留空且地址本身未带端口时使用 8521，地址自带端口时使用该端口。公网 RPC 与 WSS 共用 TLS 8521，仅调试构建允许显式 HTTP 本地开发地址，非调试构建拒绝 HTTP。登录成功后，使用原搜索页面按完整用户名找到其他用户，打开原聊天页面。

前台 WSS 通过 Authorization Bearer 连接 `/api/ws`，仅将事件作为唤醒原 `getDifference` 的提示，事件游标不当作 Telegram pts。后台或切换账号时关闭旧连接，断线采用 1–30 秒退避重连；原差分轮询保留。HTTPS/WSS 均使用系统证书信任和主机名验证，不接受自签名绕过，不跟随携带凭据的重定向。WSS 握手 401 使对应会话退出；TLS 失败不降级 HTTP。尚未实现后台推送。

登录和注册表单把服务器地址与右侧可选端口分开输入。粘贴带端口的完整 HTTP(S) 地址时自动拆分，保存会话使用完整地址，已有账号从已保存的连接配置恢复。升级后已保存的旧 5080 端口迁移至 8521，其他自定义端口保留。登录/注册切换采用淡出、轻微滑动和淡入动画，保留表单内容。`Tools/Verify-BotgramServerAddress.ps1` 验证空端口、地址自带端口、手动指定端口、端口边界、IPv6 与旧地址迁移。

所有新下载的构建依赖放在 **D:\BotgramBuild**：

- `downloads`：下载归档和官方校验文件。
- `tools/gradle-8.13`、`gradle`：Gradle 程序与缓存。
- `android-sdk`：新增 NDK、CMake、构建工具；现有平台和工具通过目录联接读取。
- `native`：仓库固定版本源码依赖，工作区子模块位置通过目录联接读取。
- `nuget`、`dotnet`、`tmp`：.NET 包、CLI 状态和构建临时目录。

```powershell
./Tools/Prepare-BotgramBuild.ps1
./Tools/Build-Botgram.ps1
```

默认构建目标为 `:TMessagesProj_AppStandalone:assembleAfatDebug`，输出文件名为 `Nyarugram-debug.apk`，交付路径为 `artifacts/android/Nyarugram-debug.apk`。运行 `./Tools/Build-Botgram.ps1 -Variant Standalone` 构建非调试、R8 混淆及资源压缩版本，原始输出为 `TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk`，交付路径为 `artifacts/android/Nyarugram-standalone.apk`。两者安装后的显示名称均为 `Nyarugram`（所有系统语言一致），沿用仓库的开发签名配置，不作为私有生产签名的正式发布包。后端集成测试：

```powershell
cd ../Botgram.Backend
dotnet build
node tests/smoke.mjs
node tests/tl-schema.mjs
```

Android Java 编译完成后可运行 `./Tools/Verify-BotgramTL.ps1`，使用刚编译的原 TL 类对测试响应做二进制往返检查。宿主机环境替身仅提供 Android 文本工具及测试环境标记，不会打包到 App。

## 历史交付验证结果（不适用于下述未构建的新改动）

- `assembleAfatDebug` 完整通过，包含 arm64-v8a、armeabi-v7a、x86、x86_64；APK 位于 `artifacts/android/Nyarugram-debug.apk`（约 115 MB），安装显示名称已通过 APK 元数据检查为 `Nyarugram`。
- `assembleAfatStandalone` 完整通过；非调试 APK 约 68 MiB，确认未标记 debuggable，R8 后仍包含默认 HTTPS 主机、HTTP 拒绝逻辑及 WSS 路径。
- 两个 APK v1／v2 签名校验通过；版本 `13.1.2`（72389），包名 `me.nyarugram.messenger`，最低 Android API 23、目标 API 36，使用仓库开发签名。
- 后端 Release 发布成功，交付包为 `artifacts/Botgram.Backend.zip`。
- 后端集成回归及 56 个响应类型与源代码匹配检查通过；本次宿主检查完成 29 个基础响应、2 个 GID 响应和 26 个联系人响应的原 TL 二进制往返，以及 34 项地址检查和 6 项账号隔离断言。
- 公网 `https://nyarugram.moe.autos:8521` 的受信任证书、TLS 1.2 / 1.3、隔离账号注册登录和双向文字、Bearer WSS、断线重放及退出失效检查通过；TLS 1.0 / 1.1 被拒绝。正式数据目录不含测试账号。
- 未执行手机／模拟器安装和端到端收发测试。原生通话、附件及其余未支持 API 的限制仍然存在。

后端集成测试包含 TL 身份稳定性、单聊、原响应类型、群聊、差分、去重与权限隔离。Android 编译和设备验证的实际结果以本次交付说明为准，不应把服务端通过测试理解为手机收发或语音已经验证。

## 设置与日志

- Settings 和旧版个人设置页不再显示 Devices，设置搜索中也移除了对应入口。
- Debug 区块以及版本号隐藏菜单只提供“保存日志”和“查看日志”。
- 保存日志通过系统文件选择器导出 ZIP；包含日志目录中的 `.txt`、`.log` 文件，不包含数据库或堆转储。导出前等待 Java 日志队列写入并刷新，按每个文件当时的长度读取。
- 查看日志复用 Telegram 的 `UniversalFragment` 列表和 `AlertDialog` 文本弹窗，显示文件名、大小，并可选择复制文本。每次最多读取末尾 256 KiB；完整内容可通过保存日志导出。
- 新增文案包含英文与内置简体中文；没有日志时显示空状态，文件访问失败时显示错误提示。

## 当前客户端增量与验收边界

- Settings 与旧版个人设置恢复 **Nyarugram Premium**。默认未激活，只有 `POST /api/me/premium/activate` 返回真实 `active:true` 才解锁；状态查询为 `GET /api/me/premium`，启动查询失败会重试。不进入 Telegram 支付，不伪造余额。PB `UserSummary.nyarugram_premium` tag11 保留 absent/false/true；旧 PB 和 Gram ID 响应缺字段只保留同一认证会话已确认的状态，不继承其他账号/过期会话。用新 TL user 对象触发原 UserConfig 权益通知。
- Gram ID 编辑保留原区域选择器及八位号码输入。原整数布局重载把号码框宽度设为零，现明确使用浮点 weight；后台保存验证号码与稳定用户 ID，显示名统一为 Gram ID，wire `gid` / TL `phone` 不改名。
- Gram 收入页移除两处 Fragment 充值及余额不足弹窗中的 Fragment 充值入口；收益、统计、提现不移除。所有账号的发送礼物入口显示“暂无可用的礼物”，并在构造加载、拍卖、转送及礼物型 Premium/Stars/转售付款前设置防线；普通订阅、充值、抽奖和礼物外观组件不因此禁用。原生请求按账号会话及 token/GUID 取消隔离，礼物型商店交易确认也明确拒绝，既有未完成的礼物付款不会被伪装成已完成。
- 主题列表的文件解析和预览图解码移至后台，结果按 view 绑定 generation 校验；不强制每次打开聊天设置重新请求远程主题，不关闭动画。尚无设备帧耗时测量，不能把原因定位等同于性能验收。
- 壁纸首期仅纯色/渐变 NoFile：全局列表/收藏/安装与聊天个人覆盖、双人会话 `for_both`、`revert` 使用 typed RPC。预览、清除和聊天设置等待服务端返回后才改本地；不依赖伪造服务消息，不改变 pts。文件/slug/上传/图案资源/非空表情主题、消息 ID 复用及全部收藏重置尚不支持，明确失败。群及收藏夹不支持 `for_both`。
- revision 不是 pts：登录、重连、前台恢复与定期查询读取 GetWallpapers，变化时逐个补拉缓存 full-info，以便其他请求插队；全部成功且未被新变更或提示打断后才应用并推进本地 watermark，失败保留旧缓存。旧 full-info 缺 wallpaper state 不当作清除。基本群 `TL_chatFull` 无壁纸线字段，另用原 ChatThemeController 壁纸缓存持久化 NoFile，按账号槽位与本人 TL ID 隔离，不改原 TL 构造器格式。

**当前不是可交付版本。** 新 schema/mapper/UI 源码已修改，但正式 protoc 再生成、Premium/壁纸宿主回归、Android 类型编译、最终 APK/签名/版本/资源检查仍被构建工具路径的实际沙箱拒绝阻塞。旧生成 Java 不包含新增字段，不能直接构建交付。`Tools\Verify-BotgramPremium.ps1`、`Tools\Verify-BotgramWallpaper.ps1` 与使用编译后真实 TL 类型的 `Tools\Verify-BotgramGifts.ps1` 为待执行检查；已有 Java 语法解析及礼物分类替身测试不等于 Android 类型编译或运行时验证。Android Studio、模拟器与真机运行测试按用户要求暂停（NOT RUN）。历史 APK 与后端测试记录不作为本轮结果，未安装、未变更生产服务或默认 base URL。
