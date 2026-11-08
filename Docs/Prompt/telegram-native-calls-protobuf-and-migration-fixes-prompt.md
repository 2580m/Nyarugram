# 修复 LegacyOnly Protobuf 通话协商与既有数据库离线迁移入口

请完成当前审阅的问题，修复代码、补充回归并更新实际交付记录。先核对当前工作区，保留已有用户修改，持续推进到本提示要求的实现和可运行验证完成。

## 项目与范围

- 客户端位于 `W:\Work\Botgram`，后端位于 `W:\Work\Botgram.Backend`。
- 阅读两个仓库的 `AGENTS.md`，客户端的 `Docs/Botgram.Android.md`、`Docs/Botgram.GID.md`、`Docs/Botgram.Chinese.md`，以及后端 `DEPLOYMENT.md` 和当前存储模式限制。
- 参考 `Docs/Prompt/telegram-native-calls-prompt.md` 与 `Docs/Prompt/telegram-native-calls-final-review-fixes-prompt.md` 理解原生通话链路和此前修复。
- 正常业务和本次验收采用 Protobuf-only。LegacyOnly 是存储模式，其 Protobuf RPC 路径由 `ProtobufRpcMapper` 执行。
- 本次实现范围为通话协商和重试逻辑的遗漏，以及能对指定既有隔离数据库执行的离线迁移入口。当前关系后端要求回环地址及 `botgram_test_*` 数据库，迁移工具遵循该范围。
- 客户端协议快照同步及 `getCallConfig` 消费说明已另行修正。以当前文件为准保留这些修复，并检查其回归。
- 新依赖、工具和缓存全部使用 `D:\BotgramBuild`，沿用固定版本及校验信息。
- 本提示授权源码修改、隔离测试、构建和交付物更新。运行服务和正式数据的操作按当前会话的部署授权执行。

## 一、补齐 LegacyOnly 存储下的 Protobuf 通话协商与幂等

### 当前根因与复现

`CallService` 已有 `NegotiateProtocol()`、`RequireSameCapabilities()`，模型也已有 `RequestProtocol`、`AcceptProtocol` 和协商后的 `Protocol`。JSON 网关和 PostgreSQL 路径已接入这些规则，但 `ProtobufRpcMapper` 的 `AcceptCall`、`ConfirmCall` 仍保留旧实现。

最终发布 DLL 的隔离 Protobuf-only HTTP 探针复现了以下过程。

1. 主叫提供 `library_versions=[10.0.0, 9.0.0]`，被叫仅提供 `[9.0.0]`。
2. 发起、接听和确认均成功。
3. 激活结果仍选择 `10.0.0`，该版本不在被叫提供的集合中。
4. 确认后重试被叫原始 `acceptCall`，返回 `409 CALL_ALREADY_ACCEPTED`。

源码中接听分支仍比较当前 `Protocol` 与归一化后的新协议，接听和确认仍改写 `Protocol`，遗漏了原始提案和协商结果的正确生命周期。现有 JSON 通话回归和 PostgreSQL 协商用例通过，仍无法覆盖这个正常 Protobuf 入口。

### 修复要求

- 将已有共享协商规则接入 `ProtobufRpcMapper` 的正常请求路径，复用现有实现。
- 发起时保存原始 `RequestProtocol`，首次接听时保存原始 `AcceptProtocol`，由双方提案计算并保存协商后的 `Protocol`。
- 协商层区间和库版本交集，P2P、反射器能力及双方隐私按已有共享规则计算。没有兼容能力时返回明确错误。
- 确认阶段校验原始主叫提案，后续状态、差分和重试复用既有协商结果。
- 请求重放分别依据稳定的原始发起及接听信息校验，覆盖激活、终态和重启后的重试。冲突参数保持明确错误，重复请求保持同一呼叫身份及状态收敛。
- 接听阶段执行与 JSON、PostgreSQL 一致的隐私及有效 P2P 计算，关闭 P2P 时检查可用 TURN。
- 兼容历史缺少提案字段的有效记录，按现有迁移及回退规则处理。
- 检查客户端实际回显的确认协议和原生实例版本选择，保持与原调用链一致。

### 必须新增的回归

- 在 `Storage:Mode=LegacyOnly` 且 JSON RPC、JSON WSS 均关闭的隔离服务上，直接经 Protobuf HTTP 发起呼叫。
- 双方版本集合不同但有交集、层范围部分重叠、集合顺序不同及重复版本，验证最终交集、顺序和去重结果。
- 双方版本或层范围没有交集，验证错误、事务回滚及呼叫状态。
- 接听后和确认后分别重试原始发起、接听、确认请求，验证成功结果、稳定身份以及事件和 pts 行为。
- 冲突的协议、被叫及密钥参数，多设备接听竞争、终态后重试及服务重启。
- 接听时关闭 P2P，覆盖仅 STUN、有效 TURN 和双方能力不同的情况。
- 继续运行对应 JSON 和 PostgreSQL 回归，确认共享逻辑一致性。

## 二、提供能升级指定既有隔离数据库的离线入口

### 当前根因

交付说明给出的 `BOTGRAM_MIGRATION_TESTS=1 dotnet run --project Storage.Tests` 是快照迁移回归命令。

`Storage.Tests/Program.cs` 先创建随机 `botgram_test_intake_*` 数据库，随后对该临时库运行测试，最后删除该库。它无法升级连接配置中指定的既有 v7 数据库。

`PostgresBackendSchema.ApplyAsync()` 是实际 schema 迁移 API，启动路径通过 `VerifySchemaAsync()` 检查精确版本集合。当前任务需要可执行、可审阅的离线维护入口及对应说明。

### 实现要求

- 先检查现有 `Storage.Admin`、`FourComponentSetup` 和维护工具，复用适合的入口或扩展现有命令。
- 工具显式读取目标连接配置，对指定的既有数据库调用 schema 迁移 API，并输出非敏感的目标及版本预检查结果。
- 保留当前回环地址和 `botgram_test_*` 范围校验，遵循仓库当前对关系存储的使用约定。
- 明确区分预检查和实际应用。执行前核对当前 schema 版本、目标身份、运行读写者及适用迁移路径。
- 使用现有或适当补充的离线所有权与并发保护，验证仍有后端或四组件读写者运行时的行为。
- 在事务内执行迁移，失败保留旧版本和原数据。重复执行应安全，并报告迁移前后的真实版本。
- 对指定数据库原地升级，保留账号、有效会话、TL 身份、聊天、消息、隐私、验证器、恢复码和 pts。
- 命令成功后检查 schema 及当前后端的启动校验。日志保护数据库凭据、密钥环和用户数据。
- 提供 PowerShell 下实际可执行的命令示例，说明配置文件、预检查、备份要求、应用、验证及失败处理。明确实际支持的起始版本和目标版本。
- 将 `BOTGRAM_MIGRATION_TESTS` 命令保留为回归测试入口，在文档和构建记录中准确标注其用途。

### 必须补充的验证

1. 准备真实 v7 结构的独立隔离数据库，写入代表性的既有业务记录并保存非敏感校验摘要。
2. 经新增维护入口对该指定数据库执行 v7→v8，证明升级的是同一个数据库，记录和稳定身份保留。
3. 重复执行、已经是目标版本、不支持的版本、目标范围非法、存在活动读写者及并发执行。
4. 制造实际迁移失败，验证事务回滚和原数据、schema 版本保留。
5. 使用迁移后的数据库通过当前后端启动检查，并验证登录、隐私、验证器数据兼容和原生通话状态读取。
6. 运行现有快照导入导出和迁移回归，将其与指定数据库的 schema 升级证据分别记录。

## 验证与交付

- 运行本次针对性回归，以及相关聊天、身份、隐私、通话和存储检查。
- 运行带 `--android-root` 的协议登记检查，保持客户端与后端协议快照逐字节一致，按实际修改运行生成代码和真实 TL 编解码验证。
- 源码修改完成后构建受影响的交付变体，对最终发布 DLL 复跑正常 Protobuf 入口回归。
- 按仓库约定同步后端发布目录、客户端 `artifacts/backend`、ZIP 和构建记录，核对实际文件集合及哈希。
- 更新 `artifacts/build-info.json`，准确记录本次实现、回归结果、既有数据库迁移命令、交付路径和实际部署状态。
- 最终报告逐项给出根因、修改文件、可执行命令和验证证据，并列出仍待外部环境或设备验收的项目。
