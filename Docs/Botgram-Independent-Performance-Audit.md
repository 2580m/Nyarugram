# Botgram 独立性能与架构审计报告

审计日期：2026-11-03。范围：当前工作区后端源码、Android 契约与历史说明、已有 TLS 性能原始证据。本文审计的是当前含未提交变更的工作区及指定证据二进制，不代表某个已发布 Git commit。

## 1. 结论与定位程度

**正式性能预算 FAIL 可以独立复算；现有证据不足以把 FAIL 归因为 Protobuf 线协议本身更慢，也不足以认定物理磁盘是唯一根因。** 最终正式矩阵 18 个 PB/JSON 配对中，p95 超过 JSON 的 110% 有 8 个，CPU 超过 110% 有 3 个，正式 HTTP 错误为 0。

已经定位的机制有三类：

1. **共享的串行持久化与排队。** 两入口连 GetState、GetHistory 也经过同一个 Store.Write；业务、响应构造、全库 JSON 序列化、Flush(true)、文件替换均占有同一互斥锁。认证的 Store.Read 也竞争该锁。独立插桩运行中，最慢 5% 请求的“Write 等锁 + Flush 墙钟”占比中位数分别为 JSON 93.0656%、PB 96.0271%。这是该次运行尾延迟的强证据。
2. **确定存在的协议路径差异。** PB 在事务内完成 ToByteArray、响应上限检查、取消检查；JSON 最终 HTTP JSON 编码在提交及解锁之后。PB 还做事务内 Session 复查、UUID/TL ID 适配和部分重复消息编码；JSON 则有无条件预计算、重复查询和逐字段 JSON 节点构造。共享业务服务不等于共享总成本。
3. **不足以隔离协议因果的测量条件。** 正式二进制为 Debug；CPU 时间存在 15.625 ms 量化；只做三轮且运行顺序不完全平衡；20 次预热没有稳定性判据；并发 2 是批式闭环。它们限制结论的外推能力，但尚未证明能解释全部失败。

**尚未定位的是正式 8 个 p95 失败和 3 个 CPU 失败配对的逐项唯一原因。** 插桩来自另一运行，不能与正式请求逐条拼接。不得把“共享瓶颈成立”替换成“协议没有差异”，也不得把“配对出现约三倍 p95”替换成“PB 编解码慢三倍”。

本轮只读审查产品与已有证据，执行了离线复算，没有启动新的受控负载，没有改产品代码、预算、历史证据或正式服务。新增交付物只有本报告和只读复算脚本。

## 2. 证据等级及使用规则

- **CONFIRMED / 已证实**：源码可以确定的行为，或原始数据可复算的事实。必须明确它证明的是机制、测量结果，还是因果。
- **STRONG / 强证据**：多项证据相符，但仍缺少控制变量实验，不能宣称唯一根因。
- **POSSIBLE / 待验证**：机制上合理，当前无足够运行证据；不得直接据此修改产品。

源码位置按当前工作区给出。历史说明只作为线索；正式二进制 SHA-256 用于固定被测对象，不据此声称整个脏工作区与二进制已经逐文件可重现对应。所有墙钟阶段包括线程被挂起或调度等待；Flush 计时不能单独解释为设备物理写入耗时。

## 3. 真实调用链与事务边界

### 3.1 两入口及认证

[Program.cs:27](W:/Work/Botgram.Backend/Program.cs:27) 注册单例 Store。[Auth.cs:22](W:/Work/Botgram.Backend/Auth.cs:22) 的认证路径对 Bearer 摘要执行 Store.Read 并查 Session；[Program.cs:117](W:/Work/Botgram.Backend/Program.cs:117) 缓存认证结果，随后在 [Program.cs:131](W:/Work/Botgram.Backend/Program.cs:131) 进入限流。API 限流按用户 300/min，队列长度 0；鉴权先于限流，不能把限流视作消除认证等锁的屏障。

每次成功 RPC 的主要 Store 调用是认证一次 Read、入口一次 Write。Mapper 与共享 P1 服务不再嵌套调用另一个 Store.Write。

|步骤|JSON `/api/telegram/rpc`|PB `/api/v1/rpc`|
|---|---|---|
|认证|Store.Read，查询 Session|相同|
|请求输入|框架绑定 JsonObject|读取 body、PB 解码、envelope 验证|
|业务事务|Store.Write → 维护 → Gateway → P1|Store.Write → 维护 → Session/取消复查 → Mapper → P1|
|锁内响应工作|JSON 节点、用户/会话预计算|PB DTO、ToByteArray、响应大小和取消检查|
|锁内提交|全库 Serialize → Flush(true) → Move|相同|
|锁外输出|框架最终 JSON 编码及网络写出|已经编码的 bytes 网络写出|

JSON 入口见 [Program.cs:187](W:/Work/Botgram.Backend/Program.cs:187)。Gateway 返回的 JsonObject 在 Store.Write 成功后交给框架输出；诊断标记在 result ready 后计 JSON encode/write，[Program.cs:82](W:/Work/Botgram.Backend/Program.cs:82)。不能说 JSON 的全部序列化均在锁外：节点构造、SerializeToNode 和全库持久化编码均在锁内。

PB 入口见 [ProtobufRpcEndpoint.cs:20](W:/Work/Botgram.Backend/ProtobufRpcEndpoint.cs:20)。它检查媒体类型、压缩与长度，使用 MemoryStream、8192 字节缓冲及 CodedInputStream，递归上限 32，请求上限 128 KiB。当前没有 body.ToArray() 路径，不能指控其反复完整复制输入。

[ProtobufRpcEndpoint.cs:52](W:/Work/Botgram.Backend/ProtobufRpcEndpoint.cs:52) 进入 Write，在锁内再次检查 Session 是否仍存在且未过期；[ProtobufRpcEndpoint.cs:64](W:/Work/Botgram.Backend/ProtobufRpcEndpoint.cs:64) 直接调用 ProtobufRpcMapper，并不绕行 JSON Gateway。[ProtobufRpcEndpoint.cs:69](W:/Work/Botgram.Backend/ProtobufRpcEndpoint.cs:69) 在锁内 ToByteArray，再检查 8 MiB 响应上限和取消；[ProtobufRpcEndpoint.cs:79](W:/Work/Botgram.Backend/ProtobufRpcEndpoint.cs:79) 锁外写网络。

### 3.2 Store 的实际提交边界

[Store.cs:9](W:/Work/Botgram.Backend/Store.cs:9) 定义实例 gate；[Store.cs:33](W:/Work/Botgram.Backend/Store.cs:33) 的 Read 和 Write 使用同一个互斥锁，不存在多读者并行。数据库还由 [Store.cs:25](W:/Work/Botgram.Backend/Store.cs:25) 的 FileShare.None 租约保护。

一次 Write 的顺序为：

1. 等待 gate；当前诊断只记录 Write 等锁，[Store.cs:38](W:/Work/Botgram.Backend/Store.cs:38)。
2. 清理过期 Sessions/Events，处理超时 Calls，[Store.cs:48](W:/Work/Botgram.Backend/Store.cs:48)。
3. 执行业务 action，[Store.cs:63](W:/Work/Botgram.Backend/Store.cs:63)。PB 的响应编码也在 action 内。
4. 在同一锁内将整个 Database 序列化到临时文件，Flush(true)，覆盖 Move，[Store.cs:68](W:/Work/Botgram.Backend/Store.cs:68)。成功路径只有一次全库持久化序列化，没有旧版的额外内存回滚快照。
5. 失败时从已提交磁盘恢复内存状态，恢复失败抛 AggregateException，[Store.cs:88](W:/Work/Botgram.Backend/Store.cs:88)。

因此一次慢 Flush 会阻塞后续读、写及认证。Write 等锁阶段不包含此前认证 Read 的等待；不能把诊断 lock_ms 当作请求全部锁等待。当前串行同步 I/O 也不是换 ReaderWriterLockSlim 就能解决的问题。

### 3.3 被测三项业务

- **GetState**：JSON 在 Gateway 入口先做 Owned/Related/chats；PB 直接走 State，见 [ProtobufRpcMapper.cs:281](W:/Work/Botgram.Backend/ProtobufRpcMapper.cs:281)。Pts 扫描事件，见 [SyncService.cs:19](W:/Work/Botgram.Backend/SyncService.cs:19)。即使结果看似只读，也经过 Write 的维护与全库保存。
- **GetHistory**：两边使用共享历史服务的扫描、排序、分页，见 [ConversationMessageService.cs:39](W:/Work/Botgram.Backend/ConversationMessageService.cs:39)。JSON 分派见 [TelegramGateway.cs:152](W:/Work/Botgram.Backend/TelegramGateway.cs:152)，PB 分派见 [ProtobufRpcMapper.cs:317](W:/Work/Botgram.Backend/ProtobufRpcMapper.cs:317)。消息描述还会扫描关联对象，[ConversationQueryService.cs:55](W:/Work/Botgram.Backend/ConversationQueryService.cs:55)。PB 输出 peer 有额外身份反查。
- **SendMessage**：共享 Send 检查权限、random_id 幂等、回复关系，追加消息及事件，见 [ConversationMessageService.cs:12](W:/Work/Botgram.Backend/ConversationMessageService.cs:12)、[ConversationMessageService.cs:117](W:/Work/Botgram.Backend/ConversationMessageService.cs:117)。JSON 见 [TelegramGateway.cs:166](W:/Work/Botgram.Backend/TelegramGateway.cs:166)，PB 见 [ProtobufRpcMapper.cs:336](W:/Work/Botgram.Backend/ProtobufRpcMapper.cs:336)。PB 返回顶层 Message 与 Updates 内 Message；即便复用同一个 DTO 引用，生成编码器仍分别计长及写入两个字段，见 [BotgramRpc.cs:14288](W:/Work/Botgram.Backend/Protocol/BotgramRpc.cs:14288) 和 [BotgramRpc.cs:11052](W:/Work/Botgram.Backend/Protocol/BotgramRpc.cs:11052)。这是输出 schema 的重复，不是业务执行了两次 Send。

不能直接把 History/State 改成 Read。Write 入口有维护副作用；[Models.cs:27](W:/Work/Botgram.Backend/Models.cs:27) 的 TelegramId 会惰性写字典；[UserQueryService.cs:8](W:/Work/Botgram.Backend/UserQueryService.cs:8) 的 Profile 会触发身份分配；部分查询路径还可能创建会话，见 [ConversationMessageService.cs:107](W:/Work/Botgram.Backend/ConversationMessageService.cs:107)。只读化必须先证明和分离这些副作用。

## 4. 按严重性排序的发现

### F1 高：共享全库同步提交形成串行瓶颈，读类 RPC 也承担持久化

**等级：机制 CONFIRMED；该次插桩尾部归因 STRONG；正式协议差异的唯一解释未证实。** 影响 JSON 与 PB。

源码与事务边界见第 3 节。数据库主表是 List，[Models.cs:16](W:/Work/Botgram.Backend/Models.cs:16)；事件持有消息等 payload，[Models.cs:34](W:/Work/Botgram.Backend/Models.cs:34)，消息增长还会放大全库保存成本。插桩尾部的等锁与 Flush 占比见第 6 节。

反例与边界：PB 有些配对明显更快，且 GetState/History 的 CPU 经常更低；共享持久化不能单独解释为何某一轮 PB 更慢。Flush 可能受到系统调度、文件系统和其他活动影响；无设备级证据不能认定磁盘硬件是唯一原因。无通话基准也不能归因于每 5 秒后台无条件保存：[CallExpiryService.cs:7](W:/Work/Botgram.Backend/CallExpiryService.cs:7) 仅在发现超时通话后才 Write。

建议先测量每次提交字节数、锁等待和持有时间，区分维护、业务、编码、持久化。随后在隔离环境设计真正只读路径。不得删除 Flush、先成功后落盘或跳过恢复与租约。

### F2 高：当前正式 FAIL 有效，但不足以作协议本体性能结论

**等级：统计结果与测量条件 CONFIRMED；它们对每次失败的贡献待验证。** 影响比较结论。

最终 DLL 为 Debug，Production 环境变量并不会启用 Release 优化。三轮顺序为 JSON 先、PB 先、JSON 先；并发与操作顺序固定，见 [TlsTests.cs:162](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:162)。20 次预热无稳态判据。CPU 时间粗量化及批式并发见第 5 节。

不能据此取消现有 FAIL。四套已有结果均有失败且原始统计一致，说明问题需要继续处理；但不能把 Debug、顺序或 CPU 量化任意一个单独提升为已证实根因。优先增加 Release、平衡顺序、长 CPU 窗口和稳态观测，不放宽既有预算。

### F3 中：PB 的响应编码确实扩大事务内工作范围，但约三倍 p95 的因果未成立

**等级：编码位置 CONFIRMED；总持锁更长及其尾延迟贡献 POSSIBLE。** 主要影响 PB，并可能使同 Store 的其他请求排队。

PB 的 ToByteArray 通常包含计算大小及写入，生成代码中的重复嵌套字段会重复编码。JSON 则承担不同的节点构造成本，故不能只比较 response_encode 与 JSON 最终写出。插桩最慢 PB 请求的 response_encode 仅 0.0081 ms，远小于当次锁等待与 Flush；此样本反驳“极慢请求主要花在 PB 编码”的笼统说法，但不能代表全部请求。

不得把所有编码移出事务。目前编码异常、超过 8 MiB、提交前取消都会使 Write 失败并恢复；若先提交再编码，可能变为消息已持久化但响应生成失败。安全实验应按操作分类：证明只读并得到不可变快照后才讨论锁外编码；变更操作若保留预提交编码，先优化 DTO 与适配。任何方案均需验证编码异常、超限、取消、迟到响应及幂等重试。

### F4 中：PB 身份反查与共享全表扫描具有放大成本

**等级：源码行为 CONFIRMED；当前预算影响待定量。** 影响 PB 适配及两协议共享查询。

PB 在 TL long、UUID 和 peer 之间反查，见 [ProtobufRpcMapper.cs:40](W:/Work/Botgram.Backend/ProtobufRpcMapper.cs:40)、[ProtobufRpcMapper.cs:145](W:/Work/Botgram.Backend/ProtobufRpcMapper.cs:145)、[ProtobufRpcMapper.cs:250](W:/Work/Botgram.Backend/ProtobufRpcMapper.cs:250)。Related 包含嵌套扫描，[UserQueryService.cs:23](W:/Work/Botgram.Backend/UserQueryService.cs:23)；History、Pts 与 DescribeMessage 也重复扫描。

本次基线仅 3 用户、100 消息，不能从渐近复杂度推断它已主导当前尾部。下一步用隔离内存数据，在同样业务结果下测适配耗时、分配与扫描次数；先考虑请求局部查找表。持久索引要维持写事务、回滚恢复和身份稳定性，不直接增加未经设计的共享缓存。

### F5 中：JSON 无条件预计算与重复重建是真实开销，但不能安全地直接删除

**等级：源码行为 CONFIRMED；消除后收益与语义安全待验证。** 影响 JSON。

[TelegramGateway.cs:93](W:/Work/Botgram.Backend/TelegramGateway.cs:93) 在分派前计算 Owned、Related、users、chats；History 不使用全部预计算，Send 的 Updates 又重建 Related/Owned，见 [TelegramGateway.cs:69](W:/Work/Botgram.Backend/TelegramGateway.cs:69)。逐字段 SerializeToNode 见 [TelegramGateway.cs:14](W:/Work/Botgram.Backend/TelegramGateway.cs:14)。

Related/Profile 有身份分配副作用，删去看似未使用的计算可能改变后续稳定 ID。应先明确身份创建边界，再按操作懒构建或复用真正需要的结果；回归首次身份分配、事件、重启和 TL 二进制契约。优化 JSON 是自身质量改进，不能为了让 PB 预算通过而人为降低 JSON 性能。

### F6 中：预算及失败留证流程存在缺口，本次未发现它们造成误算

**等级：流程缺口 CONFIRMED；本次 raw-summary 一致。** 影响证据可靠性。

[evaluate-tls-budget.ps1:27](W:/Work/Botgram.Backend/tools/evaluate-tls-budget.ps1:27) 重算 raw p95，但后续主要据 summary 判定，[evaluate-tls-budget.ps1:45](W:/Work/Botgram.Backend/tools/evaluate-tls-budget.ps1:45)，缺少逐格 raw-summary 数值一致性断言；CPU 也取 summary。完整矩阵结束后才写 raw/summary，[TlsTests.cs:244](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:244)，中途失败可能丢失详细现场。

本轮额外断言了 36 格、每格 200 样本、raw p95 及批 CPU 累加与 summary 一致，四套已有证据未发现不一致。建议增加逐格断言、失败 finally 落盘和完成度标记；未完成矩阵不能标 PASS。内存采样应标下界；当前 memory 不决定退出失败，exactPeakPrivate 标 INCONCLUSIVE，不能擅自补写成通过。

### F7 中：JSON 与 PB 的事务内 Session/取消保障不对称

**等级：代码差异 CONFIRMED；撤销竞态可利用性及性能贡献未验证。** 主要影响 JSON 正确性比较。

PB 在取得 Write 锁后复查 Session 并检查取消，JSON Gateway 入口只使用此前认证取得的 uid。若认证后排队期间 Session 被撤销或到期，JSON 缺少 PB 对应复查；这是待复现的 TOCTOU 窗口，不是已证明漏洞。PB 因正确性检查产生的必要成本不能删掉。

下一轮使用隔离账号和可控排队验证认证、撤销、获得业务锁的顺序。若确需统一，优先让 JSON 达到同等正确性保障。取消不保证业务未提交：PB 的最后一次取消检查发生在持久化前，之后取消或网络写失败仍可能已提交；必须使用同一 random_id 重试并返回原消息，不能重发成新消息。

## 5. 正式基准独立复算

### 5.1 统计结果

每格 200 正式请求；nearest-rank p95 为排序后零基 189，p99 为 197，见 [TlsTests.cs:239](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:239)。共有 36 格，即 7200 正式请求，加 720 预热请求。下表 r 为零基轮次，时间单位 ms，比例均为 PB/JSON。表内小数仅显示舍入值，阈值判断使用原值。

|r|并发|操作|JSON p95|PB p95|p95 比|CPU 比|
|---:|---:|---|---:|---:|---:|---:|
|0|1|State|7.1169|6.3874|0.8975|0.6667|
|0|1|History|24.0634|19.3937|0.8059|0.5970|
|0|1|Send|7.5374|22.4225|2.9748|1.3673|
|0|2|State|12.5113|38.9045|3.1095|1.0000|
|0|2|History|17.5653|39.0926|2.2256|0.5616|
|0|2|Send|14.1804|25.5662|1.8029|1.3778|
|1|1|State|20.0571|20.1154|1.0029|0.8478|
|1|1|History|16.0855|20.8617|1.2969|0.7500|
|1|1|Send|22.4019|7.5939|0.3390|0.7656|
|1|2|State|39.5583|12.7631|0.3226|0.5060|
|1|2|History|42.2670|13.1073|0.3101|0.8919|
|1|2|Send|15.0082|43.3624|2.8892|1.2000|
|2|1|State|21.4875|17.9678|0.8362|0.6500|
|2|1|History|8.9760|21.1448|2.3557|0.8816|
|2|1|Send|7.8706|21.5673|2.7402|1.1000（边界通过）|
|2|2|State|27.0027|12.7923|0.4737|0.8478|
|2|2|History|39.4940|13.6632|0.3460|0.4512|
|2|2|Send|40.4763|13.8829|0.3430|0.6338|

p95 失败 8/18，CPU 失败 3/18，HTTP 错误 0。不能把 1.1000 的显示舍入误判为超过 1.1。

其他已有运行亦独立核对：目录后缀 a366200aaf9d417f8820f3d3ab75a1a5 为 p95 失败 7、CPU 失败 2；20d19eeab95741379284d25a7067d20a 为 6、2；3ceb53c2534644aa8e11cdf4f4577edc 为 5、2。均为 7200 正式加 720 预热、HTTP 200、raw-summary 一致。这些不是本轮新增实验，也不保证彼此运行条件完全等同，不能任意合并为一个统计总体。

### 5.2 公平性：成立部分

基线有 3 用户、3 Session、1 会话、100 消息、101 事件。每格复制同一 baseline 并启动独立 server，见 [TlsTests.cs:53](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:53)、[TlsTests.cs:159](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:159)。两协议互不继承另一方的数据库增长。

24 份读类最终快照与 baseline 字节相同；12 份 Send 最终为 320 消息，仅消息、事件及对应 nextID 变化。Send 的正式阶段由 120 增至 320，增长序列对两协议一致，但它不是固定规模的静态数据库测试。每用户总计 220 次调用低于 300/min 限额，且全部 HTTP 200，没有限流导致这些尾部的证据。

History 都请求 100 条并检查业务内容；请求及响应媒体、PB correlation/outcome 和业务语义都有验证，[TlsTests.cs:190](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:190)。本轮未发现“PB 测大历史、JSON 测小历史”或“某一侧失败被当作快响应”的问题。

HTTP 计时从 SendAsync 到读完响应 body，不包含客户端构建请求及之后的业务验证，见 [Program.cs:1048](W:/Work/Botgram.Backend/Protocol.HttpTests/Program.cs:1048)。同一 server 使用共享 HttpClient，启动健康检查已通过 HTTPS，[Program.cs:973](W:/Work/Botgram.Backend/Protocol.HttpTests/Program.cs:973)、[Program.cs:1036](W:/Work/Botgram.Backend/Protocol.HttpTests/Program.cs:1036)。不能假定每个请求都重新 TLS 握手。

### 5.3 公平性：限制及反例

- **构建模式**：最终被测后端 PDB 指示 debug-plus/DEBUG。两协议同构建，故不存在已证实的“一侧 Debug 一侧 Release”；但 Debug 性能不可直接外推生产 Release。ServerGC 已开启，也不能据此认定 GC 是尾部原因。
- **运行顺序**：三轮首协议不平衡，操作始终 State→History→Send，并发始终 1→2。c1 Send 中 r0/r2 PB 后且慢、r1 JSON 后且慢，支持时序影响的可能性；但 c2 Send 中 r1 PB 先仍慢、r2 PB 后却快，顺序不能单独解释全部结果。
- **并发模型**：[TlsTests.cs:184](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:184) 以 WhenAll 批屏障推进。并发 2 确实有两个异步请求，但快请求结束后不会立即补位，不是持续两个在途请求，更不是固定到达率的开放模型。不能从这个测试外推高负载吞吐或排队容量。
- **CPU**：使用后端 TotalProcessorTime 和 QueryProcessCycleTime，[Program.cs:1204](W:/Work/Botgram.Backend/Protocol.HttpTests/Program.cs:1204)。每协议 2700 批中 JSON 1849 批 CPU 为 0，PB 2005 批为 0，非零为 15.625 ms 倍数。累计窗口较短时，几次量化跳变就能改变 10% 判断。CPU 是后端进程量，不直接把测试客户端 CPU 加进去，但批窗口会涵盖请求构建/验证期间的后端活动。
- **CPU 反证**：r0c2 Send CPU 比 1.3778，而 cycles 比 0.9716，不支持直接声称“PB 多做 37.8% 计算”。cycles 也受频率等因素影响，只是交叉证据，不能替换既有预算把 FAIL 改 PASS。
- **内存**：[Program.cs:1259](W:/Work/Botgram.Backend/Protocol.HttpTests/Program.cs:1259) 目标 2 ms 采样不等于实际间隔；观察到最大间隔 18.7662 ms。采样含预热；PeakPagedMemorySize 是进程 lifetime 指标，[Program.cs:1216](W:/Work/Botgram.Backend/Protocol.HttpTests/Program.cs:1216)。采样峰值只能作为下界。
- **网络**：缺少连接 ID、实际握手次数及 HTTP 版本逐请求遥测。已有健康预热和连接复用不能证明绝无重连，也不能指定 TLS 为根因。该 loopback 证据不代表 Android 真机、局域网或公网端到端表现。
- **重试**：正式 Send 使用新 key，预热与正式 key 不同，[TlsTests.cs:187](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:187)，因此这张性能表不验证重复 random_id 重试性能与取消语义。

## 6. 插桩证据：支持什么、不支持什么

源码计时为 inclusive wall time，[RpcDiagnosticStage.cs:19](W:/Work/Botgram.Backend/RpcDiagnosticStage.cs:19)。父子阶段不能相加；进程 GC delta 不是单请求分配或 GC 因果。诊断中间件也有自身日志和计时开销。

独立插桩运行每实例 220 条，剔除前 20 条预热，共 7200 正式请求，JSON/PB 各 3600；各取 elapsed 最慢 180 条为最慢 5%。下表指标是先逐请求计算 `(lock_ms + flush_ms) / elapsed_ms`，再取标准中位数；不是总和比，不是两个阶段中位数相加。偶数样本使用中间两个值的平均数。

|协议|全体中位占比|全体 >50%|全体 >80%|最慢 5% 中位占比|最慢 5% >50%|最慢 5% >80%|
|---|---:|---:|---:|---:|---:|---:|
|JSON|77.7895%|3586/3600|1236/3600|93.0656%|179/180|176/180|
|PB|80.1868%|3587/3600|1838/3600|96.0271%|177/180|174/180|

两个极慢样本，单位 ms：

|样本|elapsed|Write 等锁|Flush|mapper|响应 encode|persist encode|replace|进程 GC delta|
|---|---:|---:|---:|---:|---:|---:|---:|---|
|JSON r1c2 Send|83.7900|64.3179|17.4099|0.3159|0.1406（含输出）|1.0111|0.3947|0/0/0|
|PB r2c2 State|80.0941|58.2713|20.4288|0.0245|0.0081|0.8091|0.3906|0/0/0|

原始来源为 [backend-27028.stdout.log](D:/BotgramBuild/tmp/p6-http-runs-198250e918904a8d9e18cfa04898194b/backend-27028.stdout.log) 与 [backend-30996.stdout.log](D:/BotgramBuild/tmp/p6-http-runs-198250e918904a8d9e18cfa04898194b/backend-30996.stdout.log)。该证据支持这次插桩尾部被等锁和 Flush 墙钟主导，且这两个极慢请求没有观测到进程 GC 计数变化。它不证明所有尾部都与 GC 无关，不证明 Flush 内全部时间是物理磁盘工作，也不证明正式运行相同轮次有相同阶段分布。

## 7. Q1–Q7 的直接回答

**Q1：PB 相比 JSON 增加哪些额外开销？** 请求 envelope/版本/结构校验、事务内 Session 与取消复查、PB DTO 映射、UUID/TL ID 反查、事务内 ToByteArray/大小验证，以及 Send 两处 Message 的独立编码。相对地，JSON 有自身反射/节点、预计算及最终编码成本。当前 PB 没有先转 JSON 再进 Gateway，也没有已发现的 body.ToArray 重复整包复制。

**Q2：是否有锁瓶颈？** 有。Read 与 Write 同 gate，所有 RPC Write 串行，认证也会被持久化阻塞。Write 等锁计时不能覆盖认证 Read 等待；尚缺总持锁时长和认证等待的统一 trace。

**Q3：同步持久化是否是原因？** 它是确定的共享成本，且强烈解释插桩运行尾部；仍不能单独解释正式协议间比值。每次成功 RPC 都全库保存，读类也一样。Flush(true) 的正确性要求应保留。

**Q4：共享业务后为何仍有差异？** 请求解析、身份适配、预计算、DTO/JSON 节点构造、响应 schema、编码位置、Session/取消保障与输出路径不同；全库持久化还会与排队、系统时序相互作用。必须比较完整入口，也要单独隔离 codec/mapper，不能混为一个实验。

**Q5：测试公平吗？** 相同基线、增长、构建、业务量、计时边界与 HTTP 成功条件基本成立，预算结果可复算；Debug、三轮不平衡顺序、预热、批并发、CPU 量化和缺失网络遥测限制因果解释。结论是“该配置下未达预算”，不是“协议本体已证明更慢”。

**Q6：CPU 超限说明什么？** 按既有 TotalProcessorTime 规则确有三个超限配对；目前不能量化其中多少来自真实额外计算、量化边界、JIT/后台活动。r0c2 Send 的 cycles 反例尤其要求长窗口复测。不能拿延迟的 Flush 证据直接解释 CPU 超限。

**Q7：目前最可能的三个因素是什么？** 按证据和解释范围排序：（1）共享串行提交与等待放大，最能解释插桩尾部；（2）独立进程顺序、预热/Debug 与系统时序造成的测量波动，是正式配对反转的强候选；（3）协议适配和事务内响应编码差异，是确定存在的差异机制，但当前未证明主导约三倍 p95。它们可能共同作用，不能当作三个已经分别定量证实的根因。

## 8. 安全修复顺序与最小实验

本节是下一轮执行建议，**尚未实施**。保持原预算并保留原基准作为可比较锚点，新增实验明确另行标识；不要用新的较宽松判定替代旧 FAIL。

### A：已证明的问题，优先补齐证据及正确性

1. 预算工具增加每格 raw-summary 一致性、样本数和完整矩阵检查；失败时可靠保存已完成样本及异常上下文，不记录凭据/消息全文。
2. 固定 Release 二进制、构建模式、依赖版本与哈希；保存原有 Debug 结果，不追溯改写。为阶段观测补认证等锁、总持锁、提交字节数及响应大小，标明 inclusive/exclusive。
3. 处理 JSON/PB 保障不对称时，保留 PB 事务内 Session 复查与幂等；先用隔离竞态用例验证，再统一契约。该项是正确性加固，不承诺带来性能收益。

这些改动各自小步提交给下一轮审查，不能顺势重构 Store 或全局缓存。

### B：强证据，先做最小对照再改业务

|实验|检验假设与控制变量|应记录的原始结果|支持/反驳条件及剩余解释|
|---|---|---|---|
|B1 Release 配对复测|同机、同 baseline、同 TLS、同预算；只改变构建模式；多组 AB/BA 平衡，预先固定顺序；预热至可观察稳态|每请求 wall、状态、body 大小、每格快照/哈希、构建模式、长窗口 CPU/cycles、运行顺序|若反转显著减少，支持构建/时序贡献；仍需区分 JIT、调度和存储影响，不能把 Release 收益全归 codec|
|B2 同次运行完整阶段追踪|相同二进制与数据；低开销 trace 关联认证 Read、Write wait/hold、mapper、编码、persist、Flush、replace；另做关闭插桩对照|逐请求阶段及 trace、总 CPU、诊断开销对照|才能把正式失败单元与阶段分布关联；Flush 内原因仍需 OS/文件系统证据|
|B3 codec/mapper 隔离测量|只用隔离、不可变的等价业务快照；同语义和规模、足够长窗口；明确这不是端到端预算|解析、映射、CalculateSize/编码、分配、扫描次数、响应字节；检查结果等价|能判断 PB 适配/重复字段是否值得改；不能据内存实验替代持久化 HTTP 结果|
|B4 固定规模与增长规模对照|读类保持不变；Send 记录每次规模，按相同增长位置配对；单独扩大用户/会话/消息规模|数据库字节、事件数、提交时间、查询/映射时间|判断全库保存或扫描增长；扩大规模后发现的瓶颈不自动等于当前小基线根因|
|B5 Session/取消/重试边界|独立数据库中可控排队、撤销/过期、提交前后取消、网络输出失败；重试保持相同 random_id|状态码、提交后快照、消息数量和 ID、事件与身份变化|证明单次业务效果与授权边界；不能要求“客户端取消必定未提交”|

B1/B2 最少应先完成，才能收紧正式配对的因果解释。B3 可指导局部适配优化。所有负载实验只能启动本轮自有进程、随机 loopback 端口、独立临时目录及数据库，不接入正式账号，不碰 18524、Caddy 或正式数据。

若 B3 证实 PB 反查成本显著，优先用请求局部映射减少重复扫描；若 JSON 冗余显著，先分离身份副作用，再减少重复 Related/Owned；若 B2 证实编码占用显著，按只读/变更操作分别设计不可变快照及预提交验证，不一刀切移锁外。

### C：可能存在，目前不直接修改

- WAL、增量持久化或 group commit 是中长期架构候选。需要持久化成功后返回、崩溃恢复、租约、顺序、回滚及幂等完整设计；现有证据不足以授权直接替换 Store。
- 调整 GC、线程池、HTTP/TLS 或系统存储设置，必须先有针对性证据。当前不应关闭安全校验、改变正式网络/防火墙，或强制依赖未观测的 TLS/GC 解释。
- 改 PB Send schema 去重需检查 Android 消费契约和版本协商，不能仅因两个字段重复就删除一个。
- 直接将 History/State 改 Read、把所有响应编码移锁外、替换全局锁，均需要先解决第 3 节的副作用和提交语义。

### 改动后的必要回归

|范围|必须保持的结果|
|---|---|
|权限与 Session|非成员拒绝；撤销/过期不因排队恢复授权；响应不泄漏其他用户数据|
|幂等|相同 random_id 并发、超时重试、取消后重试和重启后重试只产生一条消息，稳定返回原 ID|
|持久化|成功响应对应已持久化状态；Serialize/Flush/Move 失败可恢复；第二进程租约拒绝；不清空库修复|
|编码与大小|编码异常、超限与提交边界符合已定义契约；PB/JSON 业务语义等价；TL flags、实体、回复及稳定身份不变|
|同步|事件顺序、每用户 pts、历史/差分分页、回放和已读游标保持|
|性能|原预算照常报告，新增 Release/阶段实验分开报告；保存所有失败现场，说明内存采样限制|
|Android|涉及响应契约时验证真实 TL 二进制编解码；真机功能另验，不能由后端或宿主测试替代|

## 9. 本轮实际复算与证据索引

### 9.1 实际执行的离线验证

交付脚本 [botgram-audit-recompute.py](W:/Work/Botgram/botgram-audit-recompute.py) 已执行成功，退出码 0。实际命令为：

```powershell
python "W:\Work\Botgram\botgram-audit-recompute.py"
```

- **假设**：正式 FAIL 能由 raw 复现，且 summary 未偏离原样本；插桩尾部占比能从 stdout 重算。
- **控制**：只读固定路径的既有 JSON 与日志，不启动服务、不改样本；每格断言 200、总格数 36、诊断总正式数 7200。
- **原始输入**：下列正式 raw/summary、诊断执行日志所指向的 stdout。
- **结果**：8 个 latencyFailures、3 个 cpuFailures、0 errors；逐格 raw p95 和 CPU 累加与 summary 一致；插桩标准中位数见第 6 节。
- **判断**：支持统计复现及该次诊断阶段分布；不构成新负载重现，不测试磁盘因果、编码移锁外收益、Session 竞态或真机行为。
- **限制**：复算脚本依赖当前机器已有 D 盘证据；它不是独立生成全部负载的便携工具。完整证据含敏感测试上下文时应受控保管，本报告不复制令牌、密码或消息全文。

本轮还审查了四套既有 raw/summary、数据库快照与测试源码。历史负载的启动命令和执行上下文以原执行日志为准，不能把它们写成本轮实际执行。第 8 节所有实验均未执行，因而没有虚构实验命令、数值或通过结论。

### 9.2 固定正式证据

正式结果：[benchmark-tls.json](D:/BotgramBuild/tmp/p6-http-runs-fe35c2158a3040cfa48bbfb29bc72e54/benchmark-tls.json)。

SHA-256：`AF93C40A591207D7C897DC4771289B5DF3D80487360E9EA75A4F0C3B8802F2F0`

正式原样本：[benchmark-tls-samples.json](D:/BotgramBuild/tmp/p6-http-runs-fe35c2158a3040cfa48bbfb29bc72e54/benchmark-tls-samples.json)。

SHA-256：`00C9BA7A902F4115FA46656052B3CFBF8A3F51631E50C84422B40EBD5101756D`

被测后端：[Botgram.Backend.dll](D:/BotgramBuild/tmp/p6-next-delivery-bin/Botgram.Backend.dll)。

SHA-256：`1C1411E12D2B7248EF49CA50DD2FA57FBA549AE2B27C15FA07986E72FCD3CB57`

被测测试程序：[Protocol.HttpTests.dll](D:/BotgramBuild/tmp/p6-next-delivery-bin/Protocol.HttpTests.dll)。

SHA-256：`8D65BABA7D7FF3E1B91A5505F9AA9137774CCE3F054B5FC4FA49FF97912D5CBE`

辅助上下文：[p6-next-evidence-manifest.json](D:/BotgramBuild/tmp/p6-next-evidence-manifest.json)、[p6-next-delivery-benchmark.log](D:/BotgramBuild/tmp/p6-next-delivery-benchmark.log)、[p6-next-diagnostic.log](D:/BotgramBuild/tmp/p6-next-diagnostic.log)。诊断样本位于目录 `p6-http-runs-198250e918904a8d9e18cfa04898194b`，由执行日志定位各进程 stdout；日志中历史 PID 仅用于证据关联，不用于操作当前进程。

测试统计实现主要见 [TlsTests.cs:181](W:/Work/Botgram.Backend/Protocol.HttpTests/TlsTests.cs:181)；预算见 [evaluate-tls-budget.ps1:11](W:/Work/Botgram.Backend/tools/evaluate-tls-budget.ps1:11)；阶段解析规则见 [summarize-rpc-stages.ps1:13](W:/Work/Botgram.Backend/tools/summarize-rpc-stages.ps1:13)。

历史说明 [PROTOBUF-TEST-RESULTS.md:431](W:/Work/Botgram/PROTOBUF-TEST-RESULTS.md:431)、[PROTOBUF-IMPLEMENTATION.md:113](W:/Work/Botgram/PROTOBUF-IMPLEMENTATION.md:113) 记载了最终 FAIL、测量增强和限制；本报告没有把历史开发者的解释当作独立证明。

## 10. 审计边界

两轮独立交叉验证已核对主要源码发现、调用链和测试公平性；它们提高了静态结论的可靠性，不能代替控制变量的运行因果实验。本轮没有改变产品、启动或升级正式服务、接触正式数据库、修改端口/证书/ACL、删除 JSON/WSS、进入后续迁移阶段或创建 Git commit。

报告完成了共享尾延迟机制和协议路径差异的定位，并复现了正式预算失败。正式失败配对的唯一原因仍需第 8 节 B1/B2 最小实验收敛；在此之前，不应宣称已完全解决 PB 性能问题，也不应承诺某个局部优化必然使预算通过。
