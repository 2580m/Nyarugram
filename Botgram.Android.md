# 原 Telegram 页面接入说明

保留原 `LaunchActivity`、`MainTabsActivity`、`DialogsActivity`、`ChatActivity`、消息气泡、群聊创建及资料页面。此前的独立 Botgram Activity、聊天界面、消息缓存和 WebRTC 界面已移除。业务兼容工作集中在后端，而不是另建客户端聊天模型。

## 协议边界

后端 `../Botgram.Backend/TelegramGateway.cs` 按此仓库的 TL 类实现请求与响应语义，包括稳定的数字用户／群组 ID、消息、会话、历史分页、已读和更新差分。客户端新增的是通用 TL 对象传输，不按业务改写聊天控制器。

采用经过 Bearer 认证的 HTTP(S) TL 对象 RPC，**没有实现 MTProto 加密、数据中心和握手协议**，因此未修改的官方 Telegram 客户端不能直接连接。客户端仍需以下接入改动：

- `LoginActivity`：用户名／密码认证，登录成功进入原 `onAuthSuccess` 和页面导航。
- `BotgramLoginView`：作为原 `LoginActivity` 内的 `SlideView` 提供用户名／密码认证，复用原登录页的布局、继续按钮、加载动画、描边输入框及错误弹窗；注册时显示名称输入框。
- `BotgramSession`：按 Telegram 账号槽位隔离令牌，并用 Android Keystore AES-GCM 加密保存。
- `BotgramTransport`：通用 TL 类／字段封送、HTTP 请求、取消绑定、错误回调、前台差分轮询；不做会话、消息或用户转换。
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

客户端默认连接 `https://nyarugram.moe.autos:8521`。服务器地址省略协议时使用 HTTPS，省略端口时默认 8521，而非 443。公网 RPC 与 WSS 共用 TLS 8521；仅调试构建允许显式 HTTP 本地开发地址，非调试构建拒绝 HTTP。登录成功后，使用原搜索页面按完整用户名找到其他用户，打开原聊天页面。

前台 WSS 通过 Authorization Bearer 连接 `/api/ws`，仅将事件作为唤醒原 `getDifference` 的提示，事件游标不当作 Telegram pts。后台或切换账号时关闭旧连接，断线采用 1–30 秒退避重连；原差分轮询保留。HTTPS/WSS 均使用系统证书信任和主机名验证，不接受自签名绕过，不跟随携带凭据的重定向。WSS 握手 401 使对应会话退出；TLS 失败不降级 HTTP。尚未实现后台推送。

登录和注册表单把服务器地址与右侧端口分开输入，端口默认 8521。粘贴带端口的完整 HTTP(S) 地址时自动拆分；保存会话仍使用完整地址。升级后已保存的旧 5080 端口迁移至 8521，其他自定义端口保留。登录/注册切换采用淡出、轻微滑动和淡入动画，保留表单内容。`Tools/Verify-BotgramServerAddress.ps1` 验证地址组合、端口边界、IPv6 与旧地址迁移。

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

## 本次验证结果

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
