# P0 基线测试结果

> 2026-11-02 续测：保留原始基线记录；最新边界、宿主与构建状态见文末，历史“仍待”不代表当前状态。

日期：2026-11-02。范围：旧 JSON RPC 的隔离后端测试与请求/响应留样；不表示 Protobuf 迁移完成或 P0 全覆盖通过。覆盖明细与缺项见 `PROTOBUF-RPC-COVERAGE.md`。

## 本次直接执行

后端原始 HEAD：`67d5806`，开始时工作树干净。只修改 `tests/smoke.mjs`、`tests/gid.mjs`、`tests/contacts.mjs`，未改业务、未部署、未访问生产服务或生产库、未下载依赖。

| 检查 | 实际结果 |
|---|---|
| `node tests/smoke.mjs` | PASS；补齐 resolveUsername 正/负例、RPC 无认证和 logout 后拒绝；加强关键错误正文断言后再次运行 PASS |
| `node tests/gid.mjs` | PASS；Vector fixture 从手工组装改成真实 getUsers RPC 响应 |
| `node tests/contacts.mjs` | PASS；保留既有联系人/隔离/回滚/重启/迁移断言，新增成功与失败请求响应留样 |
| `node tests/m49.mjs` | PASS：固定快照 2026-11-01、SHA-256、政策、29 区域、249 国家映射，离线 |
| `node tests/tl-schema.mjs` | PASS：56 个 Gateway 响应类型与当前 Android 源码匹配；这是静态契约检查，不是二进制往返 |
| `git diff --check`（后端） | PASS；仅提示既有 Git LF→CRLF 工作副本转换警告 |
| fixture 结构/安全核查 | 83 条均含 request/response；requestType 与 request.$type 一致、status=expectedStatus；扫描未发现 authorization/bearer/password/token 关键字 |

Node：`v24.15.0`。使用既有 `bin/Debug/net8.0/Botgram.Backend.dll`，本次未重新构建 .NET。该 DLL SHA-256：`2c91b72afe6ad1fc04c4ab438a0b86a6368ded20b15d9037b8297410c7b4a28f`。

.NET 子进程环境：`DOTNET_CLI_HOME=D:/BotgramBuild/dotnet`、`NUGET_PACKAGES=D:/BotgramBuild/nuget`、`TEMP=TMP=D:/BotgramBuild/tmp`、关闭 CLI 遥测。清除外部 BOTGRAM_TEST_DLL/BOTGRAM_TEST_ROOT 覆盖，测试自行分配 127.0.0.1 临时端口与仓库 obj 下的独立临时库，重启仍只操作各自测试库。

## 本次生成的真实 RPC 证据

路径均相对 `W:/Work/Botgram.Backend/`。文件在 obj，不提交隔离库或凭据；每次运行会覆盖对应 fixture，用户/GID/时间会变化，以下哈希只对应本次样本。

| 文件 | 总数 | HTTP 200 | 非 200 | SHA-256 |
|---|---:|---:|---:|---|
| `obj/smoke-rpc-fixtures.json` | 38 | 30 | 8 | `b8f869153c121163061b7d9396778583f1a1ce897998756091a62e99ff120de9` |
| `obj/gid-rpc-fixtures.json` | 3 | 3 | 0 | `f42ee68c82f387962b120b21aa9bb3bd6cc0bbc4aea1b22102736e53336ff96d` |
| `obj/contacts-rpc-fixtures.json` | 42 | 26 | 16 | `1cab61d15985bf030692094217cb30b926e6ba9ad25c2d94838d6979c8a75046` |

合计 83 条，59 条成功、24 条错误。非 200 分布：400×15、401×4、403×1、404×1、409×1、501×2。21/21 规范 RPC 有成功入口样本；仅 7/21 有非 200 样本，外加 deleteMessages 的 unsupported 501。不能据此写成 P0 全覆盖通过。

错误正文真实样本包括 `USERNAME_NOT_OCCUPIED`、`unauthorized`、`session_expired`、`SEND_AS_PEER_INVALID`、`RANDOM_ID_DUPLICATE`、`REPLY_MESSAGE_ID_INVALID`、`PEER_ID_INVALID`、`PHONE_NOT_OCCUPIED`、`PHONE_NUMBER_INVALID`、`FIRSTNAME_INVALID`、`CONTACTS_TOO_MUCH`、`CONTACT_INVALID`、`BOTGRAM_CONTACT_NOTES_UNSUPPORTED`、`BOTGRAM_RPC_UNSUPPORTED:TL_messages_deleteMessages`。其中只有覆盖文档列出的关键新增字符串作严格正文断言，不能把记录等同于逐条语义断言。

## 主执行 agent 的实际验证与已确认约定

以下为主执行 agent 本轮实际执行状态；本子任务未重复执行这些宿主脚本：

- 三个宿主脚本改为 GUID 独立输出子目录后全部通过：Auth **6**、ServerAddress **34**、TL smoke **29** + gid **2**。宿主验证不代替 Android 构建或真机测试。
- `Transport.send` 已改为进入 requests 队列前捕获 final server/bearer 字符串，不再在队列内读取可变字段；**仍待 Android 构建验证**，不能据此宣称账号切换/迟到回调端到端通过。
- 用户明确批准使用现有 `User.Id` UUID 直接作为稳定 `user_id`；不得因连接地址变化或协议迁移重新分配身份。
- `server_domain` 由部署者显式配置为自己服务的稳定身份域名，不含协议和端口，不随连接地址变化；项目不指定公共服务器，隔离测试使用独立测试域。这是已批准契约，不表示本次后端 JSON 测试已覆盖该字段或已完成业务实现。

## 未验证与后续门槛

- Protobuf schema/编码/解码、bytes 表示、int64/默认值/未知字段/类型、与旧 JSON 的差分及兼容回退仍未验证。
- 固定 server_domain 与稳定 UUID user_id 的跨协议实现、持久化兼容及测试仍需后续落实；当前 TL 数字 ID fixture 不能替代新契约验收。
- Android 编译仍待完成；安装、真机原页面、切换账号、取消/迟到回调、失效账号禁止回落 Telegram 原生网络均不能宣称本次已通过。
- 21 方法的全部错误/权限边界、差分超过 100 条切片/TooLong、实体与 UTF-16 全组合、所有历史分页参数未穷举。
- 不支持的附件、原生 phone.* 与后台推送不在通过范围；REST 通话信令测试不是原 Telegram 通话端到端验证。

结论：旧 JSON 21 个规范入口的可追溯请求/响应基线已补齐，并保留真实错误状态及正文；P0 仍有上述明确缺项，不具备“全覆盖通过”或可发布声明。

## P0 边界续测实际结果（2026-11-02）

本子任务使用相同D盘环境、清除外部测试路径覆盖，实际执行 `node tests/rpc-boundaries.mjs` 及 `node tests/smoke.mjs`，均exit0/PASS；既有Debug DLL未重建，无下载、部署或生产访问。边界套件启动随机loopback端口，独立`obj/rpc-boundaries-*`数据库，finally停服。只新增测试及smoke类型断言，不改业务。

| 续测证据 | 数量/结果 | SHA-256 |
|---|---|---|
| `obj/boundary-rpc-fixtures.json` | 156条：131成功、25失败，全部status=expectedStatus | `757e9dde8236483aa9c058824bef6b54a8f89363a19007bfbf66d1edf3a715e3` |
| `obj/boundary-tl-fixtures.json` | 131条真实成功响应；保留原始int64数字，待主agent宿主回读 | `0864141549eb72e1464f469fd8a5807e29eb3853b64d2346770b4fd077a70bde` |
| 重跑`obj/smoke-rpc-fixtures.json` | 38条：30成功、8失败；替代上方历史smoke哈希 | `f8bfd53e08931e13a96b9c2756b2059df4d02796878efb716ddfe2c395bed75b` |
| 后端`git diff --check` | PASS（LF/CRLF警告） | — |

边界覆盖：差分四分支、105事件100+5分页与重放/pts、long最小最大/0/-1原始数字及幂等冲突、15实体和UTF16非法边界、可选字段flags与unsupported、非成员群操作与冒充拒绝、已读不回退。具体断言及未穷举边界见覆盖矩阵续测节。四套合计239条=190成功+49错误，21方法成功、10方法有非200，不代表P0全覆盖。

主执行agent本轮实际执行并确认：新版`TLCodecSmoke`对smoke30+gid2+contacts26真实序列化/重新解析PASS，生成对应`obj/*.tl-bytes.json`，共58份含requestType/responseType/base64的真实TL二进制；SessionScope PASS；`Tools/Build-Botgram.ps1`默认`:TMessagesProj_AppStandalone:assembleAfatDebug`已exit0。因此历史“Android编译仍待”已解除，但没有新APK交付、安装或真机验证。此处58份是此前样本的宿主执行结果，不能自动归属于此次重跑后变化的smoke内容，也不含新增boundary131份。

后续主执行已完成新增 boundary 131 份真实 TL 序列化/重新解析，与此前 58 份合计 189 份通过；该结果对应当时样本，不自动归属于后续重跑生成的 fixture。未实现 Protobuf，不声明 JSON↔Protobuf、bytes、全字段/权限组合或手机端到端通过。

## P1 共享业务与持久化续测（2026-11-02）

- 最新源码 dotnet build 通过，0 警告/错误；rpc-boundaries、contacts、gid、smoke 全部 PASS。
- 最新上述测试样本真实 Android TL 回读：boundary 131、smoke 30、gid 2、contact 26，共 189 个通过。联系人首次调用误用了不存在的复数文件名，修正为实际 contact-tl-fixtures.json 后通过；不是解码失败。
- typed-persistence.mjs 新增并实际执行/复跑 PASS：手工旧 JSON 快照全部 15 类有序实体、6 类强类型事件，UTF-16 offset/length、language/url、null/空数组/字段缺省保持；用户/会话/关系/数字身份/计数游标不变；成功持久化及跨进程重启一致；Store.Write 回调异常、磁盘临时文件写入失败均完整回滚内存且保持原磁盘内容。
- 静态 tl-schema PASS（当前源码可直接识别 41 个响应类型；不能把数量变化视为完整 schema 覆盖），m49 离线校验 PASS。后端 git diff --check PASS，仅有 LF/CRLF 警告。
- 上述首批结束时 ContactService 和 ConversationMessageService 已承接共享业务，Gateway 尚有其他查询；后续收拢结果以下节为准。Protobuf schema/入口/Android 映射仍未实现，未新增 APK 交付或真机测试；未部署或访问生产库。

## P1 查询收拢最终回归（2026-11-02）

- 新增安全 UserProfile/PublicUserResult、DialogQuery/DialogPage/MessageView/PeerDetails，以及 typed EventPage；旧 Gateway 不再直接查询业务集合，REST 创建/精确已读/列表和 WSS 分页复用服务。业务服务不依赖 JSON DOM；JSON 仅保留在旧适配及存储边界。
- 新增 smoke 断言：offset_peer/offset_id 优先级与回退、peerDialogs 选择/去重/隔离、非零 folder 与 pinned 空结果、getUsers 重复/未知 ID、公开字段不泄漏盐/摘要/token、REST 列表 keys/lastMessage/unread/receipts/隔离。
- 最终源码重新 build：0 警告、0 错误。smoke、rpc-boundaries、contacts、gid、typed-persistence、tl-schema、m49、forwarded-headers 全部 PASS。
- 最新 smoke fixture：48 次 RPC（40 成功、8 预期错误）。最终四套真实 Android TL 回读：40 + 131 + 2 + 26 = 199 个响应通过；不沿用历史样本哈希描述本次产物。
- P1 共享业务提取及相关本地回归完成；未执行旧二进制回退演练、真机或所有字段组合穷举。该记录时 P2 尚未启动；后续 P2 进展见下节。无部署、生产库操作或新 APK 交付。

## P2 schema 与隔离工具链（2026-11-02，阶段验收完成）

- 新增强类型 proto，21 个请求/响应 tag 为 100—120，error=10；含 UUID+server_domain、15 类 UTF-16 实体、普通/服务消息、五种更新及四种差分。尚无 HTTP 入口/Android Mapper，也没有运行时输入校验器。
- 后端 Protocol/botgram_rpc.proto 与客户端 Shared/botgram_rpc.proto 是各自独立的相同副本，SHA-256：`0ae93af8d5be2ad236d0a63bf77451e08788fdc99498c92a2c0344d4dd716b93`。
- 已执行 Tools/Verify-BotgramProtobufAndroid.ps1：生成 Java lite 与 runtime 3.25.8 通过 Java 8 目标编译、D8/R8 release min-api 23。隔离 dex 大小分别为 1,152,092 / 617,320 字节；不是 APK 体积增量，也不是设备 API23 运行结果。R8 保留全部协议类型、不混淆；不代表真实 App 混淆规则已验证。
- 现有后端 `dotnet build --no-restore --nologo` 通过，0 警告/错误；测试项目源码排除在现有 Web 项目之外。
- 主执行实际复核 `tools/protobuf.ps1 -Action Check` PASS：protoc 25.8、Java protobuf-javalite 3.25.8、Google.Protobuf 3.25.8 三项下载 SHA-256 与锁文件一致；重复生成 Java/C# 逐字节相同。哈希来自初次 HTTPS 下载固定，未独立验证上游签名，不能宣称供应链签名验证通过。
- Java → C# → Java 共 66 组二进制样本 PASS：21 个方法请求/响应 tags、15 类实体/emoji UTF-16、signed64 极值、optional 缺省/显式零、四差分、五更新、普通/服务消息、手工 golden hex、未知字段/operation 保留及 NOT_SET、截断拒绝。兼容性使用隔离旧/新测试 schema 验证加字段、未知枚举和跨 runtime 回读；不代表两个已发布 App 版本的业务兼容验收。
- 最新主执行样本目录：`D:\BotgramBuild\protobuf\tests\24b884ad0e164dd397bd66e43385c279`。P2 仅测试 schema/runtime，尚无 unknown operation 的 HTTP 拒绝实现；身份空值和跨域样本仅验证承载，不证明业务允许。
- 主执行实际运行 `node tools/verify-protobuf-contract.mjs` 及 `--android-root W:/Work/Botgram` 两种模式均 PASS：74 个 message、21 对请求/响应 tags、mapping 目标、全部请求继承字段和嵌套历史子类覆盖、两仓 schema 逐字节相同。独立后端模式不读取 Android；该检查不证明业务 validator 已实现。
- 字段策略语义复核已修正：显式 self sender 验证后忽略，替代 sender 为 403；同会话 reply peer 验证后忽略，跨会话为 501；搜索 limit 与 pinned folder 保持旧忽略行为；联系人非零 flags 保留拒绝约定。它们是冻结的后续映射约束，本轮没有修改旧入口或运行时验证逻辑。
- 字段策略收尾后后端再次 build PASS，0 警告/错误。P2 schema/工具链阶段验收完成；当时 JSON↔Protobuf 业务差分、HTTP 权限与请求边界执行、APK 集成/应用混淆及真机端到端均未验证。此为 P2 历史记录；P3 结果见下节，未改变 WSS、生产服务或交付 APK。

## P3 后端入口及隔离验收（2026-11-02）

### 实现及主执行验证

- 新增 `POST /api/v1/rpc`，Protobuf 强类型解析、运行时输入校验与 21 个操作映射，直接调用共享业务。旧 JSON RPC 继续保留，未切流。详细设计见 `PROTOBUF-IMPLEMENTATION.md` 的 P3 节。
- 主执行实际完成 Debug build，0 警告/错误；smoke、gid、contacts、rpc-boundaries、typed-persistence、m49、tl-schema、forwarded-headers 均 PASS。contacts 26 份及 boundary 131 份是原 JSON/TL fixture，不代表 Protobuf 到 Android TL 的新回读验证。
- `node tools/verify-protobuf-contract.mjs --android-root W:/Work/Botgram` PASS：74 messages、21 tags、字段/子类登记、两仓 proto 字节一致。
- `tools/protobuf.ps1 -Action Check` PASS：三项工具下载哈希、重复生成及纳入源码 C# 字节一致、Java8 编译、66 组 Java→C#→Java 样本。最新样本目录 `D:\BotgramBuild\protobuf\tests\ddd74faf417d47c5a770132df4d7cefa`。此前固定 Protocol.Tests 输出拒绝写入已通过唯一 obj/bin 目录解决，未删旧输出或改 ACL。
- locked restore 和 Release publish PASS，本地输出 `D:\BotgramBuild\protobuf\p3-publish`；仅验证发布产物生成，未部署，也未将下述 HTTP 测试描述成对该 Release 目录的测试。后端 `git diff --check` PASS，仅 LF/CRLF 提示。

### HTTP 测试代理实际执行结果

测试位于后端 `Protocol.HttpTests/`。测试代理执行以下命令，报告编译 0 警告/错误及 **PASS：1886 断言、21 操作**：

```powershell
dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --nologo --no-restore -v:q
dotnet D:/BotgramBuild/protobuf/http-tests/bin/Debug/net8.0/Protocol.HttpTests.dll
```

使用 D 盘 CLI/依赖/TEMP 环境，随机 loopback 端口及隔离域 `isolated.test`，自启进程均停止；不使用正式库。最终隔离证据目录：`D:\BotgramBuild\protobuf\http-tests\runs\d4959f8a5bb945aea497690a19a51834`。目录包含隔离账号摘要及会话数据，不交付或提交这些数据库。

- 从同一注册及稳定 TL ID 基线复制两份数据库，JSON/Protobuf 逐步执行相同场景。每步比较显式业务字段投影和完整持久化快照；归一化独立生成的会话 UUID，以及验证过的业务时钟字段，基线会话到期时间保持精确比较。不是逐 wire 字段比较，也不是无归一化逐字节数据库比较。
- 覆盖全部 21 操作、signed64 最小/最大值、重复发送和冲突、回复、群服务消息、联系人导入备注、用户/群详情、回执、GID 更新及差分。105 条新增消息按 100+5 分页，无重复并推进到 Empty。
- 覆盖无认证/失效/退出、未知 major/operation、空/非法 request_id、畸形/截断/递归过深、媒体类型/压缩拒绝、固定长度及 chunked 超限、跨域、未知实体、emoji 半代理边界、消息 ID 溢出、limit 缺省/显式零、重复列表、非成员群无状态变更/无正文泄漏、429。
- Windows 在数据库 `.tmp` 路径注入目录，真实响应为 **500 BOTGRAM_STORAGE_FAILURE**（UnauthorizedAccessException），原磁盘快照保持；移除注入后同键重试只执行一次。固定长度超限为 **413 BOTGRAM_REQUEST_TOO_LARGE**，chunked 超限为 **413 BOTGRAM_REQUEST_INVALID**。
- 可控取消上传后同键重试及重启后重试均无重复；取消不代表请求一定未提交。旧 JSON getMessages 刻意使用 `{id:id}` 对象列表，不证明真实 Android 整数列表旧入口正常；Protobuf 入口直接使用整数 ID。

### 复核限制与阶段边界

- 历史主执行复跑曾在沙箱内及解除 D 盘写入限制后返回 **-532462766 = 0xE0434352**（CLR 未处理异常），无诊断输出。不能简单归为网络或工具偶发；2026-11-02 补证见下节：主执行独立复跑已 PASS，但历史异常所属进程及调用栈仍未定位。
- 本轮没有独立验证 8 MiB 响应上限的真实超限场景、全部字段/权限组合、Docker build、新旧发布二进制回退、Android Mapper/APK 集成/应用混淆、设备 API23 或手机端到端。schema 字节往返和原 JSON TL fixture 不能替代 Protobuf→Android TL→真实页面验证。
- P3 服务端实现、代理隔离测试及主执行 HTTP 独立复跑完成；历史 CLR 异常根因仍开放，不宣称全矩阵验收或可发布。未进入 P4、不部署、不操作生产库、不提交 Git、不移除 JSON。生产 TLS、HTTP80/ACME 风险未由本阶段解决。

### CLR 异常调查与主执行独立复跑补证（2026-11-02 02:55–02:58 +08）

- 按 Application 日志的 `.NET Runtime` / `Application Error` 提供程序精确查询，而非只按 1000 ID（存在大量其他提供程序同号事件）。近三天无对应崩溃记录；最近 1026 是 10 月 22 日 CrossDeviceService，非本测试。当天精确查询结果保存于 `D:/BotgramBuild/protobuf/clr-http-rerun/events.json`。没有拿到本次异常类型或栈，因此历史工具宿主、测试启动器及后端三种归属均 INCONCLUSIVE；不能以本次成功倒推历史原因。
- SDK 仍为 8.0.204，沿用原 D 盘 CLI/NuGet/TEMP 环境、Debug 测试 DLL、隔离域与随机 loopback 端口。未修改产品业务逻辑、依赖或系统 ACL。首先以 Node 子进程直接执行原 DLL：PID 33584，02:56:26–02:56:32，**exit 0，1886 断言/21 操作 PASS**；证据为 `run.json`、`test.stdout.log`、空 `test.stderr.log`。
- 为满足完整日志要求，测试不再丢弃后端 stdout/stderr，按 PID 保存至本次隔离目录，并输出启动/停止 PID、退出码及是否由测试停止；等待流排空。不记录请求体或 Bearer。重新编译 **exit 0，0 警告/错误**（`build.log`），再经 Node 执行 PID 3776，02:57:19–02:57:25，**exit 0，1886/21 PASS**（`logged-run.json`、`logged-test.stdout.log`、空 stderr）。
- 最后恢复原 PowerShell 直接执行路径（非 Node）：宿主 PID 48068，02:57:34 开始，**exit 0，1886/21 PASS**；完整输出 `direct-test.log`。5 个后端 PID 45316、34928、46456、48264、33324 均 `stoppedByTest=True`、`exit=-1`：这是现有测试 Kill 清理行为，不是自然退出或 CLR 崩溃。其 stdout 日志无 Unhandled/Exception/fail，stderr 无输出；不将预期的 HTTP 错误响应视为进程异常。
- 上述构建、父进程日志与退出码位于 `D:/BotgramBuild/protobuf/clr-http-rerun/`；最终隔离数据库与后端日志位于 `D:/BotgramBuild/protobuf/http-tests/runs/012a031f051745778ff1a0d91fc281c7/`，Node 日志版运行目录为 `f439cc7cad1e4de1be9bab6b655b0bde`。数据库含隔离账号/会话，禁止提交或交付。保留原始日志，不用“偶发”替代根因结论。
- 结论：主执行独立复跑缺口已补齐；历史 0xE0434352 未复现、根因未闭环。若再次发生，需以对应时刻 PID、标准错误和 Windows 崩溃事件/转储继续追踪，当前不作无证据业务修复。

## P4 Android 映射验收进展（2026-11-02，尚未完整验收）

- 显式请求/响应 Mapper、会话级身份缓存和 typed HTTP RPC 已接入源码。用户批准新增鉴权 transport-only tag121，专用于既有 TL ID→UUID 解析，不改变原21业务tag，不分配身份或更改持久化模型。后端解析群组时验证成员；self/未知/缺失映射及重启稳定性有专项测试。
- 后端当前源码主执行独立build为0警告/错误，HTTP套件exit0、2033断言通过（21业务操作及transport-only身份bootstrap），完整日志`D:/BotgramBuild/tmp/p4-http-current-final.log`。首次直接运行既有DLL仅1886项，发现是旧测试产物后重新build当前项目再验证2033项，不把旧DLL当本轮证明。主执行随后独立复跑m49/gid/smoke/contacts/rpc-boundaries/typed-persistence均exit0（边界回归156次RPC、131成功TL样本）；主执行`tools/test-protobuf.ps1 -SkipToolchainCheck`校验固定下载物哈希并完成66组Java lite↔C#双向二进制样本，exit0（D盘独立run `38d0f25023d64c8aa15258e03e52c18c`）。后端结果不代替Android APK验收。
- 主执行实际运行 `node tools/verify-protobuf-contract.mjs --android-root W:/Work/Botgram`：PASS，76 messages、21 TL operations + 1 transport-only tag，继承字段/子类清单与两仓proto字节一致。
- 主执行实际运行 `Tools/Verify-BotgramProtobufMapping.ps1 -CompileMapperSources`：exit0，203断言PASS（独立复跑最新扩展版）。真实generated protobuf bytes经生产ResponseMapper构造原TL对象，按对应原请求解码并再次序列化，校验字节稳定；覆盖21响应、15实体、4差分、5更新、普通/服务消息、sfixed64极值、超安全整数身份、UTF-16和非法输入。扩展测试覆盖HTTP媒体类型/状态/major/request_id/outcome错误、畸形Protobuf、每次仅一次请求且无JSON回退、active失效与独立Identity用户/群缓存隔离。随后主模块Android Java编译exit0，再以默认模式复跑203断言同样exit0，使用本轮Gradle实际编译的Mapper。loopback HttpServer用于bootstrap及HTTP信封测试，不是连接真实后端的全链路测试。
- 主执行实际运行 `Tools/Verify-BotgramProtobufAndroid.ps1`：旧固定class输出写入失败后改用GUID独立D盘目录，再执行exit0。最新 `D:/BotgramBuild/protobuf/android-check-6f421d55c54c473bbc549ae76038e62e/`：Java8、D8/R8 release min-api23通过，dex分别1,167,728 / 631,724字节。隔离R8保留全部协议类型且不混淆，不是应用APK体积或实际混淆规则验证。
- 主模块Java编译后，主执行认证隔离宿主回归exit0（6项断言）；既有TL/GID/contacts/boundaries fixture回归分别40/2/26/131响应解码、序列化及重新解析通过。
- 主执行服务地址宿主回归exit0（34项）；SessionScope宿主回归exit0：旧UI/storage任务、独立generation、active-task drain、同步查询、传递cleanup及context restore通过。不是新typed传输在真机账号切换/取消/迟到回调的端到端证明。

### P4 首轮真机反馈与修复中（2026-11-02）

- 用户真机确认：隔离服务 health 可达；注册进入会话列表、Bob 注册/切换账号、搜索 Bob 通过。
- 用户真机失败：找不到收藏夹；Alice 首次打开 Bob 持续加载，输入消息发送后消失，双向消息/引用回复未通过。不能把此前宿主或构建通过视作设备通过。
- 静态确认并修复：`MessagesController` 在 getPeerDialogs 成功但 dialogs 为空时继续 getHistory，避免首次 direct/saved 会话加载链悬空；发送映射不再拒绝原 SendMessagesHelper 默认设置的 clear_draft。字段政策明确草稿仅在客户端维护，忽略该标记不代表云草稿同步；其他不支持的发送选项继续拒绝。
- 在原 SettingsActivity 添加常驻“我的收藏”项，复用现有资源、自身账号 ID 和原 ChatActivity；原聊天页更多菜单入口保留。是否在手机显示及自发消息仍待新 APK 复测。
- 修复后的源码编译宿主映射实际通过 **212** 断言，包含 clear_draft=true/false 的同义发送、不支持选项仍返回 501、空 peerDialogs/空历史的真实 TL 序列化及回读；不执行 MessagesController 回调链，不能代替首次会话 UI 复测。这是 `-CompileMapperSources` 临时模式，不代表已用新 Android 编译类或手机执行。
- 字段契约验证通过：21 TL 操作、76 消息，Android 字段/子类清单完整，双端 proto 一致。本轮另行复跑认证 6 断言、服务地址 34 断言、SessionScope、原 TL 样本 40/2/26/131 全通过，均为宿主验证。默认构建再次遇到既有 Gradle daemon 日志/FileAccessTimeJournal 拒绝访问；正在复用此前 D 盘隔离缓存及临时库输出方案离线构建，新交付结果尚待记录。
- 后续复用 D 盘隔离缓存离线构建 `assembleAfatDebug` 已 exit0；默认模式使用新 Android 编译类复跑 **212** 断言通过。新 APK 验证 v1/v2 签名、me.nyarugram.messenger、Nyarugram、13.1.2/72389、minSdk23/targetSdk36、四 ABI，通过后覆盖交付 `artifacts/android/Nyarugram-debug.apk`，源/交付 SHA-256 均为 `43AB36D048A46303161EA55968F13C2B9E30868770FD4678ED2D15DD71E7396D`。本次不新增资源、不升版；Standalone 未重建，下面旧 Standalone 验证不包含本次修复。
- 未重装/清除手机数据，未触碰正式库，未部署正式服务。请覆盖安装新 Debug，用现有 Alice/Bob 复测设置→我的收藏、自发文字、首次打开对方聊天、双向文字及引用回复；修复后真机尚未确认，P4 尚未验收通过。

### P4 列表兼容与失败收尾修复（2026-11-02）

- 用户确认上一版收藏、自发文字、Bob 注册/切换、搜索、双向消息和引用回复正常；新增问题是主页尾部持续闪烁骨架行。
- 原列表控制器默认发送 exclude_pinned=true，Protobuf 请求映射错误返回 501，且原失败分支未释放 loadingDialogs。现按后端无置顶项的实际语义兼容该标记，并同步字段政策；未实现或宣称置顶能力。
- 失败回调在 UI 线程释放对应 folder 的加载标志并通知 dialogsNeedReload；不推进分页、不把失败标为到底、不主动立即重试，不删除正常分页骨架行。UI 任务沿用既有 SessionScope 隔离。用户断网/恢复、失败后重试和最新首页状态仍待真机确认。
- 双端契约检查 PASS：76 messages、21 TL + 1 transport-only tag、Android 字段/子类清单和 schema 字节一致。源码编译宿主映射 **215** 断言 PASS，新增初始列表 exclude_pinned=true、true/false 等价、分页偏移和 peer 保持断言；宿主不运行原列表控制器 UI。
- SessionScope 宿主回归 PASS；认证隔离 6、地址检查 34 断言 PASS；两个仓库 git diff --check PASS（只有已有 LF/CRLF 提示）。未改后端业务或重启隔离服务，保留现有测试账号和数据，正式服务未部署。
- D 盘隔离缓存离线 assembleAfatDebug exit0；默认宿主模式以本次 Android 编译 Mapper 复跑 **215** 断言 PASS。apksigner v1/v2 PASS（保留工具提示的 META-INF 元数据未受签名保护警告）；aapt 确认 me.nyarugram.messenger / Nyarugram / 13.1.2 (72389)、minSdk23/targetSdk36、四 ABI。本轮无新增资源、不升版；Standalone 未重建。
- 已覆盖交付 artifacts/android/Nyarugram-debug.apk，源与交付 SHA-256：`D09472F2625F810DBAE37DC7B5527F0BBEABA6818921F9484251E1C0A22D8312`。请覆盖安装后完全退出并重开，用现有账号检查首页加载及滚动到底；列表修复和失败后恢复仍未真机验证。

### P4 离线标题与自身资料状态修复（2026-11-02）

- 用户关闭 Wi-Fi/移动数据冷启动后反馈主页仍为 Telegram、Alice 自身资料仍为在线；该反馈不是断网恢复测试通过。
- BotgramTransport 接收既有系统网络广播、启动及 Activity 恢复网络快照；无网络返回 WaitingForNetwork，恢复网络先 Connecting，收到后端响应后 Connected。启动强制通知，同状态通知也重新应用原标题覆盖；既有 3 秒轮询检查 30 秒可达状态到期，避免状态无通知地变旧。
- 自身资料普通/展开两处文案读取连接状态，订阅 didUpdateConnectionState 并在销毁时注销。其他用户状态不变；Connected 仅表示近期传输可达，不是服务端 presence。复用已有中英文资源，无新资源、无版本变更。
- 本轮 Debug `:TMessagesProj_AppStandalone:assembleAfatDebug` 构建 exit0；实际 Android 编译类 Mapper 215 项、认证登出 6 项、服务地址 34 项及 SessionScope 宿主回归 PASS。
- apksigner 验证 v1/v2 签名通过；aapt 确认包名 `me.nyarugram.messenger`、名称 Nyarugram、版本 13.1.2/72389、minSdk23/targetSdk36 及 arm64-v8a、armeabi-v7a、x86、x86_64。签名仍为仓库开发证书，保留既有 META-INF 元数据警告。
- 最新 Debug APK 已复制至 `artifacts/android/Nyarugram-debug.apk`，源产物与交付 SHA-256 一致：`9A98DB6EF7CBB8E1357B20AAE602D66CCDC091F3DB3CA6FD456515D63FD21A5B`；build-info 当前 Debug 哈希及本轮验证记录已同步。未重建 Standalone、未部署后端、未改账号数据。
- 尚未真机验证；待覆盖安装后检查断网冷启动、资料页内断网/恢复更新、不重启恢复收发、主页骨架和账号切换。上述构建与宿主回归不能代替这些设备验收。

## P5 协议取证准备（2026-11-02，未完成阶段验收）

- 后端新增 `Protocol:AuditRpc`（默认 false），仅记录两种 RPC 路径的协议、固定枚举 operation、HTTP status、elapsed_ms、completed/aborted。覆盖认证失败和旧入口拒绝；不记录 Bearer、账号、request_id 或消息正文。异常未完成时 status 可能尚未更新，必须同时读取 completed/aborted，不能将此类默认200视为成功。
- `Protocol:LegacyJsonRpcEnabled` 默认 true，保留旧客户端兼容；隔离环境显式 false 时，认证后的旧 `/api/telegram/rpc` 返回410 `BOTGRAM_JSON_RPC_DISABLED`，含大小写及尾斜杠变体。登录/注册、`/api/telegram/me`、GID REST 和 WSS 不在禁用范围。未修改正式实例配置或客户端/APK。
- 主执行实际运行 `dotnet run --project Protocol.HttpTests/Protocol.HttpTests.csproj --no-restore`：首次测试新增断言误把 TextMessage 当 string，编译失败；改为读取嵌套 text 后复跑 exit0，2062断言，21业务操作+身份bootstrap。两协议使用同一基线的独立数据库副本比对语义及持久化，不对同一数据库重复双写。原分页、重启、权限、存储失败与取消/幂等测试随套件执行。
- 新增禁用专项：两个旧JSON写请求均410且数据库不变；Protobuf GetState/SendMessage/GetHistory成功，写入仅一条；旧REST仍可用；无认证仍401。审计日志自动断言相应协议/操作/状态，并检查无令牌或消息正文。该证据来自宿主HTTP测试，不是真机。
- 本次隔离证据根：`D:/BotgramBuild/protobuf/http-tests/runs/486f78ae4b694d9c9b29baac0522a160`。`backend-12260.stdout.log` 包含 JSON410×2、Protobuf未认证401、GetState200、SendMessage200、GetHistory200。单次耗时仅可追踪，不代表性能灰度或错误率门槛通过。后端 `git diff --check` 通过（既有换行转换警告）。
- 真机步骤：保留既有Alice/Bob测试库，确认恢复后才启动原18524隔离端口；设置 `Protocol__LegacyJsonRpcEnabled=false`、`Protocol__AuditRpc=true`，日志级别保留 Information。先验证旧RPC410，再由手机执行双方发送/回复、历史、群聊、联系人/GID及多账号/前后台；按时间窗口核对 SendMessage、GetHistory、GetDifference 等200记录，并验证消息唯一。仅 ResolveIdentities 成功或宿主计数不足以证明手机业务链路。
- 当时阻塞（已由下方续测状态覆盖）：此前真机隔离服务已退出，系统报告原 `D:/BotgramBuild/tmp/p4-device-20261102/device-db.json` 被移入回收站；未尝试自动恢复，也未创建同路径空库。后续经用户授权用新隔离目录恢复18524服务，旧库仍不可恢复；不能将新账号验证当作旧库迁移验证。P5仍不标记完成，正式服务未更改，不进入P6/P7。

### P5 能力、灰度与黑洞续测（2026-11-02）

- 历史状态（已由下方本轮实现覆盖）：能力协商 **NOT IMPLEMENTED**：`BotgramApi.rpc` 固定 `POST /api/v1/rpc`，major/outcome校验是信封校验，不是服务端能力广告/协商。当前无旧服务能力缺失、新旧major选择或协商缓存的运行时验收对象；不得将硬编码Protobuf或无JSON回退记为协商通过。
- 历史状态（已由下方本轮实现覆盖）：会话灰度/回滚 **NOT IMPLEMENTED**：现有会话scope隔离不等于协议选择。未实现按会话固定协议、停止新会话Protobuf准入、保留在途协议并在新会话选回旧协议的控制；禁用JSON开关只是服务端入口拒绝，不是灰度。继续保留旧入口，不自动改协议重试写入。
- 用户已反馈18524真机“可以收发消息”。本轮只读核实health=200，未重启该实例或读取账号数据库。服务使用新隔离目录 `D:/BotgramBuild/p5-device-20261102-134314/`；stdout是历史UTF-8与PowerShell追加UTF-16的混合日志，使用共享读取并去NUL解析审计固定字段，不改原日志。
- 审计快照共281条：JSON410×1；Protobuf SendMessage200×5、GetHistory200×4、GetDifference200×227、ReadHistory200×5及400×2、GetState200×3，其余成功包括身份解析、会话、联系人和资料；另有未解析401×4。全部记录completed=True/aborted=False。主执行此前主动探针含JSON410、GetState成功和退出；计数不能全部归因手机，更不能把无身份/请求ID的审计精确关联某条消息。用户反馈加SendMessage/GetHistory日志支持基础手机收发链路，但400/401原因未解释，不能声称真机零错误或全21操作通过；未检查真实用户数据库中的消息唯一性。
- 新增可选 `Tools/Verify-BotgramProtobufMapping.ps1 -Blackhole`，执行既有Android编译的生产`BotgramApi`及Mapper，不修改生产超时。宿主loopback分别模拟完整接收请求后不返回响应头、返回200/Protobuf头及首字节后停止正文；使用latch解除黑洞，不更改防火墙/系统网络。
- 上述命令实际exit0，**223断言PASS**（原215+黑洞8）：两阶段均SocketTimeoutException，分别**20002ms / 20002ms**；每次仅一请求，无JSON回退、无内部重试；同一API对象随后请求成功。测试允许18–35秒调度窗口，验证生产20秒read timeout，不作为延迟性能预算。
- 黑洞通过范围仅为宿主HTTP响应头/正文读取超时与恢复；不覆盖连接建立/上传阻塞、持续滴流的总截止时间、真实Android网络切换、取消时限、WSS黑洞/退避或恢复后差分补齐，不能写成网络故障全矩阵通过。生产RPC源码未改，无新APK/部署。

### P5 能力协商、会话灰度与 ReadHistory 关联取证（2026-11-02）

- 新增 Bearer 认证及 API 限流下的 `GET /api/protocol/capabilities`，广告 negotiationVersion=1、protobuf-v1/tl-json-v1 和幂等/差分/WSS唤醒能力。`Protocol:ProtobufRolloutPercent` 默认100，允许0–100；对当前服务端会话TokenHash再做SHA-256确定分桶，不暴露哈希。禁用JSON且灰度不足100的配置启动拒绝。
- Android普通RPC先协商，再将选定协议绑定当前账号槽位的加密token保存；新登录及退出清除固定，旧凭据不能覆盖新token的协议。同一API对象缓存协商结果；账号间不共享。只有明确选中tl-json-v1才走旧入口，超时/错误不会切JSON或内部重发写请求。已经固定的会话不因灰度比例变化自动迁移；停止新Protobuf准入应保留旧入口，不强制切换在途写请求。
- 后端最终源码单独build通过（0警告/错误），已构建DLL独立执行HTTP回归exit0，**2257断言PASS**，含21业务操作及身份bootstrap。新增0/50/100%及JSON关闭配置的能力广告、鉴权、同用户不同token、稳定分桶和无持久化改动检查。运行证据：`D:/BotgramBuild/tmp/p5-build.stdout.log`、`p5-http.stdout.log`；隔离根`D:/BotgramBuild/protobuf/http-tests/runs/0f29079cc1c54c92a89105dd6cd5f0d9`。14个子进程均由测试停止；未读取正式数据库或升级18524。
- Protobuf审计新增request_id、peer_kind、max_id和error。ReadHistory的负数及超int32上限返回400 `BOTGRAM_RPC_INVALID_ARGUMENT`且无状态变更；0当前成功无操作；int32最大值读至实际末条消息，pts推进一次，重复请求不再推进。断言逐行关联请求ID/参数/错误码/HTTP状态，并确认不含token、用户UUID或正文。审计读取最初与异步写入冲突，改为停止测试实例并排空日志后检查；未改业务断言来掩盖错误。
- **旧真机两次ReadHistory400仍未归因**：旧日志没有上述参数及错误码，新边界回归不能倒推历史原因。客户端可能传非正max_id的静态路径仍需真实请求复现，当前不擅改语义。JSON审计没有新的请求ID及ReadHistory参数；本轮关联取证范围明确限Protobuf。
- 源码宿主协商及HTTP响应头/正文黑洞回归**228断言PASS**（`-CompileMapperSources -Blackhole`），两次生产read timeout约20001/20002ms，无内部重试或JSON回退，同API随后恢复。宿主classpath调整为真实org.json jar优先于Android stub；不影响APK source set。实际Android编译类默认模式尚待新构建完成，不能把源码宿主结果当作APK或真机通过。
- 最新Android Debug构建仍在后台原生链接阶段；本节新增协议协商尚未交付APK或部署到18524。ADB列表为空，冷启动持久化、多账号/迟到回调、HTTP/WSS真机黑洞、前后台恢复、消息唯一及pts连续性仍未验证。现有18524用户基础收发反馈仍有效，但不代表新协商实现已在手机运行。
- `dotnet run`直接启动曾无可用输出地异常退出；分离build和已构建DLL执行后完成上述验证，不把失败当通过。P5仍未完成，性能预算未批准，保留JSON入口，不进入P6/P7。

### P5 双协议性能基线（2026-11-02，非达标结论）

- 扩展后端 `Protocol.HttpTests/Program.cs`，无参数仍执行原回归，`--benchmark`独立采集基线。测试代理Release构建报告0警告/错误；主执行已复核源码、下述原始汇总及执行日志。没有修改生产业务、限流或18524实例。
- 同机.NET 8.0.33、12逻辑处理器；先建立100条相同文字消息的已停止基线，再为每操作/协议复制独立数据库并启动随机loopback进程。每组5预热+40正式采样，3操作×2协议=240正式响应均200且业务校验通过。发送使用相同45个signed64幂等键但分别在独立副本执行，逐键检查仅一条持久化消息，不向同一库双写。
- 完整HTTP正文计时，排除客户端编码/解码校验和进程采样；字节数仅HTTP body。各操作交替先执行的协议，仍存在JIT、缓存及顺序偏差；无并发、TLS、手机网络或长时错误率观察。每实例同用户45请求，不绕过300/min限流。

| 操作 | JSON p50/p95 ms | Protobuf p50/p95 ms | 请求body均值 B（JSON/PB） | 响应body均值 B（JSON/PB） | 后端CPU合计 ms（JSON/PB，40次） |
|---|---|---|---|---|---|
| GetState | 7.232 / 7.779 | 6.581 / 7.343 | 56 / 18 | 114 / 27 | 203.125 / 203.125 |
| GetHistory（100条） | 9.573 / 10.765 | 7.254 / 10.543 | 149 / 73 | 45287 / 15217 | 171.875 / 125 |
| SendMessage | 7.088 / 8.168 | 6.977 / 7.897 | 219 / 110 | 1458 / 567.275 | 156.25 / 187.5 |

- 每次完整响应后采样WorkingSet/PrivateMemory，不是连续峰值或分配量。GetState、GetHistory、SendMessage的最大WorkingSet（JSON/PB）分别75,030,528/72,974,336、117,035,008/80,551,936、78,344,192/76,144,640 B；CPU计数粒度较粗，发送组PB CPU较高，不能依据单轮结果声称CPU全面改善或无未解释回归。
- 基准后端DLL SHA-256：`35A17833353C23689A8F9180C368753A954B5855BDA1A37D5B03B80ADCE587D9`。证据：`D:/BotgramBuild/protobuf/http-tests/runs/bebf96c9c14844cc851ebd6b287609be/benchmark.json`及`benchmark-samples.json`；执行日志`D:/BotgramBuild/protobuf/http-tests/benchmark-launch.stdout.log`显示全部7个自启进程由测试停止。汇总不含凭据，不交付同目录隔离数据库。
- 增加基准后原HTTP路径实际复跑**2062断言PASS**，证据`D:/BotgramBuild/protobuf/http-tests/regression-launch.stdout.log`，隔离运行目录`cf535dfa523e438dbd6b82a986b8b042`。主执行复核该日志含PASS且9个自启进程均stoppedByTest=True；exit=-1为测试主动清理，不当作自然崩溃。
- **性能门槛仍开放**：原设计只要求先测基线再定预算，未批准p95/错误率/CPU/内存/APK增量数值。当前仅覆盖3类代表性操作、单轮40样本，没有同条件旧APK体积基线、Android编解码/耗电、并发和重复轮次。240次观测无错误不代表生产错误率为0。下一步应以重复/反序与并发实测排除偏差，再明确预算；能力协商、会话灰度、WSS/真机黑洞及未解释错误解决前，P5不标完成。

### 本轮Android构建问题、产物与未验证

- 默认Debug构建在沙箱内外均发生D盘Gradle daemon日志/`FileAccessTimeJournal`写入拒绝，因此不能只归因沙箱。独立daemon目录仅绕过第一处，未解决后续journal。
- 使用D盘独立Gradle user home/project cache、`GRADLE_RO_DEP_CACHE=D:/BotgramBuild/gradle/caches`只读复用既有依赖，离线配置及buildSrc编译已能运行。初次误把modules-2当只读根目录导致plugin解析失败，修正后已越过该处，无重新下载。Kotlin daemon曾试图写C盘marker并失败，fallback编译通过；后续尝试显式in-process避免该路径。
- 完整 `:TMessagesProj_AppStandalone:assembleAfatDebug` 最终在10个既有media3/jlatexmath `mergeDebugJniLibFolders`输出目录删除失败，exit1；不修改ACL、不删除依赖或junction、不把失败记为APK构建通过。
- 单独Java编译起初同样在11个依赖库`generateDebugBuildConfig`旧目录删除失败；随后使用D盘临时init script仅重定向media3/jlatexmath buildDirectory，不改源码构建配置、不清理旧目录，`:TMessagesProj:compileDebugJavaWithJavac` exit0（137 tasks，129 executed），默认mapper复跑203项通过。完整Debug APK按同样隔离方式构建exit0；apksigner验证v1/v2签名，aapt确认包名me.nyarugram.messenger、名称Nyarugram、版本13.1.2/72389、minSdk23/targetSdk36及四ABI。APK SHA-256为4B1A8AFF6DB42F9DA9E979EC28ED6B6745D50E82DB67FD30FAE6A507B5DF098B。解包DEX后确认两个生产Mapper、Identity、RpcRequest/RpcResponse及GeneratedMessageLite实际打包；签名使用仓库开发证书，不是私有生产签名。
- 新Debug APK已复制到artifacts/android/Nyarugram-debug.apk并复核哈希相同，build-info顶层哈希及独立p4ProtobufVerification已更新；其余历史验证区块保留原适用范围，不代表本轮部署或设备证据。该APK普通请求需要匹配的新Protobuf后端，无自动JSON回退；本轮未部署后端，不保证连接旧部署。真实`:TMessagesProj_AppStandalone:assembleAfatStandalone` exit0（340 tasks，336 executed），包含minifyAfatStandaloneWithR8。新Standalone已核验v1/v2签名、me.nyarugram.messenger、Nyarugram、13.1.2/72389、minSdk23/targetSdk36、四ABI及不可debug；SHA-256为E2FA9C139CC11D25332FB5F6482640C5DE23267C421817D0FC829B49E68AA128，复制到artifacts/android/Nyarugram-standalone.apk后哈希相同。最终configuration包含GeneratedMessageLite字段keep规则；mapping和实际DEX确认Identity/RequestMapper/ResponseMapper及RpcRequest/RpcResponse/UserSummary，且operation_/outcome_/protocolMajor_/requestId_/tlUserId_/gid_字段保留。静态产物不证明设备运行时解析通过。ADB设备检查为空；真机安装、原页面收发、冷启动、账号切换、取消/迟到回调、前后台和设备API23未执行。未部署、未访问正式数据、未执行P5灰度或移除JSON入口。

## P6 WSS Protobuf 双协议实际结果（2026-11-02）

用户现已授权推进P6；此前“不进入P6”的记录保留当时适用范围，不代表P5真机恢复、历史ReadHistory400归因或性能预算已验收。

### 实现范围

- `/api/ws`通过标准子协议协商：优先`botgram.event-hint.v1.protobuf`二进制EventHint；支持显式`botgram.events.v1.json`文本；未提供子协议的旧客户端继续JSON。仅未知子协议返回400，认证要求不变。
- EventHint仅含major与事件cursor；不承载消息正文、不作为TL pts或消息消费确认。Android收到提示后仍唤醒原getDifference；保留前台轮询、会话generation守卫及原RPC写超时策略，无写超时自动JSON重发。
- 服务端共用原权限分页，串行await发送、每次10秒截止；两仓proto独立副本一致，已更新C#与Java lite生成源码，无跨仓构建依赖。

### 已执行验证

| 检查 | 实际结果与范围 |
|---|---|
| 后端隔离构建 | 0警告、0错误；测试DLL对应后端SHA-256为`E01952FD7152C3DEB9A521917AC74025B6C8D2621E321D5227DE3EC48D9EFC1F`，主执行重新核对哈希 |
| HTTP完整回归 | 主执行复跑exit0，**2872断言PASS**，含21业务操作与transport-only身份引导 |
| WebSocket子集 | 上述总数中**599断言PASS**：真实loopback WS握手，JSON/PB协商、旧客户端、401/400、100+尾页、cursor独占续接、权限过滤、非法客户端数据及logout断开 |
| 丢提示与差分恢复 | 丢弃提示、断线期间新增消息后，getDifference恢复107条消息；校验两页顺序、唯一性、连续pts、最终GetState及空差分 |
| Android生产listener宿主 | 主执行复跑exit0，**108断言PASS**；真实nv/protobuf/JSON，替代UI队列，无网络；覆盖golden `0801109601`、longMax、unknown字段、截断拒绝、协议/帧错误及旧generation回调隔离 |
| 后端smoke/gid | 测试代理以同一最新隔离DLL执行，两套exit0/PASS；未指向正式库 |
| 静态契约 | 77个message、21业务tag与1个transport tag检查PASS；两仓schema字节一致 |
| 固定工具Generate/Check | Generate成功且3个缓存artifact哈希通过；完整Check在D盘工具解压/检查目录写入拒绝，**未通过完整Check**，未改ACL或删除缓存 |
| 差异空白检查 | 两仓`git diff --check`通过；仅既有LF/CRLF转换提示 |

主执行HTTP复跑根为`D:/BotgramBuild/tmp/p6-http-runs-f5d9ce94db334b5996c8a661667be7d9`，15个自启进程均由测试停止；exit=-1是测试主动终止。运行时数据库写入/替换有工具回收通知，未恢复或交付隔离数据库，PASS依据实际执行输出而非声称快照仍完整存在。此前代理运行根为`D:/BotgramBuild/tmp/p6-http-runs-f7a110c866e34f86bc1b821639fe4cd8`。宿主class输出在`D:/BotgramBuild/tmp/p6-host-final-83a3c8887a394013b70359eee4837228`，替身不进入APK。

### 明确限制与尚未验收

- 历史缺口已在本轮安全补修关闭：nv 2.14的`setMaxPayloadSize`确为发送侧配置，现不再使用它充当入站限制。固定上游jar经SHA-256校验，Gradle仅过滤原`WebSocketInputStream.class`并编译本仓库替代实现；其余entry逐字节一致。OPEN回调在读线程启动前设置PB累计1024字节、JSON累计8MiB；帧头解析后、payload分配/读取前检查单帧及跨分片预算，最多1024片，控制帧不清零累计，非法头和超限后缓存终止错误，避免close等待再次读入攻击body。
- 历史javalite递归限制缺口仍是运行库事实，现由生产listener的解析前迭代wire检查防护，不依赖递归配置。固定32项group栈校验配对/深度/完整性，0/31/32层接受，33/34/100层拒绝；EventHint没有embedded message，unknown length-delimited字段按不透明数据跳过，不把内部随机group字节误判为结构。
- 主执行复跑`Tools/Verify-BotgramEventSocket.ps1 -FilteredJar TMessagesProj/build/botgram-websocket/nv-websocket-client-2.14-receive-filtered.jar`，**214项真实nv reader/loopback检查 + 232项生产listener断言PASS**（exit0）。覆盖超大头无body立即拒绝、64位长度、PB分片累计边界、空分片数、控制帧穿插、非法mask/RSV/opcode、握手后立即攻击与实际断开，以及上述group深度/错配/截断/opaque字段和generation隔离。输出位于`D:/BotgramBuild/tmp/event-socket/2f2c99bb223b42ad92c15ec039305ec6`；宿主UI替身不进入APK。此前108断言记录仅对应补修前版本。
- loopback WS没有TLS终止；宿主没有设备网络。真实WSS/TLS、真机黑洞与恢复、前后台、多账号切换和设备端消息唯一性/pts仍待验证。
- 补修前Android完整`:TMessagesProj_AppStandalone:assembleAfatDebug`已exit0，306 tasks（302 executed）；D盘独立user home/project cache、既有只读依赖及临时库输出重定向离线构建成功，未改ACL或依赖junction。apksigner验证v1/v2通过，aapt确认me.nyarugram.messenger、Nyarugram、13.1.2/72389、minSdk23/targetSdk36和四ABI；DEX确认真实`protocol.v1.BotgramRpc$EventHint`、生产listener和两个子协议标识。首次DEX检查漏写v1包段导致查找失败，按源码真实包名修正后通过，并非APK缺类。新Debug已复制到artifacts/android/Nyarugram-debug.apk，源包与交付SHA-256均为`31CE1A26D78854484F3ABEAF0B78A13A077BA3DDABB9475277DBB4207BDC33DB`；build-info顶层哈希及独立p6Verification已更新。未新增Android资源、未重建Standalone/R8、未安装或真机验证；历史验证区块不代表本轮产物。
- 本轮安全补修后完整Debug构建exit0；Kotlin daemon因C盘临时标记拒绝写入后自动使用编译fallback，最终构建成功，不把该警告当作构建失败或已修复环境。签名v1/v2、包名/名称、13.1.2/72389、minSdk23/targetSdk36及四ABI核验通过；DEX含替代reader、bridge、validateHintWire、两处防护错误标识、EventHint及双子协议。新源包与`artifacts/android/Nyarugram-debug.apk`哈希均为`EA269FF10EAFC3D8B0B8CA740B52BC739B4576C62FC4508E93D466D293E1015F`；已更新build-info。无新增资源、未重建Standalone/R8、未安装。
- 固定工具链主执行首次复跑仍遇旧protoc文件覆盖及checks目录写入拒绝（exit1）；代理一次成功不能消除该失败。现仅调整`tools/protobuf.ps1`为D盘唯一compiler-runs解压目录与tmp/protobuf-check目录，不改ACL、不清缓存、不改固定版本或哈希。再次主执行完整Check **exit0**：3个依赖SHA-256、libprotoc25.8、重复生成及纳入C#逐字节一致、Java8 lite编译、66组Java↔C#互操作全部通过。检查输出`D:/BotgramBuild/tmp/protobuf-check-ae9385d876af4133bff5823802301a9a`，互操作样本`D:/BotgramBuild/protobuf/tests/f054feec6e2c4c2b840bf6958596e465`。旧目录写拒绝根因未定，不宣称权限已修复；使用隔离输出完成验证。
- 本轮ADB `devices -l`为空。独立18524实例受信TLS证书/主机名、代理端口及PB协商尚无新证据；未启动或升级它。P5旧两次ReadHistory400仍缺原请求参数/错误码，边界回归不能倒推根因，需新版关联审计和真机复现。性能仅历史3操作各40样本loopback基线，无TLS/并发/Android编解码和同条件旧APK增量，数值预算未批准，不能形成联合通过结论。
- 未部署正式服务、未升级18524隔离实例、未操作生产数据、未退役JSON；P5遗留验收项保持开放，不进入P7。

### P5/P6 联合验收任务

**P6 状态：实现完成，本地验收通过，待真机联合验收。** 以下均为待执行项，不是通过记录。P5/P6 合并验收，通过后再讨论是否启动 P7。

- [ ] **独立环境与兼容性**：记录测试后端版本/DLL哈希、APK哈希、独立数据目录、TLS主机名及证书信任链；不使用正式账号或数据库。新 Debug 只连接兼容测试后端；APK构建不证明生产服务器已支持新子协议。
- [ ] **真实 TLS/WSS 双协议**：经实际TLS终止及代理确认握手选中 `botgram.event-hint.v1.protobuf`，实际收到并解码二进制EventHint；分别验证显式JSON与无子协议旧客户端的文本事件兼容。保存脱敏握手、帧类型、cursor及差分关联证据，不记录Bearer。
- [ ] **真机可靠性矩阵**：覆盖断网、黑洞、恢复、前后台切换和Alice/Bob快速换号。逐场景核对消息唯一、旧会话回调不污染当前账号、各账号pts不回退，恢复后最终差分追平；记录设备/系统版本、操作步骤及相关请求/日志证据。宿主与loopback结果不替代此项。
- [ ] **异常连接与资源边界**：仅在隔离环境，经真实TLS/代理/服务端链路验证超大、分片、深嵌套、慢速或不完整输入及异常断开；分别覆盖服务端接收客户端异常输入、测试服务端向Android发送异常帧。先约定连接数、输入速率、测试时限和停止条件，观察客户端/代理/服务端内存、CPU、连接与任务释放、正常连接可用性，确认无持续增长或资源耗尽。本地安全回归不等于全链路安全验收。
- [ ] **ReadHistory 400 归因**：使用新审计复现真实客户端调用，关联request_id、peer_kind、max_id、HTTP状态及error，取得参数和错误码并解释调用路径；不将边界回归倒推为旧错误根因，不保存令牌或消息正文。
- [ ] **性能验收标准**：在重复、反序、并发及TLS/真机实测基础上，明确并批准p95、错误率、CPU、内存、APK增量及Android编解码等指标的数值门槛、测量条件与判定方法；在门槛批准前不宣称性能达标。现有单轮loopback基线仅作参考。
- [ ] **联合结论**：逐项附真实结果与证据，区分通过、失败、阻塞及未测；所有必需项关闭后才形成P5/P6联合通过结论，再讨论P7。

上述清单为执行前状态；2026-11-02 本轮实测及已批准服务端预算以下节为准，未完成的端到端项目仍不勾选。

## P5/P6 联合验收实测（2026-11-02）

### 环境、版本与命令

Windows，同机 .NET 8.0.33、12逻辑处理器，JDK17；后端HEAD `67d5806a72a8e9f143bb92fb151e3a232d28f563`、Android HEAD `d8b512988fa12b0909c267ce749ee295ee68f78d`，两仓均有前序未提交修改，HEAD不代表完整构建源码。未修改业务/协议，仅扩展 `Protocol.HttpTests/Program.cs` 与新增分部测试 `TlsTests.cs`。21业务RPC、transport-only身份、W1、双RPC/双WSS保持。

- APK源包与交付SHA256均 `EA269FF10EAFC3D8B0B8CA740B52BC739B4576C62FC4508E93D466D293E1015F`，13.1.2/72389；本轮无Android源码修改、无需重构建。后端实测DLL SHA256 `E01952FD7152C3DEB9A521917AC74025B6C8D2621E321D5227DE3EC48D9EFC1F`，与P6本地回归版本一致。
- 协议major1，EventHint及77-message schema保持。TLS使用临时localhost自签leaf：精确SHA256 pin、平台主机名/有效期校验，仅容忍该leaf链不受系统信任；不更改系统信任或Android安全策略。Kestrel直连真实HTTPS/WSS，**不是公共受信证书/反向代理验收**。
- 所有服务随机loopback端口、独立D盘数据/日志；18524、生产实例/证书/库均未操作。自有子进程已停止，临时PFX已移除；数据库含合成会话，不交付。测试主动停止的exit=-1不等于自然崩溃。
- .NET命令在后端目录，设置 `DOTNET_CLI_HOME=D:/BotgramBuild/dotnet`、`NUGET_PACKAGES=D:/BotgramBuild/nuget`、`TEMP=TMP=D:/BotgramBuild/tmp`、关闭遥测，无下载。

| 实际命令 | 退出码/证据 |
|---|---|
| `D:/BotgramBuild/android-sdk/platform-tools/adb.exe devices -l` | 0，空设备列表，真机BLOCKED |
| `Tools/Verify-BotgramEventSocket.ps1 -FilteredJar TMessagesProj/build/botgram-websocket/nv-websocket-client-2.14-receive-filtered.jar`（客户端目录，JDK17） | 0，214 reader/loopback + 232生产listener断言PASS；输出 `D:/BotgramBuild/tmp/event-socket/732034d06afc42e2849bac74b7c19933`，宿主非手机 |
| `dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --nologo --no-restore` | 1，默认输出被既有进程锁定，未终止它；`D:/BotgramBuild/tmp/p6-tls-build.log` |
| `dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --nologo --no-restore -o D:/BotgramBuild/tmp/p6-tls-execution-bin` | 0；`D:/BotgramBuild/tmp/p6-tls-build-isolated.log` |
| `dotnet D:/BotgramBuild/tmp/p6-tls-execution-bin/Protocol.HttpTests.dll --joint-tls` | 0，649断言；`D:/BotgramBuild/tmp/p6-joint-tls-execution.log` |
| 同一DLL `--benchmark-tls`（初版两轮） | 0，480正式+120预热；`D:/BotgramBuild/tmp/p6-benchmark-tls-execution.log`，保留不覆盖 |
| `dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --nologo --no-restore -o D:/BotgramBuild/tmp/p6-tls-three-bin` | 0；`D:/BotgramBuild/tmp/p6-tls-three-build.log` |
| `dotnet D:/BotgramBuild/tmp/p6-tls-three-bin/Protocol.HttpTests.dll --benchmark-tls` | 0，720正式+180预热；`D:/BotgramBuild/tmp/p6-tls-three-benchmark.log`，进程成功不等于性能通过 |
| 两仓 `git diff --check` | 0；后端既有LF/CRLF提示 |

首轮命令/退出码、108项源码/产物哈希和清理检查在 `D:/BotgramBuild/tmp/p6-tls-evidence-manifest.json`；其中Program.cs SHA256 `FD65483D50BBE9173B9649DCF84CFEC7611DAECAB28583662C1A6C736CF94D2D`，初版TlsTests.cs `728AE6421FFEF3A30840B8BF73808083152BCE38692F560F4CF232733179BE44`，测试DLL `8632AAC332CE0C0A22D7C91C287606554601DFC2DF039B0379A4C117530C3C5E`。补足三轮后TlsTests.cs SHA256 `AACD387FF52D0335F43EDBB06F19FD126D79F004C508EFA6B19FFEE05F28C8E7`，测试DLL `9078E07B5744B92C71F0EE09933D4ACD1BBE9F773573237CB1E829F812DD3B9C`，后端DLL不变。原manifest仅对应首版，不覆盖三轮源码。

### TLS/WSS 与差分：PASS（限定Kestrel直连）

649总断言中604为WSS子集（含5次健康观察）。证据根 `D:/BotgramBuild/tmp/p6-http-runs-0270b61025ec4ab6a30eee85930d2850`，日志保存101状态、selected_subprotocol、帧类型/大小、cursor与差分汇总，不输出Authorization或正文。

- 三种真实握手101：无协议→文本JSON；`botgram.events.v1.json`→文本JSON；`botgram.event-hint.v1.protobuf`→二进制EventHint，实际解码major/cursor。首分页cursor100，尾页108，PB帧4字节；JSON首100事件、尾6事件。验证未知协议/非法cursor拒绝、未认证401、权限过滤与logout后关闭、cursor独占恢复。
- 丢弃提示/断线积压后2页getDifference恢复107消息，唯一且连续，pts由1至108，最终状态与空差分一致；WSS cursor可到110，不当作TL pts。EventHint仅通知，消息通过RPC差分取得。**该调用方是C#测试，不是Android**；Android生产listener仅有宿主generation/唤醒证据，真实Android getDifference关联仍BLOCKED。
- RPC灰度与WSS协议独立；本轮未验证设备上当前账号持久化固定和快速切号，不以服务端协商替代该项。

### 异常连接：部分PASS，完整安全验收NOT TESTED/BLOCKED

事前边界为最多2连接、每秒最多10帧、每例最多10秒；健康失败/进程退出/超时即停止，资源观察5秒。实际服务端负例每例1连接、1个2字节客户端数据帧，110ms间隔；文本/二进制非法上行均关闭。服务端只发送EventHint，不解析客户端hint，不将unknown group测试误归给服务端。

结束后5次健康均200，CPU增量31.25ms，WorkingSet 97,284,096～98,693,120 B，PrivateMemory 62,124,032～63,160,320 B，未见该短窗口持续增长；非连续峰值，无线程/任务逐项释放计数，不能证明长期资源稳定。自有进程最终0。

超大消息、分片累计超限、1025片、深度33/34/100、错误帧、不完整输入/异常断开的客户端防护，本轮214+232宿主回归PASS；分配/读取前拒绝由真实reader与生产wire guard测试覆盖。但**这些攻击尚未经TLS恶意服务端→Android/代理链路验证**。反向代理NOT TESTED，Android及其CPU/任务释放BLOCKED，不关闭全链路安全项。

### ReadHistory：边界PASS，历史归因INCONCLUSIVE

TLS审计实际两条：`request_id=http_test-1 peer_kind=User max_id=-1/2147483648 error=BOTGRAM_RPC_INVALID_ARGUMENT status=400`（各独立请求复用测试ID，不宣称全局唯一）。另验0 no-op、int32最大读至末条及重复不推进。测试路径为C#→Protobuf endpoint→共享业务，非手机会话。

静态Android路径为MessagesController.completeReadTask读取task.maxId构造TL_messages_readHistory→ConnectionsManager→BotgramTransport→BotgramProtobufRequestMapper，mapper直接把int max_id赋给int64 MaxMessageId，不主动改值。由于Android int不可能直接携带2147483648，该超界测试不能作为旧手机400原因。无设备无法取得真实request_id/账号代际/参数；历史两次400仍INCONCLUSIVE，不推测已修复，不修改业务语义。

### 性能：测量完成，延迟预算FAIL，其余限制保留

**用户本轮明确批准服务端预算**：同机同负载至少3轮、协议反序、并发1/2及TLS，每操作PB p95≤JSON的110%，新增非预期错误0且错误率不劣化，单位请求CPU及峰值私有内存≤JSON的110%。Android编解码及APK增量尚无可比基线，阈值未批准。原240正式样本保持历史基线，不作最终结论。

首版两轮480正式证据 `D:/BotgramBuild/tmp/p6-http-runs-43cd7007cdab4650bcb9551d831f32be`。补齐三轮：3×并发1/2×3操作×2协议=36格，每格5预热+20正式，正式720、预热180，900响应均200，校验媒体类型/业务类型/请求关联/历史数量/幂等键及各独立副本消息数。运行目录 `D:/BotgramBuild/tmp/p6-http-runs-4dcc2d49a71c406a9cc9411aed458eb9` 下 `benchmark-tls.json` 与 `benchmark-tls-samples.json`；汇总SHA256 `EDD52E7824FEADC4A27B96123761B38472F0C313A45CFD24701AF78747965D98`。独立基线100消息，各实例25请求，不绕过300/min限流；每个不重叠批次CPU只累计一次，不重复求和并发请求CPU。

| 超预算配对（轮次从0计） | JSON p95 ms | PB p95 ms | PB/JSON |
|---|---:|---:|---:|
| round1，并发2，SendMessage | 15.7819 | 44.4304 | 2.8153 |
| round2，并发1，GetState | 7.6142 | 8.5204 | 1.1190 |
| round2，并发1，SendMessage | 8.2743 | 9.1264 | 1.1030 |

18个配对中3个超过1.10，故本轮延迟预算**FAIL**；其余配对范围约0.345～1.010。观测异常根因未定，不通过删除离群值或放宽预算消除失败。正式720次非预期错误0，仅样本错误率PASS，不宣称生产错误率为零。

GetState请求/响应body JSON/PB分别56/18与114/27 B；GetHistory为149/73与45287/15217 B；SendMessage请求219/110 B，响应JSON1458 B，PB约565 B（ID编码长度可变）。这些仅HTTP body，不是TLS线路字节。计时从SendAsync至完整body读完，排除编码/校验；复用连接，不是每请求新TLS握手。

CPU配对比约0.2～9.0，多组超过1.10；20请求与15.625ms计数粒度、JIT/GC/系统调度使结论不稳定，**未满足CPU预算，归因INCONCLUSIVE**，不能声称CPU达标。采样私有内存比约0.71～0.97，但仅批次后最大值，不是真实连续峰值，峰值预算**INCONCLUSIVE**。Android编解码、耗电/网络、同条件旧APK体积增量BLOCKED/NOT TESTED，不以宿主编解码或现存Standalone与Debug大小之差替代。

### 联合结论与后续

- PASS：最新APK哈希匹配；Kestrel直连TLS三种WSS、鉴权/失效/恢复/权限、107消息差分、宿主214+232防护；3轮性能采样执行完成。
- FAIL：本轮服务端p95预算（3/18配对超限）。未改产品业务，仅以独立输出绕过锁定构建目录并补充测试。
- BLOCKED：无ADB设备，所有手机断网/半开/黑洞/网络切换/前后台/锁屏/快速切号/迟到回调、消息与pts最终一致、首页骨架/离线状态/收藏夹复验。
- NOT TESTED：反向代理、公有受信链、完整TLS异常输入链路与线程/任务释放；Android/APK可比性能缺测。INCONCLUSIVE：历史ReadHistory400、CPU归因及真实峰值内存。
- **不满足P5/P6联合退出条件**。后续需要连接并授权测试手机、隔离账号/兼容测试环境，完成端到端与安全矩阵，调查性能波动并重测（不改阈值掩盖失败）；CPU/峰值采样需提升测量分辨率。Android/APK预算须另行批准。无需生产授权即可继续隔离测试；本轮未部署、未操作18524/生产数据、未退役JSON，不进入P7。

## 性能归因与资源采样续测（2026-11-02）

### 诊断：确认新复现样本的刷盘/锁等待，不追溯冒认旧样本

先仅增加默认关闭的 `Protocol:DiagnosticStages` 分阶段采样，覆盖Store写锁等待、回滚快照、业务、PB响应编码、持久化编码、`Flush(true)`、原子替换；请求结束记录耗时、GC计数和线程池pending。不记录令牌/正文，不改变协议或提交成功语义。设置 `BOTGRAM_DIAGNOSTIC_STAGES=1` 的隔离TLS诊断执行exit0；证据根 `D:/BotgramBuild/tmp/p6-http-runs-8dd2cb24d5fb4cacaaef701a87e8c167`，执行日志 `D:/BotgramBuild/tmp/p6-diagnostic-execution.log`。该轮有插桩，不作为正式性能通过证据。

- 假设“PB业务/编码为主要慢点”：新捕获的并发2 SendMessage慢样本不支持。`backend-7748.stdout.log:90`：31.7586ms中写锁等待18.8319、flush10.5491、业务0.0784、PB响应编码0.0308ms；`:98`：31.2288ms中锁22.462、flush6.4433、业务0.0871、编码0.0401ms。
- 假设“锁等待”：上述样本确认，且`:86`/`:94`无显著写锁等待时仍分别在flush花费16.5071/20.632ms，说明持久化耗时可经串行锁放大到后续请求。
- 假设“序列化”：上述样本快照约0.51–0.72ms，持久化编码约0.71–0.99ms，不能解释主要尾延迟；不能据此排除其他负载的序列化成本。
- 假设“GC/调度”：这些样本GC0/1/2均0、结束pending0，未观测到GC；pending瞬时值不能排除请求开始前排队、OS调度或flush期间的调度。未采集内核I/O栈，不能将flush墙钟耗时进一步断言为物理磁盘故障。

原44.4304ms样本没有当时的分阶段trace，具体归因仍INCONCLUSIVE。没有证据支持修改PB业务或削弱`Flush(true)`/原子替换/回滚；本轮**没有实施业务性能修复**。先前尝试通用诊断采集器因D盘目录PermissionError失败且探针连接拒绝，已退出；改用现有子进程stdout与ILogger，无采集器遗留。

### 原110%预算重测：延迟FAIL，CPU未达标，真实峰值仍INCONCLUSIVE

修正测量干扰而非业务：TLS并发请求不再各自执行重叠的进程Refresh/CPU读取；CPU仍按不重叠批次计一次。每格20预热+200正式，3轮协议反序、并发1/2、3操作×双协议，共7200正式+720预热。各独立实例220请求，不修改300/min限流；SendMessage最终320消息，其他100，所有响应200且业务校验完成。正式测量设置 `BOTGRAM_DIAGNOSTIC_STAGES=0`，日志采样关闭。

实际命令（后端目录，沿用D盘.NET/NuGet/TEMP环境）：

- `dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --nologo --no-restore -o D:/BotgramBuild/tmp/p6-resource-bin`：exit0。
- `dotnet D:/BotgramBuild/tmp/p6-resource-bin/Protocol.HttpTests.dll --benchmark-tls`：exit0；`D:/BotgramBuild/tmp/p6-resource-execution.log`。
- 汇总/原始样本根 `D:/BotgramBuild/tmp/p6-http-runs-a366200aaf9d417f8820f3d3ab75a1a5`；汇总SHA256 `34E61DEB49C0E9951461D3B5D95B3FD30899D60002942C1EB21A0396CE6B3F2C`；测试DLL `2F4D4E303674B834D23CE9778FBB3A1AD838FE91D3DC13A72861C9E0D1FACBA0`；后端DLL `16FD42B73A6B244063ED66280C39C8C9A80FB0588A34C18C81B26FF3E6BA52CF`（包含默认关闭的诊断代码，不同于此前无插桩DLL）。临时PFX已删除，自有37个后端子进程逐一停止。

| 超延迟预算配对 | JSON p95 ms | PB p95 ms | PB/JSON约 |
|---|---:|---:|---:|
| r0 c1 GetState | 9.232 | 11.989 | 1.30 |
| r0 c1 GetHistory | 12.407 | 16.473 | 1.33 |
| r0 c1 SendMessage | 13.717 | 21.165 | 1.54 |
| r0 c2 SendMessage | 17.038 | 31.480 | 1.85 |
| r1 c1 SendMessage | 17.627 | 20.916 | 1.19 |
| r1 c2 SendMessage | 22.942 | 32.965 | 1.44 |
| r2 c1 GetHistory | 12.640 | 19.101 | 1.51 |

18配对中7个p95超1.10，**FAIL**；不是修复后通过。CPU累计约0.844–1.781秒/格，较20请求提高分辨率，18配对仍有2个超限：r0 c1 GetState 1.3913倍、r2 c1 SendMessage 1.1932倍，**本轮CPU预算未满足**；存在进程预热/动态JIT/环境差异，不能将两次失败断言为确定的PB额外开销。非预期错误0仅代表本轮样本。

独立观察器在整个预热+正式窗口持续读取WorkingSet/PrivateMemory，目标2ms间隔，原始时间戳保留；每格389–683点，最大实际间隔56.6158ms，不能称严格2ms或无间隙连续峰值。18配对采样私有内存峰值均≤1.10（比值约0.41–0.99），仅采样下界；未取得OS精确private高水位/分配轨迹，**真实峰值预算仍INCONCLUSIVE**，不以采样PASS替代。该版样本数/暖机变化使其不能与旧20样本p95直接当作修复效果比较；旧FAIL记录完整保留。

补充回归：`dotnet D:/BotgramBuild/tmp/p6-resource-bin/Protocol.HttpTests.dll` 无参数执行exit0，2872断言PASS（21业务操作、transport-only身份及599 WS断言）；日志 `D:/BotgramBuild/tmp/p6-resource-regression.log`，根 `D:/BotgramBuild/tmp/p6-http-runs-98195ce79d9245ffbe12252def07d9fc`。该回归为HTTP/WS，不冒充TLS/Android覆盖。

ADB复查exit0仍空列表；Android端到端、历史400请求取证及UI事项继续BLOCKED。上述性能测试均为Kestrel直连，反代/证书链状态以下节为准。不修改生产、不退役JSON、不进入P7。

## 独立真实反向代理TLS续测（2026-11-02）

本节覆盖此前“反向代理NOT TESTED”的部分状态，不覆盖全部安全退出条件。固定Caddy 2.10.2 Windows amd64，官方release ZIP `https://github.com/caddyserver/caddy/releases/download/v2.10.2/caddy_2.10.2_windows_amd64.zip`，下载后先验证SHA256 `9FD1EF9BE5D9B05852B66CCC25F96F23D8651BCAB20779861A745BDFFA273722` 再解压至D盘。未引入应用运行依赖。

- 新测试入口 `--joint-proxy-tls`；设置 `BOTGRAM_TEST_CADDY=D:/BotgramBuild/tools/caddy-2.10.2/caddy.exe`。Caddy各实例仅监听随机`127.0.0.1` TLS端口→随机HTTP Kestrel，禁admin、自动HTTPS和配置持久化；证书、私钥、缓存位于唯一D盘测试目录。不创建系统服务，不改信任/防火墙或生产。
- 实际构建 `dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --no-restore --nologo -o D:/BotgramBuild/tmp/p6-proxy-bin` exit0，0警告；运行 `dotnet D:/BotgramBuild/tmp/p6-proxy-bin/Protocol.HttpTests.dll --joint-proxy-tls` exit0，649断言PASS（604 WSS）。日志 `D:/BotgramBuild/tmp/p6-proxy-build.log`、`D:/BotgramBuild/tmp/p6-proxy-tls.log`；证据根 `D:/BotgramBuild/tmp/p6-http-runs-701c9fc1114e47c0830a41b536775bb4`。
- 测试DLL SHA256 `93DD76DD2B6E88A576A362D6A3F4109910BCE072BEA3E1814443CBA753137473`，后端DLL仍 `16FD42B73A6B244063ED66280C39C8C9A80FB0588A34C18C81B26FF3E6BA52CF`；最终测试Program.cs `0B80CE2B99646C43756AE092E25389AC18C70D96AE8225DB3DD5F5CAC6BF603C`、TlsTests.cs `AE4DCA1CDF87A08E570F067F3907CD149F7404D0D474ECAEFEDAFDFBFFE3E522`。两仓`git diff --check` exit0（已有CRLF提示）。
- 代理链路三种真实101握手、JSON文本/PB二进制解码、权限/会话失效/cursor独占恢复、有界错误文本/二进制上行关闭通过；丢提示后两页RPC差分恢复107消息，pts1→108。ReadHistory负参数经代理400审计符合契约，仍不能解释历史手机400。
- 负例后5次代理健康200，所观测后端CPU增量31.25ms、private约59.13–60.07MB。该采样仅后端5秒，不包含代理CPU/峰值或逐线程/任务释放，不能宣称长期稳定。6个自有子进程逐一退出；主执行复核均不存在，临时PEM/私钥/PFX不存在。测试主动kill退出不等于观察到自然优雅释放。
- TLS仍为临时localhost自签leaf精确pin，平台检查主机名且显式检查有效期；仅该leaf的链错误被容忍。**CA证书链验证、公共信任、过期/错误主机名/错误链等负例未测**；不是“证书链安全验收通过”。客户端大消息/分片/深度攻击尚未经恶意TLS服务端→真实Android验证；代理中断/半开/黑洞、代理及Android任务释放仍未测。

最终状态：真实反代功能子集PASS；原110%延迟/CPU预算未满足；精确峰值及旧400归因INCONCLUSIVE；真机BLOCKED；完整TLS安全矩阵未关闭。**P5/P6联合验收仍未通过**。仅现有两份验收文档更新，无生产部署、无18524操作、无JSON退役、无P7。

## P5/P6 性能归因、保守优化与安全矩阵收敛（2026-11-02，本轮）

本节保留前文全部失败/未测记录，不进入P7。Android HEAD仍为d8b512988fa12b0909c267ce749ee295ee68f78d，后端HEAD仍为67d5806a72a8e9f143bb92fb151e3a232d28f563；两仓均有大量既有未提交修改，HEAD不是交付源码指纹。没有修改Android生产源码，不重建APK、不改签名。没有操作18524、正式服务/数据/证书/Caddy，没有修改ACL、终止既有进程或提交Git。

### 实际修改与事务边界审查

- 后端 ProtobufRpcMapper.cs：SendMessage响应和updateNewMessage复用同一次Message映射结果，减少重复DescribeMessage、身份查询和DTO构建；对象仅在本次请求内共享，随后同步编码，无跨请求缓存、无域实体外泄。random_id、权限、ID分配、事件和事务范围不变。
- Program.cs、Store.cs、Auth.cs、ConversationMessageService.cs、SyncService.cs、TelegramGateway.cs、ProtobufRpcEndpoint.cs及新增RpcDiagnosticStage.cs/RpcDiagnosticStream.cs：仅扩展默认关闭的诊断；增加auth、读体、共享service、mapper、响应写入与JSON编码/写入组合窗口；GC改为请求窗口中的**进程级增量**，并非单请求独占GC。修正旧business_ms命名为protobuf_mapper_ms，避免把输入检查/响应映射都称为纯业务。
- 新诊断辅助函数最初在关闭时仍构造标签字符串，复核后去掉该分配；保留其前一轮测量，重新构建最终版本并执行正式矩阵。详细诊断运行使用修正前的DLL（仅关闭路径有差别），不能冒充最终二进制的正式性能结果。
- Protocol.HttpTests/MapperProfile.cs：3轮反序、每格200预热+5000次同步mapper调用，记录线程分配和延迟。SendMessage使用已有random_id重试以固定100条数据库，**不包含新消息写入、HTTP、TLS、编解码和Store持久化，不用于原110%端到端验收**。
- Protocol.HttpTests/Program.cs、TlsTests.cs：新增8个同会话并发发送、ID唯一/连续、pts恰好推进8、完整Message与更新Message相等；增加真实错误host握手、线程/handle观察；保留粗粒度CPU时间，补充QueryProcessCycleTime；p99及OS生命周期PeakPagedMemorySize64仅作辅助，不替换私有内存或CPU原预算。
- tests/typed-persistence.mjs：增加Windows目标文件允许读但禁止替换的失败注入，验证临时文件已写入/Flush后原子替换失败仍恢复全部内存/磁盘；不改ACL。Flush(true)本身报错尚无确定性注入，**NOT TESTED**，不能由临时文件创建/原子替换失败代替。
- tools/evaluate-tls-budget.ps1 / summarize-rpc-stages.ps1：逐配对计算原1.10阈值、p50/p95/p99/最大值/错误/顺序/哈希，保留原始样本；性能失败返回exit1，运行器exit0不代表预算通过。
- 新增Protocol.HttpTests/CertificateTests.cs及客户端Tools/Verify-BotgramWssFaults.ps1、Tools/tests/wss-fault-host/下两个宿主源，仅为安全验证，不进入APK。

两个RPC入口每次均进入一次Store.Write；成功路径均一次持久化JSON编码、Flush(true)、原子替换，基准读取操作也保留写事务以支持惰性会话/身份分配。当前源码在成功路径**已没有旧的内存回滚快照序列化**，失败时从已提交磁盘恢复的实现是接手时已有改动，本轮未引入或撤销。不能把历史snapshot_ms当作当前成功路径开销。PB响应映射/编码和大小检查在锁内，JSON Gateway生成JsonObject在锁内，框架JSON响应编码在提交后锁外；这是现存的锁范围差异。本轮不将PB编码移出锁，避免不可表示的结果在已提交后才失败。PB还有事务内会话复查；不删除这些检查以改善速度。

### 分阶段对照与包含关系

单独开启BOTGRAM_DIAGNOSTIC_STAGES=1，完整7920请求（7200正式阶段+720预热）执行exit0；日志D:/BotgramBuild/tmp/p6-next-diagnostic.log，根D:/BotgramBuild/tmp/p6-http-runs-198250e918904a8d9e18cfa04898194b。解析命令tools/summarize-rpc-stages.ps1，exit0，输出D:/BotgramBuild/tmp/p6-next-stages.json，逐请求保留trace、原stdout路径和各阶段。该运行有插桩，不作性能通过依据。

| 新捕获样本 | 请求总ms | 锁等待ms | mapper合计ms | 响应编码ms | 持久化编码ms | Flush ms | 替换ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| r1 c2 JSON SendMessage | 83.7900 | 64.3179 | 0.3159 | 0.1406（编码+写） | 1.0111 | 17.4099 | 0.3947 |
| r2 c2 PB GetState | 80.0941 | 58.2713 | 0.0245 | 0.0081 | 0.8091 | 20.4288 | 0.3906 |
| r0 c2 PB SendMessage | 79.5515 | 11.9434 | 0.0728 | 0.0376 | 0.9759 | 65.8934 | 0.3908 |
| r2 c1 JSON SendMessage | 67.4160 | 0.0001 | 0.2540 | 0.1427（编码+写） | 0.7997 | 65.6807 | 0.3532 |
| r0 c1 PB GetHistory | 66.9211 | 0.0001 | 0.2419 | 0.2055 | 0.6807 | 65.1730 | 0.4046 |

以上样本GC0/1/2窗口增量均0、结束pending0，不能排除开始前排队或OS调度。JSON83.79ms样本实际Send service仅0.0243ms，PB79.55ms样本Send service0.0143ms。共同观测到Flush墙钟时间及锁放大，不能断言物理磁盘故障，不能说明所有PB差异已被解释，更不能回溯历史44.4304ms样本。

计时均为包含式墙钟：action包含mapper和PB编码；mapper包含共享service与消息映射；PB读取/解码包含读体子阶段；JSON编码+写包含response_body_write；不得将父子阶段重复相加。auth包含令牌摘要和Store.Read等待；service包含其内部权限/状态操作，尚未逐行拆分参数/权限与纯计算。JSON框架绑定只捕获读体，反序列化/路由残差未完全独立；线程池pending是结束瞬时值，未取得CPU采样调用栈、内核I/O栈或线程调度轨迹。上述未拆分项保留INCONCLUSIVE，后续若继续要求精确CPU差异归因，需要隔离进程EventPipe/CPU及I/O/调度关联跟踪，不凭编码短就排除端到端开销。

基准审查：每格独立随机loopback端口及同一停止的100消息快照副本；20预热后200正式，SendMessage每次新key，最终320消息；两协议增长轨迹一致。中间轮反序、并发1/2真实执行；每实例每用户220请求不触发300/min，无限流修改。请求计时同为SendAsync到完整body读完，客户端构建/校验在延迟窗口外；CPU按不重叠批次只取一次，仍包含后端异步/JIT等开销，周期计数不换算ms。正式矩阵时不并行构建、诊断或安全负载；外部系统活动、缓存/JIT/磁盘布局偏差并未被完全控制，不删除离群值。没有证据将CPU比值失败归因于确定的PB纯计算成本。

### 正确性与局部优化证据

- 优化前保存二进制无参数回归exit0、2872断言；日志p6-next-before-correctness.log，根p6-http-runs-1eccb3ed032647bdb8ad96c83b66764f。
- 最终源码构建后再次无参数回归exit0、2913断言（含新增并发及完整Message一致性）；日志p6-next-delivery-correctness.log，根p6-http-runs-f012d0e22e9245039d93c115a9868634。21业务RPC、transport-only身份、归一化JSON/PB响应与每步持久化快照、取消后重试、冲突、非成员/联系人/群隔离、差分页及599 WS断言通过。取消测试证明重试单次执行，不证明中止请求绝不提交。
- Node m49、typed-persistence、rpc-boundaries、contacts、gid、smoke、forwarded-headers、tl-schema均exit0，日志D:/BotgramBuild/tmp/p6-next-<test>.log。增加原子替换故障后typed-persistence单独重跑exit0，p6-next-atomic-rollback.log；旧15实体/六事件JSON、UUID/GID/TL ID/cursor、回调异常/临时文件/原子替换失败回滚、跨进程重启通过。没有执行确定性Flush失败或不可恢复介质故障测试。
- Tools/Verify-BotgramTL.ps1分别使用本轮smoke40、boundary131、gid2、contact26个fixtures，四次exit0，199个真实Android TL类序列化/反序列化回读；日志p6-next-<fixture>-codec.log。
- Tools/Verify-BotgramProtobufMapping.ps1 -CompileMapperSources -Blackhole重试exit0，228宿主断言、21结果/15实体/四差分/五更新、正文与响应头黑洞各约20s超时。首次Java UnixDomainSocket Invalid argument失败保留p6-next-android-mapping.log；设置既有IPv4偏好和D盘TEMP/java.io.tmpdir后通过p6-next-android-mapping-retest.log，不改系统网络。宿主替身不在APK源码集，不算真机。
- mapper分配前测根p6-http-runs-7790f21fd20d45c0bc78721fc0701c06，PB Send重试三轮均6736B/次；中间版本根p6-http-runs-199c4d9670734bf8935ecfd065926d6c，均5344B/次；最终版本分配详见下方最终补测。此对照局限于固定状态同步mapper，且最终包含关闭诊断标签分配修正，不能据此宣称端到端百分比改善。

### 无真机安全矩阵与资源界限

- **PASS，隔离CA链子集**：--certificate-security，7次真实TCP/TLS1.2、20断言；合法CA链/正确host、错误host、过期、尚未生效、不受信任根、缺中间、错误中间均按预期。每连接CustomRootTrust，禁AIA下载，不改全局信任。已有Git OpenSSL服务端及D盘临时PEM，finally只清理自有进程/密钥；公有CA、吊销与Android证书策略仍NOT TESTED/BLOCKED。初版Windows SslStream ephemeral-key服务端失败日志保留，使用已有OpenSSL替代，无下载。
- **PASS，TLS/WSS网络宿主子集**：Tools/Verify-BotgramWssFaults.ps1，exit0，16连接/92断言，真实TLS→nv reader→生产listener。PB1025字节/JSON8MiB+1声明、跨分片超限、1025片、unknown group32接受/33/34/100拒绝、不完整PB、错帧、非法close、中途断流、静默显式取消、生产20s读取超时通过；手工重连携带after99并接受cursor100。证据D:/BotgramBuild/tmp/wss-faults/415dc1867deb44c6b3fd01ea0775fc17/execution.log，SHA256 503AA120237366FBFF6C7C0D94F196A01592081B38ECC92BFCA02390AC5DE92A。隔离JVM leaf信任，不改系统；不是实际Android，也不是OS丢包黑洞、自动重连timer/backoff验收。
- **PASS，限定资源观察**：上述宿主5×200ms观察activeCloseTasks=0、queued=0，最终关闭执行器10s内正常终止；线程1→2为缓存关闭线程，finally shutdown。短窗heap约24.7MB稳定，CPU增量约1.56s包括全部TLS/测试，不作协议性能比较或长期无泄漏结论。
- **PASS，直连及独立代理功能回归**：最终DLL --joint-tls / --joint-proxy-tls各714断言，含原649项，不将原649重新包装为新增安全验收；真实host错误直连捕获平台NameMismatch，Caddy IP/no-SNI可在证书回调前拒绝握手，分别记录。首次新增断言未区分代理拒绝导致exit1，保留p6-next-proxy.log；修正为必须观测平台NameMismatch或TLS认证异常（不接受普通连接错误）后通过。过期leaf测试现临时pin过期证书，独立验证时间检查而非仅pin不匹配。
- **部分PASS**：有界10次WSS连接关闭及10次RPC后5秒health/线程/handle采样通过；短窗不证明长期资源稳定。连接最终kill/wait只是测试清理，不证明后端/Caddy每个异步任务优雅退出。
- **NOT TESTED/INCONCLUSIVE**：真实网络下后端/Caddy发送背压10s超时分支、逐任务取消/发送队列精确计数、代理半开/丢包黑洞、长期泄漏和测量窗口精确私有内存峰值。
- **BLOCKED**：ADB devices -l在正确权限环境exit0但设备列表为空，真实Android TLS/WSS、网络切换、前后台/锁屏、快速切号、迟到回调、消息唯一/pts最终追平与UI事项。第一次受限环境ADB daemon启动失败保留为环境失败，不由此推定设备状态。历史ReadHistory两次400继续INCONCLUSIVE；隔离参数拒绝不能解释历史手机调用。


### 最终冻结版本：原110%预算仍FAIL

最终构建命令（后端目录，D盘DOTNET_CLI_HOME/TEMP，--no-restore，无依赖下载）：

- dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --no-restore --nologo -o D:/BotgramBuild/tmp/p6-next-delivery-bin，exit0，构建日志p6-next-delivery-build.log。
- dotnet D:/BotgramBuild/tmp/p6-next-delivery-bin/Protocol.HttpTests.dll --benchmark-tls，BOTGRAM_DIAGNOSTIC_STAGES=0，exit0；日志p6-next-delivery-benchmark.log。原3轮、反序、并发1/2、36格各20预热+200正式，7200正式/720预热，所有HTTP200和业务断言通过。
- tools/evaluate-tls-budget.ps1 -BenchmarkPath D:/BotgramBuild/tmp/p6-http-runs-fe35c2158a3040cfa48bbfb29bc72e54/benchmark-tls.json -OutputPath D:/BotgramBuild/tmp/p6-next-delivery-budget.json，**exit1，预算失败**。报告含全部18配对的p50/p95/p99/最大值、200请求/格、错误数、36格执行顺序、CPU ms/周期、采样内存/间隔、请求/响应字节和原始证据SHA256。

| 轮次/并发/操作（从0计） | JSON p95 ms | PB p95 ms | p95比值 | CPU时间比值 |
|---|---:|---:|---:|---:|
| r0 c1 SendMessage | 7.5374 | 22.4225 | 2.9748 FAIL | 1.3673 FAIL |
| r0 c2 GetState | 12.5113 | 38.9045 | 3.1095 FAIL | 1.0000 |
| r0 c2 GetHistory | 17.5653 | 39.0926 | 2.2256 FAIL | 0.5616 |
| r0 c2 SendMessage | 14.1804 | 25.5662 | 1.8029 FAIL | 1.3778 FAIL |
| r1 c1 GetHistory | 16.0855 | 20.8617 | 1.2969 FAIL | 0.7500 |
| r1 c2 SendMessage | 15.0082 | 43.3624 | 2.8892 FAIL | 1.2000 FAIL |
| r2 c1 GetHistory | 8.9760 | 21.1448 | 2.3557 FAIL | 0.8816 |
| r2 c1 SendMessage | 7.8706 | 21.5673 | 2.7402 FAIL | 1.1000（边界通过） |

最终**8/18延迟配对、3/18 CPU配对超限**，未通过服务端性能预算；7200正式样本新增非预期错误0，仅表示该样本范围。采样私有内存峰值比0.3421～0.9641，最大实际采样间隔18.7662ms；采样下界均在预算内，精确私有内存峰值仍INCONCLUSIVE。OS生命周期PeakPagedMemorySize64覆盖启动，不等同测量窗口PrivateMemorySize64峰值。QueryProcessCycleTime提供高分辨率辅助：例如r0c2 SendMessage时间比1.3778但周期比0.9716，r0c1 SendMessage周期比1.2843；二者不一致不能通过换用周期指标将CPU FAIL改成PASS，亦不能单独归因为PB纯CPU计算。

正式基准前后全部保留：
- 接手状态重测根p6-http-runs-20d19eeab95741379284d25a7067d20a：6/18延迟、2/18 CPU失败，报告p6-next-before-budget-full.json。
- DTO复用、但诊断关闭仍有标签分配的中间根p6-http-runs-3ceb53c2534644aa8e11cdf4f4577edc：5/18延迟、2/18 CPU失败，报告p6-next-after-budget.json。
- 去掉关闭诊断标签分配的最终根p6-http-runs-fe35c2158a3040cfa48bbfb29bc72e54：8/18延迟、3/18 CPU失败。三次均为完整7200/720矩阵，不截取最好一轮、不删除尾样本，不能把失败组数量变化当作已证明的修复效果。

最终mapper重试分配3轮均5136B/次，根D:/BotgramBuild/tmp/p6-http-runs-5ee0d95fa6e3458f91144bb0ac533322/mapper-profile.json（前测6736、中间5344）；这证明所测版本局部减少分配，最终差额也包含关闭诊断标签构建修正。端到端没有证据支持“性能已改善/达标”，历史和本轮FAIL保留。

最终版本除2913 HTTP/WS断言外，--joint-tls及--joint-proxy-tls各exit0/714断言；根分别p6-http-runs-98a1cbf5cc2347a2a09bc767f6ee651a、p6-http-runs-72a245a5c0f647b9bb0e35a517e94367。--certificate-security exit0/20断言，根p6-http-runs-20ba648200dd46f78f6fbb6d1a9535a2。最终Node八套再次全部exit0，日志p6-next-delivery-<test>.log；四个Node脚本的fixture路径现跟随BOTGRAM_TEST_ROOT，未设时保留原obj默认，最终fixtures全部在D:/BotgramBuild/tmp/p6-next-node。四次Verify-BotgramTL.ps1使用这些最终fixtures再跑exit0，199样本通过，日志p6-next-delivery-<fixture>-codec.log；没有用旧fixture代替最终DLL结果。

| 最终证据 | SHA256 |
|---|---|
| 后端DLL | 1C1411E12D2B7248EF49CA50DD2FA57FBA549AE2B27C15FA07986E72FCD3CB57 |
| 测试DLL | 8D65BABA7D7FF3E1B91A5505F9AA9137774CCE3F054B5FC4FA49FF97912D5CBE |
| benchmark-tls.json | AF93C40A591207D7C897DC4771289B5DF3D80487360E9EA75A4F0C3B8802F2F0 |
| benchmark-tls-samples.json | 00C9BA7A902F4115FA46656052B3CFBF8A3F51631E50C84422B40EBD5101756D |

完整源码/日志/报告哈希及清理复核写入D:/BotgramBuild/tmp/p6-next-evidence-manifest.json；不包含数据库、令牌、密码或私钥内容。Git diff --check两仓通过（既有LF/CRLF提示），保持全部既有未提交修改，无自动提交。

**联合结论仍不通过**：性能FAIL；采样精确峰值、CPU具体归因、历史ReadHistory400仍INCONCLUSIVE；真实Android端到端BLOCKED；确定性Flush失败、后端/代理逐任务发送超时与完整网络故障资源矩阵NOT TESTED。安全和兼容子集PASS不得替代这些退出条件。没有进入P7，没有修改18524或生产，没有移除JSON RPC/JSON WSS/REST。下一步需要独立进程CPU/I/O/调度关联证据和更受控的磁盘/预热对照、确定性存储故障注入，以及授权真机；不以异步落盘、放宽预算或反向拖慢JSON绕过。


## P7 开发准备：独立退役、观测及配置回滚实测（2026-11-03）

本节是用户新授权的P7.1—P7.3实现及A—E隔离演练，**不是正式退役验收**。P5/P6既有性能FAIL、真机BLOCKED、历史ReadHistory400 INCONCLUSIVE保留；本轮没有重测性能、删除原始异常样本或修改110%预算。没有访问18524或正式服务器/数据库/证书/Caddy，没有变更Android生产源码、protobuf schema、账号身份、存储格式及APK，无Git提交。

### 实际代码范围与审计结论

只新增ProtocolPolicy.cs、ProtocolUsage.cs、Protocol.HttpTests/RetirementTests.cs；修改Program.cs、appsettings.json、Protocol.HttpTests/Program.cs及后端tests/ProtobufAndroidSmoke.java；另更新本文件和PROTOBUF-IMPLEMENTATION.md。既有P1—P6未提交修改、独立性能审计文件和用户改动保留。

完整依赖表见PROTOBUF-IMPLEMENTATION.md的2026-11-03 P7节：/api/telegram/rpc及JSON WSS为可独立禁用的网络旧协议；/api/v1/rpc和PB EventHint为现行协议；认证/注销/GID/个人资料/消息REST/事件REST/WebRTC REST/能力广告/health的JSON仍保留。特别地，REST /api/telegram/me和GID写入响应仍调用TelegramGateway.CurrentUser，不能删除整个Gateway。Store.Json、Models/MessageEntities/EventPayloads的旧快照转换器、配置/M49/fixtures和双协议基准继续保留。

Protocol:LegacyJsonRpcEnabled默认true不变；新增LegacyJsonWssEnabled=true、UsageLogEnabled=false；ProtobufRolloutPercent默认100不变。ProtocolPolicy.Load将两个入口和广告固定在同一启动快照，配置变更需受控重启，不做热切、在途迁移或自动重发。RPC JSON关闭时rollout必须100；WSS JSON开关与0/50/100灰度独立。原capabilities五字段和PB子协议名/schema不变，protocols/eventSubprotocols只列启用的项目。

匿名/失效认证始终401；有效Bearer访问关闭的JSON RPC为410 BOTGRAM_JSON_RPC_DISABLED，访问关闭的无协议/显式JSON WSS为升级前410 BOTGRAM_JSON_WSS_DISABLED，未知子协议400 unsupported_event_subprotocol。提供PB时即使同时提供JSON也优先PB，不因HTTP灰度0改为JSON。

旧JSON pin不因410清除或切PB重发；原auth.logOut特例走REST，用户可退出重登后重新协商，或恢复兼容配置后继续原JSON会话。旧客户端若没有PB实现，关闭JSON RPC后业务RPC不可用，即使REST登录仍成功；只关闭JSON WSS不会关闭RPC/已有轮询，但失去旧事件连接。新Android客户端可独立协商PB WSS，即使HTTP pin仍为JSON。真实旧版本UI提示/退出/重登体验仍未真机验证。

### A—E：同一数据库的真实TLS/WSS回滚

最终命令：dotnet D:/BotgramBuild/tmp/p7-verified-bin/Protocol.HttpTests.dll --retirement，exit0，**448断言PASS**。配置前缺省、无效rollout、Meter计数/错误/取消统计均有测试。七个独立子进程为种子、五种模式、一次预期无效启动；同一隔离快照文件不清空、不替换为旧备份，随机loopback端口、临时localhost leaf精确pin，不改系统信任。此处复用既有TLS helper，不宣称公有证书链验收。

证据根D:/BotgramBuild/tmp/p6-http-runs-6df41f547ad84d09bf04aa692aa73399（沿用测试器p6-http-runs随机目录名，不是旧测试结果）；摘要retirement.json及backend-<pid>.stdout/stderr.log，主日志D:/BotgramBuild/tmp/p7-verified-retirement.log。

| 模式 | JSON RPC | JSON WSS | HTTP rollout | 实测结果 | 此时原账号pts/末条消息ID |
|---|---|---|---:|---|---|
| A 完全兼容 | true | true | 100 | JSON/PB RPC、无协议/显式JSON/PB WSS均正常；REST/GID正常 | 3 / 2 |
| B 仅关旧RPC | false | true | 100 | 旧写请求410且快照不变；PB/REST正常；两种JSON WSS仍正常 | 4 / 3 |
| C 仅关旧WSS | true | false | 0 | 无协议/显式JSON WSS均410；PB WSS及同时提供两协议正常；JSON/PB HTTP均正常 | 5 / 4 |
| D 两旧协议关闭 | false | false | 100 | PB-only HTTP/WSS可用；REST注册/登录/注销/GID可用；旧入口均明确拒绝 | 6 / 5 |
| E 恢复兼容 | true | true | 100 | 同一库、同一旧Bearer恢复JSON RPC和JSON WSS；PB仍可用，无数据库迁移 | 7 / 6 |

每种模式：
- 原消息random_id=long.MinValue重试仍是ID1；各模式只新增一条原账号消息，ID2—6连续、pts仅按真实消息推进；999拒绝写不入库，无隐式跨协议重发。
- 原三账号UUID、十一位GID和TL ID保持；REST新注册的是隔离测试账号，不是重建原用户。
- capabilities与入口一致、认证先于410、未知协议400、不合法会话401、PB实际二进制EventHint及JSON文本帧/cursor实际解码；双offer优先PB。
- REST重登得到新会话但UUID相同，按0/100灰度重新协商；注销只撤销对应新token，撤销后capabilities/WSS均401，原会话继续使用。
- GID区域列表29项、REST GID成功及后续碰撞409符合原契约。
- 新旧握手成功/拒绝/认证失败、post-upgrade非法上行关闭与低敏日志均实际验证，不输出Bearer、密码和正文。

关闭JSON RPC且rollout=0的额外启动按预期拒绝，stderr明确ProtocolPolicy校验异常，子进程自然exit=-532462766是本次**预期负例**，不是未解释崩溃；断言已确认库未修改。正常测试实例由本轮主动终止，exit=-1不当作自然退出或优雅资源释放证据。中间417/447项版本日志p7-retirement-execution.log、p7-final-retirement.log保留，最终448项以上述verified版本为准。配置回滚已测，**旧程序二进制降级、生产重启/在途切流未测**。

### 观测的可用范围

Meter Botgram.ProtocolUsage/v1.0，三种Counter：botgram.protocol.attempts、botgram.protocol.errors、botgram.protocol.wss.ends。固定标签transport/protocol/outcome/http_status；无身份、会话哈希、IP、令牌或正文。MeterListener验证请求/握手只计一次（101立即记录）、错误分子及已处理取消不会误计200；真实A—E服务的UsageLogEnabled=true验证各类别实际产生。

RPC/协商/握手的分组错误率按transport/protocol聚合：errors增量÷全部outcome的attempts增量，另分别列disabled、authentication_failed、unsupported_client、rejected、failed、cancelled；取消不代表写入未提交。WSS认证失败尚未选择协议，计unselected；unknown offer不记录原字符串。连接后发送/接收循环的错误由wss.ends独立分组，client_payload_rejected/transport_error/send_timeout不得混入握手101的错误率分母，结束计数不代表所有资源释放。

日志默认关闭；测试开启仅输出PROTOCOL_USAGE/PROTOCOL_WSS_END固定字段，无新增HTTP统计接口，无用户库写入。计数需要订阅/导出才有跨重启历史，本轮未部署生产collector。capabilities 200只证明服务端响应成功，不证明客户端采用，也不能识别从未请求的旧版本及所有客户端解析失败；不能以当前本机计数推断线上旧客户端已退出。

### 构建、回归和实际退出码

Windows，.NET SDK8.0.204、D盘既有NuGet/CLI/TEMP缓存、JDK17、固定既有Caddy2.10.2；无新依赖下载。DOTNET_CLI_HOME=D:/BotgramBuild/dotnet，NUGET_PACKAGES=D:/BotgramBuild/nuget，TEMP=TMP=D:/BotgramBuild/tmp，BOTGRAM_DIAGNOSTIC_STAGES=0；Node数据/fixtures为D:/BotgramBuild/tmp/p7-verified-node。Java使用既有IPv4偏好及D盘java.io.tmpdir。所有后端命令从W:/Work/Botgram.Backend执行，宿主脚本从客户端执行。

| 实际命令/验证 | 退出码与证据（均D:/BotgramBuild/tmp下） |
|---|---|
| dotnet build Protocol.HttpTests/Protocol.HttpTests.csproj --no-restore --nologo -o D:/BotgramBuild/tmp/p7-verified-bin | 0，0警告错误；p7-verified-build.log |
| 同目录Protocol.HttpTests.dll --retirement | 0，448断言；p7-verified-retirement.log |
| 同一DLL无参数 | 0，2913断言/21RPC+transport-only身份、JSON/PB语义及每步归一化快照；p7-verified-core.log，根p6-http-runs-fc76f90345014c7b97f4e2e32182c638 |
| 同一DLL --joint-proxy-tls，BOTGRAM_TEST_CADDY=D:/BotgramBuild/tools/caddy-2.10.2/caddy.exe | 0，714断言；p7-verified-joint-proxy-tls.log，根p6-http-runs-da15184396a84e3794c3cc1b41e7f450 |
| node tests/m49.mjs、typed-persistence.mjs、rpc-boundaries.mjs、contacts.mjs、gid.mjs、smoke.mjs、forwarded-headers.mjs、tl-schema.mjs | 八套均0；p7-verified-<test>.log |
| Tools/Verify-BotgramTL.ps1 -FixturePath D:/BotgramBuild/tmp/p7-verified-node/<fixture>.json，smoke/boundary/gid/contact四组 | 四次均0，40+131+2+26=199真实TL序列化/回读；p7-<fixture>-codec.log |
| Tools/Verify-BotgramProtobufMapping.ps1 -CompileMapperSources -Blackhole | 0，新增410/pin/恢复测试后234断言；头/正文黑洞各约20s有界且无JSON重发；p7-android-mapping.log |
| Tools/Verify-BotgramSessionScope.ps1 | 0，stale UI/storage、独立generation、active-task drain、transitive cleanup/context restore；p7-session-scope.log |
| Tools/Verify-BotgramEventSocket.ps1 | 0，214 reader/loopback+232生产listener宿主断言；p7-event-socket.log |
| D:/BotgramBuild/android-sdk/platform-tools/adb.exe devices -l | 0，无设备；p7-adb-devices.log，真机仍BLOCKED |
| 两仓git diff --check | 0，既有LF/CRLF提示；不提交Git |

Android API新增的6项检查运行在真实BotgramApi源码+宿主HTTP服务：已固定JSON API在广告关闭后不重新协商，写请求410只发一次、不向PB发送、pin不变，新API可协商PB，恢复兼容后旧实例正常。这是API内存pin宿主验证；BotgramSession的持久化pin清理/新登录关系同时做源码审计，**未将其冒充完整Android SharedPreferences/Keystore/真机重登验证**。SessionScope和事件宿主保留owner/generation迟到回调检查。没有修改Android APK源码，不为日期重建APK。

Node及核心回归保留权限/联系人/群隔离、random_id极值/冲突/取消后重试、同会话并发ID唯一、差分页/pts/cursor恢复；typed-persistence验证旧15实体/六事件快照、回调异常、临时写失败和Windows原子替换失败回滚以及进程重启。Store.Write/Flush(true)/原子替换代码未改；确定性Flush本身报错注入仍NOT TESTED，不因P7通过改为PASS。

### 最终源码/产物与退出条件

后端HEAD67d5806a72a8e9f143bb92fb151e3a232d28f563，Android HEADd8b512988fa12b0909c267ce749ee295ee68f78d，均带既有未提交修改。最终源码、两仓status、日志、模式报告及产物SHA256详见D:/BotgramBuild/tmp/p7-evidence-manifest.json（不包含用户库或私钥内容）。

| 产物 | SHA256 |
|---|---|
| p7-verified-bin/Botgram.Backend.dll | A52D6DAEF343270ADD2BEF4377388E07039809A08C38F4CEEB3DC97CCF6CFBBA |
| p7-verified-bin/Protocol.HttpTests.dll | 4AE8E89E548E296449FD18D7E1B3776DBF73C3A122AEA023ED153D1815146E97 |
| 两仓独立botgram_rpc.proto（未修改） | 295935768E4285332B3E6A0FE63703035E57A545716621873763641B640251F1 |

JSON RPC与JSON WSS已具备独立禁用/恢复能力，隔离PB-only及配置回滚通过；无需重建身份、清库或数据库迁移。Android仍保留JSON pin的集中RPC分支，Gateway及JSON WSS不得删除。没有新增业务实现或更改持久化/SessionScope行为。

**正式退役仍未验收**：P5/P6原110%性能FAIL（本轮新DLL未重新测量，历史矩阵和原始样本全部保留）、真机可靠性BLOCKED、ReadHistory400 INCONCLUSIVE；线上旧版本分布、足够长度的使用统计、兼容窗口、旧客户端UI/升级退出策略、真实部署/二进制回滚和正式切流授权未具备。统计默认关闭事件日志但保留Meter订阅，不能将开发测试无旧请求等同线上可退役。默认仍true/true；生产与18524完全不动。

