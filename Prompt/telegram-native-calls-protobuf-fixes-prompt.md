# 修复原生通话 Protobuf 迁移审阅问题

请修复当前 Nyarugram 原生一对一通话 Protobuf 迁移中的以下七项问题，并完成对应回归和交付物验证。先核对当前工作区，再持续实现和验证，直到本提示要求的源码修复及可运行验证完成。

## 项目与任务边界

- 客户端位于 `W:\Work\Botgram`，后端位于 `W:\Work\Botgram.Backend`。
- 开始前阅读两个仓库的 `AGENTS.md`，以及客户端的 `Botgram.Android.md`、`Botgram.GID.md`、`Botgram.Chinese.md` 和 `Prompt/telegram-native-calls-prompt.md`。涉及服务运行时阅读后端 `DEPLOYMENT.md`。
- 本提示承接原生通话总任务，当前实现阶段为部分 Protobuf 协议及 mapper 迁移。按原提示保留 Telegram 原页面、控制器、通知、前台服务和媒体引擎。
- 用户后续采用 Protobuf-only。正常业务与验收使用 typed Protobuf RPC，JSON RPC 和 JSON WSS 在专项测试中关闭。既有 JSON 通道的测试可用于检查共享规则改动的回归。
- 本次核心范围为下列七项修复，以及支撑这些修复的协议、存储迁移、状态机和测试。原生通话完整验收中的设备、媒体、中继和群组审计结果分别报告。
- 工作区已有 presence、contacts、隐私与验证器、PostgreSQL、Kafka 等未提交修改。保留已有工作，并按当前文件内容判断改动归属。
- 新依赖、工具及缓存全部使用 `D:\BotgramBuild`，沿用固定版本和校验信息。
- 本次授权包括源码修改、隔离测试、构建和交付物更新。运行中服务的升级、重启及正式数据迁移按当前会话的部署授权执行，另行明确实际操作范围。

## 先复核调用链和问题

从原聊天页及资料页的按钮开始，核对 `VoIPHelper`、`VoIPService`、`NativeInstance`、`ConnectionsManager`、Botgram 请求与响应 mapper、更新分发及后端存储的完整链路。

以下源码位置是审阅时的定位线索，行号随工作区变化，以当前源码为准。

- 客户端 `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java`。
- 客户端 `TMessagesProj/src/main/java/org/telegram/botgram/BotgramProtobufRequestMapper.java` 和 `BotgramProtobufResponseMapper.java`。
- 后端 `ProtobufRpcMapper.cs`、`CallService.cs`、`PostgresBackend.Rpc.cs`、`PostgresBackend.Calls.cs`、`PostgresBackend.Messages.cs`、`PostgresBackend.Events.cs`。
- 后端 `PostgresBackendSchema.cs`、`PostgresSnapshotMigration.cs`、`SyncService.cs`、`KafkaDelivery.cs`、`PostgresDeliveryPersistence.cs`、`FourComponentRuntime.cs`。
- 后端 `Protocol/botgram_rpc.proto`、客户端 `Shared/botgram_rpc.proto`、协议登记及检查工具。

审阅时已重新编译后端，并运行了隔离 HTTP 探针。首个正常形状的 Protobuf `request_call` 返回 500。为了继续检查后续逻辑，探针曾通过 JSON 入口预置呼叫，再通过 Protobuf 操作。修复后的验收应直接从 Protobuf 创建呼叫。

## 一、修复通话对象映射的数值类型异常

### 已复现问题

`ProtocolJson()` 将 `min_layer`、`max_layer` 写成内存中的 `Int32` JSON 节点，`Protocol()` 随后使用 `GetValue<long>()` 读取，导致异常。

```text
System.InvalidOperationException
A value of type 'System.Int32' cannot be converted to a 'System.Int64'.
```

异常发生在 `Protocol()` → `PhoneCall()` → `PhoneResult()` → `RequestCall()`，正常请求返回 HTTP 500。连接端口等字段也存在类似读写方式。

### 修复与验收

- 统一通话协议和连接字段的读写表示，复用现有可靠的数值转换逻辑。
- 覆盖请求构造的内存节点、从持久化 JSON 恢复的节点、默认协议和中继配置生成的节点。
- 对超范围数值、缺失必需协议、非法层范围和空版本集合返回明确参数错误。
- 通过真实 Protobuf HTTP 依次执行发起、接听、确认及带连接信息的响应映射，验证事务成功持久化和失败回滚。

## 二、补齐原生通话的 DH 配置依赖

### 已确认问题

原 `VoIPService` 的主叫及被叫流程均调用 `TL_messages_getDhConfig`。当前 Protobuf 请求 mapper 缺少该分支，最终返回 501，协议中也缺少对应操作。现有八个 `phone.*` 方法尚不足以走通原入口。

### 修复与验收

- 根据当前 TL 定义和实际调用方追加 typed DH 配置请求及响应，覆盖 `version`、`random_length`、完整配置和未变化配置的语义。
- 使用已有可信的 DH 参数来源，验证参数版本、公钥及客户端需要的随机字节长度和边界。
- 服务端验证其可见的密钥参数，包括 `g_a_hash` 的长度、公钥的表示和可验证的哈希关联。端侧继续执行原公钥、共享密钥及指纹校验。
- 接入客户端两个 mapper、后端正常入口以及两种受支持存储路径。
- 测试首次获取、缓存版本命中、非法随机长度、畸形参数和重启后的调用。使用真实 Android TL 类完成二进制往返。
- 从原调用链验证 DH 成功后能够继续发送 `phone.requestCall`，被叫接听流程能够继续密钥协商。

## 三、补齐 PostgreSQL 和四组件路径

### 已确认问题

通话新分支仅进入了基于 `Database` 的 mapper。`PostgresBackend.Rpc` 缺少八个操作，最终返回 501。现有 PostgreSQL 呼叫模型仍缺少原生通话身份、密钥协商和协议字段，状态约束也缺少 `accepted`。差分、事件约束、pts 和消费者尚未完整接入原生通话事件。

### 修复与验收

- 为八个操作及 DH 依赖接入 PostgreSQL 正常 RPC 分发，复用共享业务规则，保持权限、幂等和状态迁移一致。
- 持久化稳定呼叫 ID、access hash、参与者、会话关联、请求幂等信息、接听会话归属、必要的密钥协商参数、协议、连接和终态信息。
- 在明确的离线 schema 迁移中追加结构和约束。兼容现有 schema、历史账号、有效会话、TL 身份、聊天、消息、隐私设置、验证器和恢复码数据。
- 检查快照导入导出和回退表示，保持业务身份、事件顺序和各用户 pts 的稳定性。
- 将呼叫状态和原生信令接入差分分页、事件编解码、outbox 和四组件消费者。冻结旧版事件契约按现有版本策略维护，新语义通过明确版本接入。
- 验证同一分区的前序事件、领取、重试、发布确认、消费者失败及恢复。旧读写者遇到新结构或事件时应有明确的兼容行为。
- 分别执行 LegacyOnly、PostgreSQL 和可用四组件环境的回归，报告各自结果及实际缺少的环境配置。

## 四、执行双方 P2P 隐私规则

### 已复现问题

当前 `p2p_allowed` 仅取自全局 `CallService.P2p`。被叫设置 `PhoneP2P=DisallowAll` 后，激活响应仍返回 `p2p_allowed=true`，客户端将该值直接传入媒体引擎。

### 修复与验收

- 结合全局配置及双方针对对方的 `PhoneP2P` 规则，计算本次呼叫的有效 P2P 能力，并在状态、协议和引擎配置间保持一致。
- 继续执行呼叫隐私、黑名单、联系人规则和用户例外，检查发起和接听确认阶段的权限。
- 关闭 P2P 的路径使用与本仓库原媒体引擎兼容的中继配置。缺少可用中继时返回明确的能力或连接失败结果。
- 覆盖默认规则、双方分别禁止、联系人及指定用户例外、拉黑和重启恢复。
- 分别记录服务端策略断言、客户端引擎参数验证和设备上强制中继的实测证据。

## 五、修复请求幂等和状态迁移重试

### 已复现问题

- `accept` 和 `confirm` 首次成功后，完全相同的请求重试返回 400。
- 同一主叫使用相同 `random_id` 请求另一个被叫，会收到原被叫的呼叫结果。
- 当前去重仅复用进行中的呼叫，终态记录没有成为稳定的重试结果。

### 修复与验收

- 为 `requestCall` 定义并持久化幂等语义，校验被叫、语音或视频选项、初始密钥参数及其他影响呼叫身份的字段。
- 相同请求重试返回既有结果，冲突参数返回明确错误。终态后的重试保持原呼叫身份和终态，用户再次发起使用新的幂等标识。
- 接听和确认的相同请求可安全重试，冲突的密钥或协议参数受到校验。重复挂断保持终态和已确认原因，控制重复事件及 pts 增长。
- 验证请求已提交但响应丢失、并发重复请求、乱序确认、双方挂断竞争、互相呼叫和服务重启。
- 定义多设备接听的赢家及其他设备的状态收敛，迟到请求应保持已确认的呼叫参数及归属。
- 对存储失败和响应编码失败验证原子回滚，成功响应应对应已持久化状态。

## 六、修复结束后状态与信令重放

### 已复现问题

呼叫结束后，Protobuf 差分仍返回历史 `Requested` 和信令。当前 `call.tl_signal` 缺少有效期及终态过滤。若旧来电和终态落在不同分页，客户端会先收到已失效的来电状态。

### 修复与验收

- 明确持久状态事件、短期实时信令和差分恢复之间的边界，按当前权威呼叫状态处理恢复与重放。
- 使终态单调收敛，处理重复、乱序及跨分页更新，保证已结束呼叫在重连后保持结束状态。
- 对原生信令设置合理的大小、有效期、参与者及呼叫阶段限制，并验证历史呼叫的信令隔离。
- 保持已有事件 ID、pts 和分页游标语义。对被过滤或归并的事件定义连续推进方式，验证读取及重放后的状态一致性。
- 明确 expired 数据的清理方式，保持差分位置和历史消息同步的兼容性。
- 覆盖结束后重新登录或重连、超过 100 条的消息与通话混合差分、终态位于下一页、过期信令、重复信令、后台恢复和服务重启。

## 七、同步协议登记及兼容检查

### 已复现问题

当前执行以下检查失败。

```powershell
node tools/verify-protobuf-contract.mjs --android-root ../Botgram
```

报错为 `Unregistered binary field PhoneCall.g_a_hash`。新增二进制字段、operation 和更新类型尚未完整进入字段政策和契约检查。

### 修复与验收

- 将通话和 DH 的请求、响应、字段、更新类型及必要的二进制字段登记到对应政策文件和检查工具中，保留字段用途、权限及校验边界。
- 为新操作追加编号，保留已有编号和 wire 含义。
- 维持二进制字段的明确允许清单，逐项检查密码学参数和原生信令的用途及大小边界。
- 同步客户端与后端各自持有的协议快照，并检查逐字节一致性。
- 使用固定版本的 `tools/protobuf.ps1` 生成 Java lite 和 C# 类型，验证重复生成一致。
- 补充新旧 schema、未知更新、可选字段及枚举的兼容用例，检查旧客户端接收新通话更新时的实际行为。

## 必须补充的正常入口验证

1. 使用关闭 JSON RPC 和 JSON WSS 的隔离服务，直接经 Protobuf HTTP 完成 DH、发起、接收确认、接听、密钥确认、信令、挂断和差分恢复。
2. 覆盖参数缺失、畸形密钥或协议、非参与者、错误 access hash、黑名单、呼叫隐私和 P2P 隐私。
3. 覆盖上述请求重试、并发状态迁移、多设备竞争、过期、跨分页重放、重启和持久化失败。
4. 分别验证 LegacyOnly 和 PostgreSQL，四组件环境验证事件版本、outbox 和消费者路径。
5. 运行协议登记检查、可重复生成及 Java lite ↔ C# 互操作检查，使用真实 Android TL 类型检查新增请求、响应和更新。
6. 完成全部客户端源码修改后再构建 standalone Debug APK，核验签名、包名、版本、架构和本次资源。版本按用户当前要求处理。
7. 检查原有聊天、联系人、身份、在线状态、隐私、验证器和恢复码回归。现有 `calls.mjs` 的 JSON 测试单独记录，新 Protobuf HTTP 用例单独记录。

所有测试使用独立数据目录、随机本机端口和 `botgram_test_*` 数据库。日志只记录阶段、状态和耗时，保护认证凭据、通话密钥和完整敏感信令。

## 能力字段与设备验收

- 根据完整调用链、隐私和真实可用能力设置 `phone_calls_available`、视频及其他相关能力字段，检查资料缓存刷新和能力查询失败的反馈。
- 原生语音、视频、语音与视频切换、强制中继、跨网络、后台、锁屏、系统回收及设备权限场景，按 `telegram-native-calls-prompt.md` 分别验收。
- 外部中继、推送凭据、设备或网络环境缺失时，列出具体配置项、用途、可复现步骤和待运行用例，继续完成可独立验证的源码及隔离回归。
- 单独补充群组及频道通话的调用依赖审计和阶段状态。

## 最终交付

- 逐项汇报七项问题的根因、修改文件和实际回归证据。
- 提供验证后的 APK、后端发布物、实际 SHA-256 和必要的离线数据库迁移步骤。
- 按仓库要求更新 `artifacts/build-info.json`，分别记录构建、宿主、HTTP、存储、四组件和设备测试结果，以及仍失败的回归。
- 列出一对一语音、视频、群组通话、后台来电、跨网络和中继的当前验收状态及剩余工作。
- 明确本次实际部署与提交情况，以及用户需要提供的具体外部配置。
