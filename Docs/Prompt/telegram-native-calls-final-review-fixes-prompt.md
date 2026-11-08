# 修复原生通话交付复核与 TURN/STUN 剩余问题

请在当前 Nyarugram 客户端和 Botgram.Backend 工作区中，修复本次交付复核发现的剩余问题，补齐相关回归、部署准备和实际验证记录。先复核当前源码及交付物，再持续实现和验证。

## 背景与范围

- 客户端位于 `W:\Work\Botgram`，后端位于 `W:\Work\Botgram.Backend`。
- 阅读两个仓库的 `AGENTS.md`，以及 `Docs/Botgram.Android.md`、`Docs/Botgram.GID.md`、`Docs/Botgram.Chinese.md`、`Docs/Prompt/telegram-native-calls-prompt.md` 和 `Docs/Prompt/telegram-native-calls-protobuf-fixes-prompt.md`。涉及运行服务时阅读后端 `DEPLOYMENT.md`。
- 用户后续使用 Protobuf-only。正常通话专项使用关闭 JSON RPC 和 JSON WSS 的隔离服务，保留 Telegram 原页面、VoIPService、通知、前台服务及原媒体引擎。
- 上一轮七项修复已有实现。本次独立复核确认了报告中的交付 APK、ZIP 和发布 DLL 哈希，以及 APK 包信息、v1/v2 签名和构建副本一致性。
- 独立复跑的 2949 项 baseline、709 项 PostgreSQL HTTP/WSS 回归、24 个 JSON 通话样本及协议登记检查均通过。以下剩余问题由额外边界探针和源码检查发现，应为其补充回归用例。
- 所有历史哈希、版本号和行号仅作为定位线索。执行时重新读取当前文件、配置和构建信息，保留工作区已有修改。
- 新依赖、工具和缓存全部放在 `D:\BotgramBuild`。涉及原生源码前确认 junction 的实际目标、固定版本和已有修改，按仓库要求记录来源及校验信息。
- 本提示的实现范围包括源码、隔离测试、构建、发布物及迁移说明。运行服务的部署、重启、公网端口、域名及外部资源操作按当前会话已有授权执行。

## 一、正确协商双方通话协议

### 已复现现象

通过最终发布 DLL 的 Protobuf HTTP 入口执行以下过程。

1. 主叫提出 `library_versions=[10.0.0, 9.0.0]`。
2. 被叫仅提出 `library_versions=[9.0.0]`，接听成功。
3. 主叫确认时再次提供其原始集合。
4. 激活结果仍选择 `10.0.0`，该版本不在被叫提供的集合中。

当前流程在接听和确认时改写同一个 `Protocol`。原客户端会读取 `privateCall.protocol.library_versions.get(0)` 并据此创建原生实例，因此协商结果必须由双方能力共同决定。

### 修复要求

- 明确保留双方提出的协议能力及最终协商结果。
- 按当前原生引擎的真实规则协商层范围、库版本、P2P 和 reflector 等能力。
- 最终版本应由双方支持的交集选出，按明确且稳定的规则排序或选择。双方没有兼容版本或层范围时返回明确错误。
- 发起、接听、确认、差分恢复及重试应使用一致的协商结果。
- 覆盖版本集合相同、部分重叠、顺序不同、完全不相交、层范围不相交、双方能力不同及旧数据恢复。
- 分别验证 LegacyOnly 和 PostgreSQL 正常 Protobuf 入口，以及客户端实际选择的原生实例版本。

## 二、拒绝空版本集合及非法协议输入

### 已复现现象

`library_versions=[]` 的 `requestCall` 返回 HTTP 200。当前 `ValidateProtocol()` 仅检查集合存在且数量不超过上限，允许空集合进入呼叫状态。原客户端直接读取第一个版本，存在越界风险。

### 修复要求

- 对必需协议、非空版本集合、成员类型、空字符串、长度、数量和合法层范围执行明确校验。
- 检查直接 Protobuf HTTP 请求、客户端 mapper、默认协议及持久化恢复的边界。
- 合法历史数据应保持可读，非法或无法协商的数据应给出明确结果。
- 参数校验失败时验证事务回滚，检查呼叫、事件、pts 和幂等记录的一致性。
- 增加空集合、空成员、畸形字段、超范围数值及有效边界的真实 HTTP 回归。

## 三、使用稳定的原始请求信息实现幂等

### 已复现现象

双方协议集合不同时会出现以下情况。

- 被叫接听后，主叫重试完全相同的原始 `requestCall`，返回 `409 CALL_IDEMPOTENCY_CONFLICT`。
- 主叫确认后，被叫重试完全相同的原始 `acceptCall`，返回 `409 CALL_ALREADY_ACCEPTED`。

当前去重比较使用的 `Protocol` 会在后续状态迁移中改写。现有回归使用相同协议集合，遗漏了此场景。

### 修复要求

- 分别保存重试校验需要的原始发起信息、原始接听信息及协商结果，保持其生命周期和用途清晰。
- 相同请求在接听、激活、挂断及重启后重试，应返回同一呼叫的当前有效结果或稳定终态。
- 冲突的被叫、密钥材料、语音或视频选项、协议能力及其他影响幂等语义的参数应返回明确错误。
- 覆盖请求已提交但响应丢失、双方协议不同、并发重试、终态后重试、服务重启及多设备接听竞争。
- 检查重复请求对事件和 pts 的影响，保持状态单调收敛和事务原子性。
- 两种存储路径复用共享规则，并兼容已有呼叫记录及必要的离线数据迁移。

## 四、关闭 P2P 时校验真正可用的 TURN

### 已复现现象

被叫设置 `PhoneP2P=DisallowAll`，服务端仅配置 STUN。`requestCall` 仍返回 HTTP 200，且 `P2PAllowed=false`。

`CallService.Relays` 同时保存 STUN 和 TURN，当前 `RequireRelayWhenP2pDisabled()` 仅判断 `Relays.Length`。STUN 提供地址发现，TURN 提供媒体转发。原生引擎关闭 P2P 后采用 relay-only 策略，需要可用的 TURN 中继。

### 修复要求

- 区分 STUN 配置和 TURN 配置，按实际支持的媒体中继类型检查关闭 P2P 的路径。
- 对 TURN 的主机、端口、传输方式和当前认证方式所需的凭据进行校验。
- 无配置、仅 STUN、缺失凭据、非法地址或端口等配置应有明确错误。关闭 P2P 且缺少可用 TURN 时返回 `CALL_RELAY_UNAVAILABLE` 或对应的明确配置错误。
- 保持全局开关、双方隐私、请求能力、存储的有效协议、`P2PAllowed` 和原生引擎参数一致。
- 覆盖双方分别禁止 P2P、联系人及用户例外、重启恢复和配置变更的行为。
- 将静态配置有效性与实际 TURN 连通性分别验证和报告。

## 五、完整处理 TURN/STUN URL 与传输方式

### 已确认现状

- `CallService.Configure()` 只识别 `turn:` 和 `stun:`。
- URL 的查询参数被截掉，`?transport=tcp` 等信息丢失。
- `turns:` 被忽略，现有连接模型和 mapper 尚未完整表示 TCP/TLS 传输语义。
- `VoIPService` 将连接转换为 `Instance.Endpoint`，JNI 和原生引擎随后创建 ICE 服务器配置，最终支持情况需要逐段核对。

### 修复要求

- 从配置解析、后端模型、持久化、Protobuf、Java mapper、`Instance.Endpoint`、JNI 到实际选中的 tgcalls 实现，核对完整链路。
- 分别保存 scheme、主机、IPv4 或 IPv6、端口和 transport，按明确规则处理默认值。
- 根据本仓库引擎的真实能力实现 TURN/UDP、TURN/TCP、TURN/TLS 及 STUN 的适配。引擎尚未支持的组合应在配置或请求阶段给出明确错误及能力状态。
- 保持 TLS 与 TCP 各自的语义，检查实际创建的 ICE URL、凭据和传输参数。
- 现有 `phoneConnectionWebrtc` 下发通道继续承担其支持的连接信息。需要扩展协议时追加编号，并维护兼容策略、字段登记和生成代码。
- 对主机名、IPv4、带括号 IPv6、默认端口、显式端口、UDP/TCP 参数、TLS scheme 及非法组合补充用例。
- `VoIPService.updateServerConfig()` 请求 `phone.getCallConfig`，缓存响应为 `voip_server_config`，并传入 `Instance.setGlobalServerConfig()`。新增的 `botgram_calls` 专用字段当前没有用于构造 ICE 的客户端解析逻辑，ICE 端点来自 `phoneConnectionWebrtc`。如实现需要新增全局参数，按原生库实际消费格式接入，并记录与连接列表的职责边界。

## 六、验证 coturn 与实际媒体路径

### 配置与验证要求

- 读取当前配置，列出实际缺少的 TURN/STUN 服务地址、凭据、监听和中继端口、外部地址映射及用途。
- 跨网络场景使用双方可达的服务地址。`192.168.x.x` 地址的验证范围应按设备可访问的网络明确标记。
- 按已有部署授权准备或更新 coturn 配置，核对认证、UDP/TCP/TLS 支持、监听端口及媒体中继端口范围。
- 分别验证同局域网、不同网络、P2P 允许、P2P 禁止、强制中继、UDP 受限、网络切换和短暂断网。
- 强制中继验收应包含实际选中的 candidate pair 类型、TURN allocation 或媒体转发证据，以及双向语音、双向视频的实际结果。
- 区分配置已下发、TURN 分配成功、信令状态成功和真实媒体成功，并分别报告证据。
- 缺少设备、外部服务或具体授权时列出缺项、可执行步骤和待运行用例，继续完成可独立验证的实现与隔离测试。

## 七、统一交付目录与部署脚本输入

### 已确认问题

本次报告的 ZIP 及后端仓库 `artifacts/publish/Botgram.Backend.dll` 为新版本，但客户端 `artifacts/backend/Botgram.Backend.dll` 仍是上一轮版本。

`Tools/Update-BotgramBackend.ps1` 和 `Tools/Deploy-BotgramBackend.ps1` 从客户端 `artifacts/backend` 读取发布物。按当前目录执行会取到旧后端。

### 修复要求

- 按仓库既有交付约定同步最终通过验证的完整发布物，或在本次明确调整后的唯一发布输入上统一脚本及说明。
- 检查 DLL、依赖、配置模板、协议文件和其它必要文件的完整性，处理残留旧文件时先确认目标路径及 junction。
- 核对后端仓库发布目录、客户端交付目录、ZIP 内 DLL 和 `build-info.json` 中的实际哈希。
- 验证部署脚本将读取的确切路径和文件集合，生成可审阅的部署准备结果。
- 交付记录应分别描述生成的发布物和实际运行中的版本。

## 八、纠正 schema v7 迁移说明并提供离线步骤

### 已确认问题

`artifacts/build-info.json` 的 `nativeCallsProtobufFixVerification.migration` 声称 schema v7 在启动时自动应用。

实际后端启动执行 `VerifySchemaAsync()`，遇到旧 schema 会拒绝启动，并要求显式离线迁移。当前记录与源码不一致。

### 修复要求

- 以明确的离线迁移流程准备升级，检查当前 schema、版本、活动读写者和迁移入口。
- 提供当前仓库中实际可执行的迁移命令或维护入口，说明输入配置、适用旧版本和预检查。
- 根据已有授权停止相关读写者并备份数据、配置和密钥环，迁移后验证账号、有效会话、TL 身份、聊天、消息、隐私、验证器、恢复码和 pts 保留。
- 对旧 schema 升级、重复执行、迁移失败回滚、快照导入导出及新旧读写者兼容增加回归。
- 更新构建信息和交付说明，使启动检查、显式迁移及实际部署状态一致。

## 验证和最终交付

1. 为上述每项复现补充针对性回归，通过正常 Protobuf HTTP 入口验证，并分别运行 LegacyOnly 和 PostgreSQL 路径。
2. 运行协议登记、客户端与后端 schema 逐字节一致性、固定 protoc 可重复生成、Java lite ↔ C# 互操作及真实 Android TL 编解码检查。
3. 执行相关聊天、身份、联系人、presence、隐私、验证器和存储回归，单独报告四组件事件和消费者的实际结果。
4. 完成全部客户端源码修改后构建交付变体，核验 APK 签名、包名、版本、架构和资源，以及最终 APK、后端发布物和 ZIP 的实际 SHA-256。
5. 更新 `artifacts/build-info.json`，逐项记录本次测试、仍失败的回归、外部配置及设备验收边界。
6. 按原生通话总提示复核能力字段的启用条件和资料缓存刷新，提供与真实可用能力一致的入口反馈。
7. 最终报告列出每项问题的根因、修改文件、实际执行的验证、交付路径、离线迁移步骤、部署及提交情况和剩余工作。

测试使用独立数据目录、随机本机端口和 `botgram_test_*` 数据库。日志保护 Bearer、密码、TURN 凭据、通话密钥及完整敏感信令。
