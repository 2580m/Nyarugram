# Botgram 端到端加密（首期）设计与实施说明

## 2026-11-07 按发送方向启用与永久占位

本节为当前产品规则，取代下文旧的“双方确认前禁止发送”和“关闭需要双方确认”规则。每一方独立开启、关闭自己发出消息的保护：

| 当前状态 | A 发给 B | B 发给 A |
|---|---|---|
| 双方都未开启 | 普通消息 | 普通消息 |
| 仅 A 开启 | B 永久看到“此信息开启了端到端加密” | 普通消息，A 正常阅读 |
| 双方都开启 | 新消息使用 Signal 会话，双方可读 | 新消息使用 Signal 会话，双方可读 |
| A 关闭、B 仍开启 | 普通消息，B 正常阅读 | A 对这时收到的新消息永久看到占位 |

一方开启时写入真实的 Telegram 居中服务消息：开启方看到“你开启了端到端加密”，未开启方看到“对方开启了端到端加密”。设置行显示的是本人是否开启，管理列表也只列本人开启的聊天。未开启方不需要设置 PIN 或发布预密钥即可收到占位，也可以继续正常发送普通消息。单方开启仍仅支持已实现的一对一文字；其他未支持的发送类型保持明确拦截。

单方阶段是发送者密封、接收方永久不可读；尚未建立双方可读的 E2EE 会话。发送者使用本机 vaultKey 经域隔离派生的密钥认证加密正文，并通过已有事务日志保存本机显示文本和原信封；服务器只接收密文和路由元数据。信封 `message_type=4`、`recipient_device_id=0`，不创建、上传或以后补发接收方密钥。接收方在锁定和解锁状态下均直接显示占位，之后开启也不尝试恢复这些旧消息。发送者可在自己的设备上通过 PIN 阅读本机保存的正文；本机密钥丢失后的恢复边界不变。

双方开启后的新消息继续使用 Signal 的类型 2/3、身份确认和前向保密机制；单方类型 4 不具有双向 Signal 会话的安全属性。旧单方信封不转换、不重新加密、不补发。每次状态变化更新 epoch 和服务消息，服务端按发送者校验密文/普通消息方向；并发时不符合当前模式的发送明确失败，不自动改为明文。

沿用现有 PENDING 状态表示“仅 initiator 开启”，ACTIVE 表示双方开启；CLOSED 表示双方关闭，保留记录以便读取历史。不增加账号或聊天 ID，也不更改现有字段编号。加密操作请求的 `protocol_version` 升至 2，旧语义的操作请求明确拒绝；Signal/单方密文信封本身的 `protocol_version` 仍为 1。这次 APK 必须与匹配的新版自部署后端配套，未自动升级运行服务。

## 2026-11-07 第二轮复核修复：提交恢复与后台显示

本节取代下文旧版“历史 fsync 后单独替换 vault 即构成事务”的描述。所有协议写入由 E2EE worker 串行执行；取消 dirty 回调和锁定时的最终快照写回，PIN 改写也在同一提交锁内读取和替换文件。

消息提交先在候选 vault 上执行，再将新封装 vault、显示记录以及发送时的原信封一同写入由 vaultKey 认证加密的 `vault.bin.txn`。事务意图同步完成后，历史追加记录保持不可读，直到 vault 原子替换、事务清理完成才发布历史与内存协议状态。任一步失败均锁定；重新解锁时先验证 PIN/设备层，再认证并重放事务，最后安装历史和协议状态。崩溃恢复可能完成一条此前未返回成功的操作；同一 random_id 的发送重试读取已保存的原信封，不重新消耗棘轮。此机制不提供送达保证，设备损坏及操作系统不保证的存储持久性仍有边界。

历史扫描会截断未完成的尾记录再追加。旧缓存迁移和压缩在完整的同目录临时文件构建并同步后才替换原文件；认证或写入失败保留原文件。不支持覆盖 rename 时保留 `.bak`，读取前恢复中断替换。旧整文件缓存的 SHA-256(信封字符串) 键原样保留，显示路径按真实旧键回查；此前已被淘汰或覆盖的记录无法恢复。

身份确认保留第一方在 SUSPENDED 状态下确认的准确公钥与 generation，并持久化公开的确认信息；另一方完成后可继续建立会话。确认弹窗展示待替换身份的安全码，点击操作携带该弹窗快照，后续身份变化不能继承确认。发送前也检查实际可信身份与 generation，不能在修复会话期间继续沿用旧 session。

消息构造/绘制路径只查内存；密文解密、历史查询、事务和 fsync 在 worker 上运行，完成后按账号/代际和显示版本刷新对应消息。普通文字发送在构造落库消息前异步完成加密提交，再回主线程沿用原发送流程。锁定清理显示缓存与已登记消息对象中的解锁文本，不保留后台可用的解锁会话。实际验证以本轮交付说明为准，不能将宿主检查等同于真机验收。

本文是仓库内可审阅的实现依据，不是最终交付的替代品。首期仅覆盖一对一文字消息；附件、语音、通话、群聊、多设备同步与换机恢复不在范围内。客户端保留原 `ProfileActivity`、`ChatActivity`、会话列表、气泡和设置导航。

## 1. 目标与边界

- 同一账号的全部加密聊天共用一个四位数字 PIN（允许前导零），不同账号独立；双方 PIN 无需相同且不传递。
- PIN 只用于解锁本机密钥库；消息密钥由密码学随机生成，不使用 PIN 直接加密正文。
- PIN 无法找回。忘记时只能在用户明确确认不可逆后销毁本机密钥并重建会话；不删除对方持有的消息。
- 后端只保存公钥、预密钥、必要会话状态、密文和投递元数据，不持有 PIN、私钥、明文或可替代解锁的主密钥。
- 仍保留 HTTPS/Bearer；后端可见双方身份、时间和消息大小等投递元数据。

### 1.1 威胁模型

| 对手/场景 | 本方案结果 |
|---|---|
| 诚实但好奇或数据库泄露的后端 | 只见公钥、信封路由字段、密文、时间/大小；无 PIN、私钥、明文；不能解密历史。 |
| 恶意或失陷后端篡改/重放/替换信封 | 内层 libsignal 密文认证会话、设备、epoch、random_id 与正文；外层字段不一致或密文被改会拒绝；服务端另做幂等和状态校验。服务端可阻断投递（DoS），不能伪造可解密消息。 |
| 网络攻击者 | HTTPS/WSS 仍为传输层；不能读取正文，不能把加密聊天降级为明文（服务端状态检查 + 客户端不自动回退）。 |
| 设备被完全控制且可调用 Keystore | 攻击者可尝试四位 PIN（最多一万种）；Keystore 不可导出与 PBKDF2 提高离线成本，但不能抵抗完全失陷设备。解锁后的内存明文和正在运行的 App 可被读取。 |
| 本机丢失且未解锁 | vault、私钥、会话与加密显示历史均不可读；重装/清数据/Keystore 失效后旧历史不恢复。 |
| 恶意对端 | 对端本来就能看到发送给它的明文并可截图/转发；E2EE 不阻止对端泄露。 |
| 重放/乱序/重复投递 | libsignal 重复消息检测、跳过密钥上限；服务端 random_id 幂等与消息 ID；客户端不推进错误状态。 |
| 旧客户端/不支持的客户端 | 活动加密区间拒绝明文发送；旧客户端收到新信封不会成功解析，不提供 JSON/明文回退。 |
| 服务端/管理员 | 无 PIN 恢复、无管理员解密、无托管主密钥；只能删除/阻断投递。 |

## 2. 协议选型

选用 **Signal Protocol**（X3DH/PQXDH + Double Ratchet）的官方实现 `org.signal:libsignal-android:0.86.5`（含 `org.signal:libsignal-client:0.86.5` 的 Java 协议类与 `libsignal_jni.so`）。该实现具备异步预密钥会话建立、身份密钥、前向保密、密钥演进、重放/重复消息检测与跳过密钥上限。

- 版本固定 0.86.5；AAR SHA-256 `771E9A188FA30D96CEF5433638E7C9EC00975C7AFB64F94CDFF97281330A0B93`，client JAR SHA-256 `D300C272DAE155B21AE8988F51B6B493001CD2D83F5D2F9AE15E743C7FF971BD`（2026-11-06 从 Maven Central 下载）。
- 许可证为 **AGPLv3**。本 App 源码头为 GPL v2 or later，按 GPLv3 §13 可与 AGPLv3 组合；分发时必须保留 libsignal 的许可证与声明（AAR 自带 `assets/acknowledgments/libsignal.md`）。部署者若再分发需遵守 AGPL 源码提供义务。
- Android ABI：AAR 自带 arm64-v8a、armeabi-v7a、x86、x86_64 的 `libsignal_jni.so`，最小 API 23；测试库 `libsignal_jni_testing.so` 和桌面平台原生文件在打包时排除。剥离调试符号后每个 ABI 约 6.7 MiB。
- 构建成本：AAR 约 122 MiB、client JAR 约 101 MiB（仅首次下载到 D 盘 Gradle 缓存），另需 Kotlin stdlib 2.1.0 与 coroutines；APK 预计增加约 20–30 MiB。相对自研密码协议，这是可接受的供应链成本。
- 不自行拼装密码协议；libsignal 负责会话与消息认证。Botgram 只增加版本化外层信封与业务元数据。

## 3. 身份与设备

- 账号身份：规范服务器 origin（`Protocol:ServerDomain` / 客户端保存的完整 origin）+ 后端稳定用户 UUID。Gram ID、用户名和账号槽位都不作为永久主键。
- 本机设备身份：每个账号每个安装生成一次 `deviceId = 1`（首期单设备）与 Signal 身份密钥对、registrationId。设备私钥与会话状态只存在于 PIN 保护的本机密钥库。
- 服务端 `e2ee_devices` 保存该用户当前设备的**公开**身份密钥、registrationId、签名预密钥、Kyber 预密钥及一次性预密钥。重新安装或清除数据会生成新身份密钥；发布新设备时服务端把涉及该用户的会话置为 `SUSPENDED`，要求双方显式重新确认。
- 安全码：两端用 `NumericFingerprintGenerator(2)` 对（按 UUID 排序的双方 UUID + 身份公钥）生成相同的数字安全码。首期首次信任是 TOFU；身份变化不静默接受。

## 4. PIN 与 Keystore

- 密钥库文件：`<files>/botgram-e2ee/<originHash>/<userUuid>/vault.bin`。`originHash` 为规范化 origin 的 SHA-256 前 16 字节，防止不同服务器/账号串库。
- 文件格式 v2：magic、版本、PBKDF2 迭代次数、16 字节随机盐、`outerBlob`、`vaultBlob`。文件内不再存在可单独恢复 `vaultKey` 的 PIN 或设备旁路。
- 随机生成 32 字节 `vaultKey`；`vaultBlob` 使用 AES-256-GCM(vaultKey) 加密协议状态，AAD 为 `botgram-e2ee-vault-v2`。
- 保护结构是**嵌套而非并列**：
  1. `pinBlob = AES-256-GCM(PBKDF2-HMAC-SHA256(PIN, salt, 120000), vaultKey)`，AAD 绑定版本、迭代次数与盐。
  2. `outerBlob = AndroidKeystoreAES-256-GCM(pinBlob)`，设备密钥不可导出，别名按 originHash+userUuidHash。
- 解锁顺序：先解封 Keystore 外层，再派生 PIN 解封 `vaultKey`，最后解封 `vaultBlob`。只持有文件和正确 PIN、但没有对应设备密钥时无法解封；只有设备密钥而没有正确 PIN 时同样只能得到无法认证的 `pinBlob`。PIN 错误表现为 `PinFailure`，设备密钥缺失/失效表现为 `DeviceFailure`，两者在 UI 上区分。
- Keystore 密钥保留 `randomizedEncryptionRequired=true`；加密时不传入调用方 IV，而是由 Keystore 生成并通过 `cipher.getIV()` 读取，与密文一起保存；解密时使用保存的 IV。关闭随机化要求或导出软件密钥不属于实现选项。
- KDF 为标准 PBKDF2-HMAC-SHA256：优先使用 JCE `PBKDF2WithHmacSHA256`（Android API 26+）；API 23–25 使用经 RFC 8018 已知向量与 JCE 交叉验证的兼容实现，不自创 KDF。旧版本的非标准派生仅用于读取 v1 文件。
- 旧格式 v1（PIN 与设备两个独立封装）只在正确 PIN 与对应设备密钥均验证通过后读取；首次成功解锁会把同一 `vaultKey`、同一协议状态原子改写为 v2。改写失败时保留可读 v1 文件并在下次解锁重试；读取 v1 只为迁移，不作为长期存储。迁移前已经复制出去的 v1 文件副本的弱保护无法追溯消除，必须在文档中说明。
- PIN 修改：验证旧 PIN 后仅重新封装 `vaultKey`（v1 同时升级为 v2），不更换账号 UUID、TL ID、聊天关联、身份密钥或对端已验证身份；写入失败保留原文件。
- 威胁边界：四位 PIN 只是本机便利锁，不是消息密钥。持有文件副本并知道正确 PIN 的攻击者仍可在设备外离线穷举 PIN（文件不提供第二层离线障碍），因此不能宣称四位 PIN 能抵御完全失陷设备上的猜测或内存攻击；设备完全失陷、解锁后的内存、Keystore 可被任意调用时，PIN 不提供强保护。仅知 PIN、仅有持久化读权限、无设备密钥时无法读取 vault。
- 忘记 PIN：需二次确认，删除密钥库与设备发布（服务端 `e2ee_reset`），重新设置 PIN 并生成新身份；旧消息标记为不可解密，不承诺清除对方/备份/闪存副本。
- 退避：失败次数与下次可试时间持久化到按账号/origin 隔离的 SharedPreferences；指数退避 30s、1m、2m、5m、15m、1h 封顶，成功清零。解锁在单线程队列内执行，并在实际派生开始前再次检查退避，排队请求不能绕过前一次失败产生的等待。不做自动毁钥。
- 锁定：冷启动、应用退到后台、切换账号即递增操作代际、清除内存 `vaultKey`、关闭历史文件并清空内存 LRU。后台只保存密文，PIN/解锁密钥不落盘；锁定后迟到的解锁结果不得安装密钥或触发旧 UI 成功回调。

## 5. 服务端状态机

会话记录 `e2ee_conversation`（按 conversationId）：

| 状态 | 含义 | 允许发送 |
|---|---|---|
| `DISABLED`（无记录） | 未启用 | 明文 |
| `PENDING` | 邀请已发出，等待对方确认 | 明文（尚未建立） |
| `ACTIVE` | 双方确认，边界已设置 | 仅密文信封 |
| `CLOSING` | 一方请求关闭，等待另一方确认 | 仅密文信封 |
| `SUSPENDED` | 身份密钥变化，等待双方重新确认 | 拒绝 |
| `CLOSED` | 双方确认关闭并设置关闭边界 | 明文 |

操作（幂等）：
- `INVITE`：仅 direct 会话、双方均非自己/机器人、双方均有已发布设备；否则 `E2EE_PEER_UNSUPPORTED` / `E2EE_TARGET_INVALID`。创建 `PENDING` 并写入服务消息。
- `ACCEPT` / `DECLINE`：仅 PENDING 且由被邀请方执行；接受前确认双方设备仍存在，接受后 `ACTIVE`，`epoch+1`，`boundary = 当前最大消息 ID`。
- `CANCEL`：仅 PENDING 且由发起方执行，回到 `DISABLED`。
- `CLOSE_REQUEST` / `CLOSE_CONFIRM`：ACTIVE → CLOSING → CLOSED；关闭边界在确认时设置。
- `CONFIRM_IDENTITY`：SUSPENDED 下双方各自确认新身份；双方都确认后 `ACTIVE`、`epoch+1`、新边界。
- 所有操作在同一个 `Store.Write` 中读取、校验、写状态、写服务消息、追加事件；重复请求返回当前状态，不产生重复服务消息。

发送校验（同一事务内）：
- ACTIVE/CLOSING：`text` 必须为空、实体为空，必须携带 `e2ee` 信封；否则 409 `E2EE_PLAINTEXT_FORBIDDEN`。信封必须版本 1、会话一致、发送者设备等于发送者当前设备、接收设备等于对端当前设备、`random_id` 等于请求幂等键、密文长度 1..65536。
- SUSPENDED：409 `E2EE_IDENTITY_CONFIRMATION_REQUIRED`。
- DISABLED/CLOSED：拒绝信封，502/409 `E2EE_NOT_ACTIVE`，明文按原逻辑。
- 幂等重试：同一 `random_id` 且信封字节一致返回原消息；不一致 409 `RANDOM_ID_DUPLICATE`。
- 数据保存：`Message.Text` 为空，信封单独保存；日志、错误、搜索、摘要、备份只出现密文。服务端不解析、不索引、不富文本处理密文。

事件与客户端同步：不新增事件类型；启用、接受、关闭、身份变化都追加只含文本的**服务消息**（`ServiceKind="e2ee"`），通过原 `message.created`/pts 差分到达客户端。客户端处理到该服务消息时刷新 `E2EE_STATUS`。这样避免改动事件 schema 与 pts 语义。

## 6. 密文信封

外层 `E2eeMessage`（protobuf，服务端可见的路由元数据）：
`protocol_version`、`conversation_id`、`sender_user_id`、`sender_device_id`、`recipient_user_id`、`recipient_device_id`、`key_epoch`、`random_id`、`ciphertext`（libsignal `CiphertextMessage.serialize()`）。

内层 `E2eeInner`（被 libsignal 加密，服务端不可见）：
`protocol_version`、`conversation_id`、`sender_user_id`、`sender_device_id`、`recipient_user_id`、`recipient_device_id`、`key_epoch`、`random_id`、`timestamp_ms`、`text`。

接收方解密后校验内层字段与当前会话/自身身份/对端设备/epoch 一致；不一致视为无效消息且不推进会话状态。外层只用于服务端路由与客户端选择会话，不单独作为身份认证依据。

客户端在 `TL_message.message` 中存放 `\u0001BGE1:` + Base64URL(外层信封) 作为密文的本地/传输载体：
- 本地 SQLite 的 `data` 列因此只保存密文；原 `MessagesStorage` 不需要新列。
- `MessageObject` 构造时识别前缀并在内存中解密；锁定或失败时显示固定占位文案，不写回 `messageOwner.message`。
- 发送前在 `SendMessagesHelper` 内完成加密，数据库/发送队列/草稿只接触信封。
- 服务消息与普通明文消息保持原格式。

## 7. 本地存储与泄漏边界

- 密钥库、会话记录、预密钥、已验证身份全部在 PIN 加密的 `vault.bin`；解密后的显示文本只存在于内存 LRU 与按账号加密的 `display.bin` 历史，不写回 SQLite 明文列。
- 可靠提交：发送与接收都在 vault 候选副本上完成加密/解密；先把显示文本追加到加密历史并 `fsync`，再原子替换 vault，只有两者都成功才把候选状态提交到内存并返回可发送信封或可显示文本。任一步失败时原棘轮、预密钥与信任状态不变；重试复用同一 `random_id` 和原信封，不重新消耗棘轮。
- 历史使用 vault key 认证加密（每条记录按稳定信封身份绑定 AAD），文件为追加式记录 + 偏移索引；内存只保留 256 条 LRU 与记录偏移。内存淘汰不再删除唯一可读副本，重启、解锁、关闭后重新开启仍可分页读取此前成功保存的历史。
- 加密消息的明文不进入 `messages_v2.data`、`replydata`、发送队列、pending task、草稿、SharedPreferences、搜索索引、日志或通知正文。
- 锁定/未解锁时 `MessageObject` 不显示正文、引用和搜索摘要；通知固定为“收到一条加密消息”，解锁后也不显示正文。
- 远程链接预览在加密聊天中禁用；转发、导出、收藏、分享首期阻止，复制仅在解锁的聊天页允许。
- 聊天页在启用/关闭边界显示服务消息“端到端加密已启用/已关闭”，由服务端在同一 pts 流写入。activating 前的明文历史不追溯加密。

## 8. 首期支持矩阵

| 操作 | 支持 | 说明 |
|---|---|---|
| 一对一文字发送/历史/差分 | 是 | 密文信封 |
| 回复（仅 reply_to 消息 ID） | 是 | 不发送引用明文；引用摘要可能显示占位 |
| 富文本实体 | 否 | 加密区间拒绝带实体发送 |
| 附件/语音/视频/贴纸/投票/位置/联系人 | 否 | 明确提示并阻止 |
| 转发/收藏/导出/分享 | 否 | 阻止 |
| 复制 | 是（解锁时） | 用户主动操作 |
| 编辑/撤回 | 否 | 服务端本就不支持 |
| 本地/服务端搜索 | 否（密文） | 不索引明文 |
| 系统通知正文 | 否 | 固定文案 |
| 多设备/换机恢复 | 否 | 新设备需重新确认 |
| 群聊/频道/机器人/自己 | 否 | 不提供成功入口 |

## 9. 实施与验证

- 客户端：`org.telegram.botgram.e2ee` 下实现 PIN vault、libsignal store、envelope、控制器；`PasscodeActivity` 增加隔离的 PIN 设置/验证模式；`ProfileActivity` 增加入口行；`PrivacySettingsActivity` 增加管理入口；新管理列表复用 `BaseFragment`。
- 后端：`E2eeService.cs` + `ProtobufRpcMapper`/`ConversationMessageService` 改造；JSON 快照新增可选字段（旧库缺省兼容）；Postgres/relational 明确返回 501，不静默降级。
- 宿主回归：`Tools/Verify-BotgramE2ee.ps1`（编译真实 Java 类 + libsignal-client，覆盖 PIN、安全码、握手、密文、篡改、重放、身份变化）；后端 `tests/e2ee.mjs` 覆盖状态机、权限、明文拒绝、幂等和迁移。
- 真实设备尚未验证：PIN 页面视觉、双机握手/收发、锁屏通知、后台锁定、ROM Keystore 差异。构建/宿主回归不能替代这些验收。

## 10. 实现落点与限制

- 客户端 `BotgramE2eeController` 按账号持有内存 vault；`BotgramE2eeVaultFile` 负责 PIN+Keystore 嵌套保护与 v1→v2 迁移；`BotgramE2eeVault` 实现 libsignal `SignalProtocolStore`；`BotgramE2eeCrypto` 负责身份/预密钥、会话、信封、事务式解密与安全码；`BotgramE2eeHistory` 是持久化加密显示历史。
- 异步 PIN/创建/解密任务在开始时捕获账号身份、目标路径、vault 实例与操作代际（`BotgramE2eeSessionGate`）。后台锁定、切号、退出、重置和会话替换递增代际，迟到任务在安装前与 UI 回调前再次校验，废弃结果不会写入新账号文件、清除新账号退避或显示成功。
- 发送 applicability 由 `BotgramE2eeSendPolicy` 决定：仅绑定账号、正数用户私聊、非收藏夹/非 Secret Chat 才进入 E2EE；群聊、频道、收藏夹、未绑定原生账号保持原发送路径。状态未知、刷新失败、已知关闭与活动加密聊天严格区分；未知不会当作 disabled，也不会允许已加密聊天发明文。
- 发送拦截位于 `SendMessagesHelper.sendMessage`：加密会话内只允许文字，清除实体、引用明文、链接预览与定时/媒体；协商中（pending）与身份需确认（suspended）直接阻止新消息，不自动降级为明文；加密失败或锁定同样中止，不写入本地发送队列。`BotgramProtobufRequestMapper` 将 `\u0001BGE1:` 前缀解码为 `SendMessageRequest.e2ee`，服务端要求正文为空。
- 接收映射位于 `BotgramProtobufResponseMapper`：`E2eeEnvelope` 变成带前缀的 `TL_message.message`；`MessageObject` 在构造时解密并只把明文放在内存 `messageText`。本地 SQLite、`replydata`、pending task 与通知只接触前缀密文/固定占位文案。
- 显示历史以 `conversationId|senderUserId|randomId` 稳定键定位，解决 Double Ratchet 密文只能消费一次、历史重载不能再次解密的问题；文件用 vault key 逐条认证加密，索引只保存偏移，内存 LRU 淘汰不影响磁盘副本。旧整文件 `display.bin` 缓存会在解锁时迁移到追加格式；修复前已丢失的历史无法凭空恢复。
- 会话复用由 `BotgramE2eeSessionPolicy` 判定：必须同时匹配本地已验证身份与设备 generation，不能只看 UUID/deviceId 是否存在；身份替换需用户确认当时展示的确切身份与 generation，等待期间再次替换时旧确认不自动信任新密钥。确认后删除旧 session、事务式建立新 session；安全码优先使用本地实际信任的身份公钥。
- 状态同步：服务端不新增事件类型，邀请/接受/关闭/身份变化写入 `ServiceKind="e2ee"` 的服务消息，通过原 `message.created`/pts 差分到达客户端；`MessageObject` 将服务消息映射回原 `TL_messageActionCustomAction` 并使用本地化文案。客户端在聊天页/资料页打开及身份变化服务消息到达时刷新状态。
- 服务端本机文件存储路径实现完整 E2EE；PostgreSQL/relational 校验路径对 E2EE RPC 明确返回 501 `BOTGRAM_RPC_UNSUPPORTED`，发送密文信封返回 501 `BOTGRAM_E2EE_BACKEND_UNSUPPORTED`。这是当前部署能力边界，不是自动明文回退。
- 首期只支持单设备（deviceId=1）。预密钥耗尽返回明确错误；未实现自动向对端补充预密钥。转发、导出、收藏、分享、编辑、附件、通话、群聊和换机恢复均明确阻止或不存在成功入口。
- 加密聊天页设置 `FLAG_SECURE`，避免系统截图/最近任务缩略图；系统通知正文固定为“收到一条加密消息”。未锁定时也不在通知中显示正文。
- 接收解密先验证外层格式、大小、消息类型、自身设备、目标会话、对端身份/设备与允许的 epoch，再在候选 vault 上执行 libsignal 解密并校验内层字段；任何验证失败都不消费预密钥、不推进棘轮、不更新信任。只有历史与 vault 都持久化成功后才提交候选状态并返回明文。
- 已知边界：关闭后重新启用会递增 epoch，关闭前旧 epoch 的发送重试会收到明确的 epoch 错误，不做静默重加密。客户端不提供服务端搜索、撤回或群密钥。忘记 PIN/设备密钥失效后，本机历史与协议状态不可恢复。
- 回滚边界：旧后端二进制会忽略新增的 `E2eeEnvelope`/`ServiceKind` JSON 字段，已加密消息将无法正确显示；旧 APK 不识别新 Protobuf body，也不识别本地密文前缀。必须在切回 E2EE 之前的快照和新后端上继续服务；不能用清库、重建账号或旧 APK 读取来“修复”回滚。当前没有对正式服务执行升级/回滚。

## 11. 本轮实际验证（2026-11-07 复核修复）

- `dotnet build`（.NET SDK 8.0.204）通过，0 warning/0 error；本轮后端源码未改动。
- `node tests/e2ee.mjs` 通过：预密钥发布/一次性消费、邀请/接受/关闭边界、明文拒绝、幂等与冲突、身份替换暂停/双方确认、历史只含信封、服务消息、重启持久化。
- `node tests/smoke.mjs`、`tests/gid.mjs`、`tests/m49.mjs`、`tests/rpc-boundaries.mjs` 通过（隔离数据库/动态端口）。
- `Tools/Verify-BotgramE2ee.ps1` 通过 73 项宿主断言：v2 PIN+设备嵌套封装、仅 PIN/仅设备负向、标准 PBKDF2 已知向量与兼容实现交叉验证、v1→v2 迁移、libsignal 握手、信封/安全码、上下文失败无副作用、历史超过 5000 条并重开分页、发送/接收提交失败回滚、身份确认策略、发送 applicability、操作代际隔离、原子替换失败保留旧文件。
- `Tools/Verify-BotgramProtobufMapping.ps1` 390 项通过；`Tools/Verify-BotgramTL.ps1` 44 个原 TL 响应二进制往返通过。
- `:TMessagesProj_AppStandalone:assembleAfatDebug` 构建与 APK v1/v2 签名、包名、版本、四 ABI、新增资源检查结果见 `artifacts/build-info.json` 的 `e2eeVerification`。
- 没有连接 adb 设备：PIN 页面视觉/无障碍、双机握手与收发、重启后历史、锁定/后台/通知、真实 Android Keystore（IV 生成、失效、卸载/清数据、ROM 差异）、身份替换真机流程、PostgreSQL 实际部署上的 E2EE（当前明确 501）均未验证。上述宿主/后端/编译结果不能替代真机验收。
