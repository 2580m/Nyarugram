# Nyarugram

**保留熟悉的 Telegram Android 界面，连接自己的消息服务。**

Nyarugram 是基于 Telegram Android 源码开发的自建后端客户端。它保留原生会话列表、聊天、联系人、个人资料、设置和通话页面，通过独立的通信适配层连接 Botgram.Backend。

本项目需要用户自行部署 Botgram.Backend，以及所需的数据库、TLS 反向代理和 TURN/STUN 等配套服务；项目不提供公共服务器或托管账号服务。客户端服务器地址由用户填写，部署步骤见后端仓库的 `DEPLOYMENT.md`。

项目仍在持续开发与真机验证中。它不是 Telegram 官方客户端；自建后端的账号、联系人和聊天数据与 Telegram 服务彼此独立。

## 项目特点

- **原生页面与交互**：沿用 Telegram 的页面、控制器、消息气泡、主题和动画，不另建一套聊天界面。
- **自建账号体系**：使用用户名和密码注册、登录，以 Gram ID 标识用户，无需短信注册。
- **Protobuf 通信**：原页面的 TL 对象通过显式映射转换为 typed Protobuf，与后端进行 HTTP(S) RPC 通信。
- **事件驱动同步**：通过 WS/WSS 接收事件提示，唤醒差分拉取；保留轮询作为补偿，支持有效账号的后台事件接收。
- **原生通话接入**：复用语音／视频通话页面和媒体引擎，接入呼叫状态、信令、TURN/STUN、来电通知和未接来电提醒。
- **离线简体中文**：中文随 APK 打包，不需要先下载语言包。
- **独立安装**：应用包名为 `me.nyarugram.messenger`，可与 Telegram 共存。

代码中的 `Botgram` 名称用于通信模块、协议、工具和后端仓库；面向用户的应用名称为 **Nyarugram**。

## 当前功能与边界

| 范围 | 当前情况 |
| --- | --- |
| 账号与身份 | 用户名／密码登录注册、Gram ID 分配与修改、会话恢复 |
| 联系人与搜索 | 按用户名或 Gram ID 查找、添加联系人、联系人列表与个人别名；不把 Gram ID 写入系统通讯录 |
| 基础聊天 | 单聊、基础群组、文字消息、历史记录、已读与差分同步 |
| 一对一通话 | 已接入原生语音／视频流程、信令和中继配置；具体设备与网络条件需要实测 |
| 来电提醒 | 高优先级来电频道、符合系统权限条件的全屏来电、未接语音／视频通知 |
| 隐私与安全 | 已接入部分隐私规则、黑名单、TOTP 验证器和恢复码；并非所有设置都已实现完整行为 |
| 个性化 | 原主题与壁纸页面、后端确认的免费 Nyarugram Premium 开通流程 |

保留原页面不代表已经实现全部 Telegram 服务能力。当前仍有这些边界：

- 附件、语音消息、频道、机器人、群组通话等能力不能按完整支持对待；未实现的请求应返回明确错误。
- 自动删除和账号保留期限等设置中，部分仅完成参数读写，不能等同于清理任务已生效。
- 联系人备注、部分隐私例外及其他尚未支持的选项，以客户端能力和后端返回为准，不应静默伪装成功。
- 后台常驻不等于系统推送。深度休眠、进程回收或用户强行停止应用时，不能保证及时送达。
- 跨 NAT、强制 TURN、切换网络和多设备场景仍需持续验收。

当前实现、历史验证和未完成项分别记录在 [Android 接入说明](Botgram.Android.md) 与 [构建交付记录](../artifacts/build-info.json)。历史章节可能对应旧版本，判断功能状态时应结合当前源码和最新验证记录。

## 开始使用

1. 安装 Nyarugram APK。
2. 自行部署兼容的 Botgram.Backend 服务，在登录页填写自己的服务器地址和端口。
3. 注册自建后端账号，或使用已有用户名、密码登录。
4. 按完整用户名或 Gram ID 找到其他用户，添加联系人并开始聊天。

自部署服务器的局域网地址格式示例：

```text
http://192.168.1.10:8521
```

以上仅为私网地址格式示例，需要替换为自己部署的服务器；使用 HTTPS 时填写自己的域名并配置受信任证书。省略协议时默认使用 HTTPS；端口留空且地址未包含端口时默认使用 `8521`。填写实际主机地址，不要把 `192.168.0.0/16` 这类网段写进登录表单。

### HTTP、HTTPS 与实时事件

| 服务器地址 | 通信方式 |
| --- | --- |
| HTTPS | HTTPS RPC ＋ WSS，校验证书与主机名 |
| 私有 IPv4 HTTP | HTTP RPC ＋ WS，自动启用事件连接 |

自动使用明文 WS 的私有 IPv4 范围为 `10.0.0.0/8`、`172.16.0.0/12` 和 `192.168.0.0/16`，判断仅接受实际 IP 字面地址，不通过 DNS 解析域名来放行。HTTPS 连接失败不会自动降级为 HTTP。

HTTP/WS 中的凭据与数据没有 TLS 加密，适合可信的局域网测试；公网部署使用 HTTPS/WSS。事件提示只负责通知客户端拉取差分，事件游标与 Telegram 的 `pts` 是不同概念。

### Gram ID

Gram ID 的显示格式为：

```text
+030 12345678
```

前三位为地理区域代码，后八位为用户号码，保留前导零。它是应用身份标识，不是可拨打的电话号码。

注册时可请求近似定位，由客户端把反查得到的国家代码交给后端映射区域；定位失败使用 World `001`。应用不向后端发送或存储经纬度，也不使用 SIM 或 IP 静默代替定位。详见 [Gram ID 说明](Botgram.GID.md)。

### 通话与通知

通话需要后端提供可用的媒体连接配置。跨网络部署应准备 TURN/STUN，并验证中继地址、凭据和 UDP 端口可达。

通知、全屏来电、悬浮窗和免打扰例外是不同的系统设置。后台来电能否直接显示原通话页面，取决于 Android 版本、授权状态及厂商限制；未接来电使用独立通知，点击可进入对应聊天。

## 通信与数据

客户端通过带 Bearer 认证的 HTTP(S) 请求访问自建后端。聊天及通话 RPC 使用 typed Protobuf；注册、登录、能力协商等接口仍可使用 JSON。**Protobuf-only 不代表所有 HTTP 接口都禁止 JSON。**

页面继续消费原 TL 类，适配层负责双向映射，返回的更新交给原控制器和本地存储处理。绑定自建后端的会话失效后不会回退到 Telegram 原生网络；账号之间的请求和回调保持隔离，Bearer 令牌使用 Android Keystore 加密保存。

自建后端连接不是 MTProto，不能通过修改 Telegram 数据中心地址让未修改的官方客户端直接接入。HTTPS/WSS 提供传输加密，不代表聊天消息已经具备端到端加密。

后端独立维护在 **Botgram.Backend** 仓库。本仓库是 Android 客户端，单独构建客户端不应依赖后端目录中的文件；跨仓库协议验证需要另外准备后端测试代码与样本。

## 从源码构建

当前提供的构建脚本面向 Windows PowerShell，默认交付 standalone Debug APK。

### 环境

| 组件 | 当前配置 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.13 |
| Android SDK | 36 |
| Android NDK | `27.2.12479018` |
| 最低 Android 版本 | Android 6.0 / API 23 |
| APK 架构 | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |

先准备源码中固定版本的原生依赖、SDK/NDK 和构建工具。**本项目的 Windows 工作流要求新下载的工具、依赖和缓存统一放在 `D:\BotgramBuild`。** `Tools/Prepare-BotgramBuild.ps1` 可辅助准备固定版本的原生源码依赖，但不代替完整的 Android 工具链安装。

原生依赖目录可能是指向 D 盘的目录联接。清理或更新前确认目标，不要递归删除联接指向的依赖，也不要把联接当作空子模块重建。

### 构建命令

在仓库根目录执行，JDK 路径按本机安装位置调整：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true'
./Tools/Build-Botgram.ps1
```

脚本调用：

```text
:TMessagesProj_AppStandalone:assembleAfatDebug
```

构建产物：

```text
TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/Nyarugram-debug.apk
```

版本和包名配置位于 `gradle.properties`。本地交付副本通常位于 `artifacts/android/Nyarugram-debug.apk`，对应哈希和验证结果保存在 `artifacts/build-info.json`；同一版本号的不同构建可能具有不同哈希。

发布自己的版本前，应检查品牌、签名、服务配置和第三方服务配置。不要把仓库中的调试配置直接当作正式发行配置。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `TMessagesProj/` | Telegram Android 主模块及 Nyarugram 页面接入 |
| `TMessagesProj/src/main/java/org/telegram/botgram/` | 认证、会话、Protobuf 映射、事件同步、Gram ID 等适配代码 |
| `TMessagesProj_AppStandalone/` | standalone 应用与 APK 构建入口 |
| `Shared/` | 客户端自带的协议快照和固定 M49 数据 |
| `Tools/` | 构建、翻译、协议与宿主验证工具 |
| `Docs/` | 项目说明、接口审计与其他文档 |
| `Docs/Prompt/` | 专项任务与修复说明 |
| `Docs/artifacts/` | 本地交付报告与迁移记录 |
| `artifacts/` | APK、后端发布物及验证元数据 |

若客户端与后端并列检出，后端开发和部署说明位于 `../Botgram.Backend/AGENTS.md`、`../Botgram.Backend/DEPLOYMENT.md`。

## 开发与验证

开始修改前阅读 [开发指引](../AGENTS.md)。按改动范围选择验证：

```powershell
# 客户端地址规则和离线地理数据
./Tools/Verify-BotgramServerAddress.ps1
./Tools/Verify-BotgramM49.ps1

# 编译 Android Java 类、准备好对应依赖与后端测试样本后
./Tools/Verify-BotgramProtobufMapping.ps1
./Tools/Verify-BotgramTL.ps1
```

协议或共享数据变更需要保持客户端快照、后端定义与生成代码一致。不要手工修改生成的 Protobuf 类。服务端集成测试、宿主 TL 编解码和 APK 构建通过，不能替代双机聊天、音视频、后台来电及切网真机测试。

提交问题时，请说明安装包版本与哈希、手机型号和 Android 版本、服务器连接方式、复现步骤及预期结果。应用内提供“保存日志”和“查看日志”；分享日志前检查是否包含个人信息或服务凭据。

更多说明：

- [Android 接入与功能记录](Botgram.Android.md)
- [Gram ID 与区域数据](Botgram.GID.md)
- [离线中文与翻译流程](Botgram.Chinese.md)
- [Protobuf 实现记录](PROTOBUF-IMPLEMENTATION.md)

## 上游与许可证

Nyarugram 基于 [Telegram Android](https://github.com/DrKLO/Telegram) 开发，感谢 Telegram 及相关开源项目的贡献。

本仓库附带 [GNU GPL v2 许可证](../LICENSE)。修改和分发时请保留原有版权及许可证声明，并遵守相应源码提供义务；第三方依赖遵循各自许可证。Nyarugram 与 Telegram 官方不存在隶属关系。
