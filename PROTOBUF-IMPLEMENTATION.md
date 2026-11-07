# Protobuf 实施状态

## 最新客户端增量（源码已接线，生成/构建 BLOCKED）

本节覆盖后续用户授权；下文日期与历史测试记录保留为各阶段证据，不能套用到本节。用户已要求新后端独立运行 P7；客户端不改生产 base URL，不把已有 JSON pin 的 410 写请求自动改 PB 重试。运行验收尚未完成。

| operation tag | 客户端映射范围 | 本轮状态 |
|---|---|---|
| 100–108 | state/difference/dialogs/pinned/peer-dialogs/history/messages/send/read | 保全快照已有；未在本轮重新构建验收 |
| 109–114 | search/resolve-username/users/contacts/resolve-gid/import | 保全快照已有；保留原参数拒绝边界 |
| 115–120 | create-group/peer-settings/full-user/full-group/groups/notify-settings | 保全快照已有；full-info 新增可选壁纸状态 |
| 121 | resolve-identities | 独立 Identity mapper 内部调用，不算第22项 UI 业务 |
| 122 | get-wallpapers | 新 typed 源码，列表、hash/not-modified、installed、revision |
| 123 | save-wallpaper | 新 typed 源码，NoFile + 完整 settings，含 unsave |
| 124 | install-wallpaper | 新 typed 源码，NoFile 或显式 clear；不是重置全部收藏 |
| 125 | set-chat-wallpaper | 新 typed 源码，确认后应用 PeerWallpaperChanged，不产生持久服务消息/pts |

`UserSummary` 新增 `optional bool nyarugram_premium=11`。壁纸扩展为 `UserFullResult.wallpaper=10`、`GroupFullResult.wallpaper=8`、`Update.peer_wallpaper_changed=6`、`EventHint.wallpaper_revision=3`；原 major1、tags100–121 和 error10 不变。WallpaperState 缺失表示旧服务未知，显式 cleared 才清除。signed64 壁纸 ID 保留0/负值，颜色0是黑色而非缺省；第二色与旋转成对，第三/第四色依次依赖前色。

首期不支持文件/slug/上传/图案/非空 emoticon/service_message_id/reset-all；不能宣称所有 Telegram 壁纸或所有 Telegram API 已实现。旧 Mapper 会拒绝未知 Update/ServiceMessage，所以新状态只在新请求/全量资料中返回，不进入旧 Difference 流。客户端新代码仍拒绝未识别变体，不吞掉更新。

两侧分别读取各自 schema，结构记录 SHA-256 均为 `deb2ef4326da933fdf619cf966996935c4c18cfdd0a0b4f4f81dfa9f685f0a31`；按完整声明 token 忽略声明顺序的 SHA-256 均为 `5ffac55196c5737f3783b53b664367dc1198d74fdf0c5d113c8b61fd3058c40f`。两侧各有 89 个 message、无 enum；壁纸声明在文件中的相对位置不同。有序 token 和原文件哈希不同不代表字段差异；静态比对不等于正式 descriptor、生成结果或运行时兼容验证。因 D 盘 protoc/Gradle 与共享生成产物实际沙箱拒绝，**生成 Java 仍是旧版，新增 mapper 不能作为已编译代码交付**。新增 Premium presence/session 与壁纸 presence/颜色边界/真实 TL 往返用例待执行。Android 运行测试按用户要求 NOT RUN。总报告需继续列出 APK、协议兼容、后端 HTTP/WSS、跨设备恢复和基本群缓存恢复的未验收项。

覆盖数量口径：只读母快照审计的814 RPC定义、756发送/构造候选并集不是支持数量或动态可达性证明；母快照21项业务映射+identity121，本次新增4项壁纸映射也不覆盖其余数百候选。旧 inventory 存在跨赋值 union 过近似，不用候选行数冒充实际发送路径。

日期：2026-11-02。仅本地实施与隔离测试；不授权生产部署或 JSON RPC 退役。

## 基线

- Android 起点 HEAD：`d8b512988fa12b0909c267ce749ee295ee68f78d`，后端起点 HEAD：`67d5806a72a8e9f143bb92fb151e3a232d28f563`；开始时两个工作树干净。
- 审计中 W1 尚未实现的描述已过时。当前源码已有 `BotgramSessionScope`、队列上下文传播、运行任务排空与 logout cleanup 屏障；不得撤销。
- P4 当前源码将已绑定账号的普通 TL RPC 接入 Bearer + typed Protobuf HTTP；登录/注册、GID REST 与 WSS 提示仍保留既有协议。P3 的 JSON RPC 服务端入口继续保留。新源码尚未交付或部署，完整 APK/真机验收状态见 P4 节。

## 已实施

1. 三个宿主验证脚本改用 D 盘唯一输出子目录，避免固定目录中已有 class 文件写入失败。未下载依赖。
2. `BotgramTransport.send` 在进入串行网络队列前捕获 final server/bearer 字符串；执行时不再读取可变会话凭据。原 owner scope、取消与完成回调隔离保留。
3. 后端隔离测试保存完整旧 RPC 请求、响应与 HTTP 状态；未包含认证头、密码、生产账号或令牌。
4. 真实 Android TL 宿主编码测试在回读成功后保存 Base64 二进制样本，位于后端 obj 内，不进入 APK。

## 本轮实际验证

- 原后端 build：通过，0 警告/错误；m49、tl-schema、forwarded-headers、smoke、gid、contacts 通过。
- Auth 宿主 6 项、地址 34 项通过；SessionScope 可控队列回归通过。
- 最新三套 TL 回读：smoke 30、gid 2、contacts 26 个响应通过，已保存对应 `.tl-bytes.json`。
- 修改后 Android `:TMessagesProj_AppStandalone:assembleAfatDebug` 构建通过（Build-Botgram.ps1 默认 Debug）。未宣称 Standalone/R8 验证已完成，未交付本阶段 APK，未真机测试。
- P0 关键边界基线完成：差分四分支、105 事件分页及重放、int64 极值、15 类实体和 UTF-16 边界通过；boundary 131 个真实 TL 响应回读通过，连同此前58个共189个。仍非全部组合穷举或真机验证。
- P1 第一批源代码：消息实体改为有序强类型值；六类事件 payload 明确类型，快照 converter 保留旧 JSON shape；差分不再通过 JSON 重解码事件。提取 IdentityService 和 SyncService 的身份、pts 及分页逻辑。
- P1 首批及后续共享业务提取后实际 build 通过，0 警告/错误；rpc-boundaries、smoke、contacts、gid 在最新 DLL 上再次全部通过。
- 新增 ContactService，承接用户名/GID 查找、联系人列表、导入校验与别名写入；ConversationMessageService 承接强类型发送命令、历史查询、已读结果、会话创建与群服务消息。REST 发消息/历史/已读/会话创建复用共享业务，保留 REST/TL 的幂等和错误契约差异。Gateway 仍有会话列表/用户详情等查询逻辑，尚非纯适配层，不宣称 P1 完成。
- 新增并实际复跑 typed-persistence 专项：旧 JSON 的 15 类实体和 6 类事件恢复为强类型；null/空数组/字段缺省、稳定身份与游标、成功写入和跨进程重启保持；Store.Write 回调异常及磁盘写入失败均恢复内存且不改变原磁盘数据。最新四套样本再次完成真实 Android TL 回读 131+30+2+26=189 份；静态 tl-schema（41 个直接响应类型）和离线 m49 也通过。

## P1 查询收拢与验收（2026-11-02）

- 新增 UserQueryService/UserProfile 和 ConversationQueryService：用户安全投影、联系人备注与互为联系人、会话列表/锚点分页、按 ID 消息过滤、成员与群查询均使用强类型结果。结果不包含密码盐或摘要。
- TelegramGateway 不再直接查询 Users/Contacts/Messages/Receipts/Conversations 集合；保留旧 JSON/TL 类型解析、能力检查、flags 与响应投影。业务 Service 不依赖 JsonObject/JsonElement/JsonNode，不通过 JSON 往返调用旧 Gateway。
- REST 会话创建/去重、消息、历史、精确已读、列表与 REST/WSS 事件分页复用共享服务；保留 REST/TL 的标题 Trim、群服务消息、幂等比较、已读及排序差异。REST 只读列表不新增 TL 身份分配；旧 RPC 惰性创建/身份分配仍在原 Store.Write 内。
- 最终源码 build：0 警告/错误；smoke、rpc-boundaries、contacts、gid、typed-persistence、tl-schema、m49、forwarded-headers 全部通过。新增 smoke 查询分页、选择、用户安全字段与 REST 列表结构断言。
- 最终 fixture 真实 Android TL 序列化/重新解析：smoke 40 + boundary 131 + gid 2 + contact 26 = 199 份通过，并保存二进制样本。当前静态检查仍只识别 41 个直接响应类型，不能等同完整协议覆盖。
- P1 共享业务提取及本地相关回归已完成；尚未执行新旧二进制互换回滚演练、全部字段/权限组合或真机测试。旧快照兼容与内存/磁盘失败回滚有专项证据，不代替上述未执行项。

## 用户已批准的身份契约

- 现有 `User.Id` UUID 字符串直接作为稳定 `user_id`，不重建 UUID，不改变 GID、TL 数字 ID 或聊天关联。
- `server_domain` 由部署者通过 `Protocol:ServerDomain` 显式配置为自己服务的稳定身份域名，不含协议和端口，不从请求 Host 或客户端连接 URL 推导；项目不指定公共服务器。
- 隔离测试使用独立测试域。部署者应在初始化时确定规范服务器身份，已有实例更改身份需评估兼容性；本轮没有 S2S 联邦实现。

## 后续阶段

- P0：关键回归基线已完成；保留未穷举项与真机缺项。
- P1：共享强类型业务提取及相关本地回归已完成，详见上节；旧 JSON RPC 和 REST 保留。未执行项不扩大为通过结论。
- P2：强类型 schema、字段策略登记、固定生成工具链与双向字节兼容检查已完成；范围及未验证项见下节。
- P3：后端 endpoint、强类型校验与映射已实施；隔离双协议及边界验收结果见文末。
- P4：Android 显式映射及传输源码已接入，宿主映射与隔离 D8/R8 已通过；完整 APK、应用混淆和真机验收尚未完成，详见文末。
- P5：HTTP双协议/禁用专项2062断言已有通过记录；18524用户确认可收发，审计有SendMessage/GetHistory200，但另有ReadHistory400及401未解释，不算零错误。新增宿主响应头/正文黑洞超时与恢复，223断言PASS。能力广告、会话分桶和客户端持久化协议固定已接入；新增后端回归2257断言通过，源码宿主协商/黑洞228断言通过，APK构建及真机验证仍进行中；旧ReadHistory400尚未归因，性能已有基线但预算未定；P5未完成，不自动回退JSON，不退役旧入口。详细范围见 `PROTOBUF-TEST-RESULTS.md` 的P5续测节。
- P6 必须等待 HTTPS RPC 稳定；P7 未获授权，不移除旧入口。

生产证书、Caddy、端口、正式数据均未修改。HTTP80/ACME 续期待验证风险保留，协议迁移不构成其修复。

## P2 schema 冻结与工具链验收（2026-11-02）

- 后端 `Protocol/botgram_rpc.proto` 与客户端 `Shared/botgram_rpc.proto` 各自持有独立副本；74 个 message，21 个请求/结果 oneof tags 固定为 100–120，error=10。禁止 Any、Struct、动态 map 和 opaque bytes/JSON 逃生通道；生成 DTO 不进入持久化模型。
- `Protocol/rpc-field-policy.json` 登记全部请求字段、继承字段及嵌套类型/历史子类的 mapping、明确忽略或拒绝决定。普通发送允许显式 self sender，验证后省略冗余身份，替代 sender 为 403；reply peer 必须与发送会话相同，跨会话为 501。联系人精确搜索 limit 与 pinned folder 保持旧忽略语义，联系人非零 flags 继续拒绝。
- 身份固定为既有 UUID 与配置域；GID 不作身份。random_id/client_id 为 sfixed64，消息 ID 到 TL int32 必须 checked；时间为 Unix 毫秒，实体为 UTF-16 单元，optional 保留缺省与显式零。错误保留 HTTP 状态，未知操作/不支持语义不得作为成功。以上是后续实现契约，不是已实现的运行时 validator。
- 后端 `tools/protobuf-toolchain.json` 锁定 protoc 25.8、protobuf-javalite 3.25.8、Google.Protobuf 3.25.8 的 URL/SHA-256；全部下载、生成代码、缓存及隔离编码测试产物位于 `D:\BotgramBuild\protobuf`。哈希来自初次 HTTPS 下载，未独立验证上游签名。
- 后端运行 `./tools/protobuf.ps1 -Action Generate` 生成 Java lite/C#；`-Action Check` 复核下载哈希、重复生成逐字节一致、Java 8 目标编译及 66 组 Java→C#→Java 样本。兼容测试使用独立旧/新 schema，验证新增字段/枚举及未知字段保留，不声称已发布 App 版本兼容。
- 后端运行 `node tools/verify-protobuf-contract.mjs` 可独立离线校验。额外传 `--android-root W:/Work/Botgram` 检查真实清单的完整字段/子类覆盖、21 tags 和两仓 proto 逐字节相同；该检查不成为后端构建依赖。两种模式主执行均 PASS。
- 客户端 `Tools/Verify-BotgramProtobufAndroid.ps1` 通过隔离 Java8、D8/R8 release min-api23 检查。R8 保留全部协议类型且不混淆；这不是 APK 体积、应用混淆或真机验证。后端最终 build 为 0 警告/错误，Web 项目排除 Protocol.Tests 源码且未引入 Protobuf 运行依赖。
- P2 阶段验收完成，当时未进入 P3：没有新增 HTTP endpoint、Android Mapper、流量切换或 JSON RPC 退役。`getMessages` 旧入口把实际整数数组按对象读取的既有差异已登记，P2 不改旧入口；后续映射须直接使用整数 ID。P3 后端进展见下节，真实 APK 集成、设备与端到端测试仍待后续阶段。

## P3 后端版本化入口（2026-11-02）

- 新增 `POST /api/v1/rpc`，媒体类型 `application/x-protobuf`，与 JSON `/api/telegram/rpc` 并存；复用既有 Bearer 中间件和 API 限流。由部署者显式设置 `Protocol:ServerDomain` 为自己的小写 DNS 域名，启动时校验，不取 Host/连接地址。
- `ProtobufRpcMapper` 的 21 个 operation 直接调用 P1 共享业务服务；不转 JSON、不调用 TelegramGateway，也不复制另一套业务。校验 UUID/规范域、self、成员权限、集合上限及重复、消息 int32 范围、分页、15 类实体与 UTF-16 边界；unknown operation/entity、跨域及非法参数明确报错。
- endpoint 限制请求为 128 KiB、递归深度 32、响应为 8 MiB；不接受压缩请求。校验 protocol_major=1 和 1–64 位 ASCII request_id。endpoint 错误使用 Protobuf 并保留 HTTP 状态；认证/限流中间件仍可返回原 JSON 或空正文，客户端未来须先检查状态及媒体类型。
- 顶层单次 Store.Write 内重查会话、执行映射、检查响应大小并序列化，再持久化；异常沿现有快照回滚。事务内检查请求取消；提交后网络断开不能保证回滚，重试依靠原幂等键。random_id 使用 invariant decimal 且保留 signed64 极值。
- 后端纳入固定工具生成的 `Protocol/BotgramRpc.cs`，运行依赖锁为 Google.Protobuf `[3.25.8]` 并记录 NuGet 内容哈希。Docker 复制生成代码及锁文件，以 locked restore 构建；后端编译不读取客户端或 D 盘生成目录。Docker 实际构建尚未执行。
- `tools/protobuf.ps1 -Action Check` 现同时检查纳入源码的 C# 与固定工具链重复生成结果逐字节一致。编码测试改用 D 盘唯一 obj/bin 目录，避开既有固定输出拒绝写入，不修改 ACL 或删除既有输出。
- HTTP 隔离测试保留后端子进程 stdout/stderr 与 PID/停止原因；主执行以原 DLL、日志增强版 Node 路径及 PowerShell 直接路径独立复跑均 exit 0（1886 断言/21 操作）。历史 0xE0434352 未复现且无对应 Windows 崩溃事件，根因仍未定位，不作业务修复。
- P3 当时不实现 Android Mapper、不切流、不部署、不退役 JSON、不交付 APK；隔离验收和实际命令结果见 `PROTOBUF-TEST-RESULTS.md` 的 P3 节。

## P4 Android 显式映射与当前验收（2026-11-02）

最新真机状态覆盖下方历史“尚未验证”：用户已确认收藏夹、自发消息、Bob 注册/切换、搜索、双向文字与引用回复正常；随后反馈会话列表尾部持续闪烁骨架行。本轮兼容原列表请求 exclude_pinned=true（当前后端无置顶项，过滤不改变结果），同时在请求失败时于 UI 线程释放 loadingDialogs 并通知刷新，不推进分页游标、不假设已到底、不主动立即重试；沿用既有 SessionScope 对迟到 UI 回调的隔离。Standalone 未重新构建，最新列表修复仍需真机确认，P4 未完整验收，未部署正式服务。详细构建与验证证据见 PROTOBUF-TEST-RESULTS.md。

- 离线状态补修：复用 ApplicationLoader 已有网络变化广播，经 ConnectionsManager 通知 BotgramTransport；无网络用 WaitingForNetwork，有网络但无近期后端响应用 Connecting。启动强制发布状态、已有轮询发布超时变化、Activity 恢复时重查网络；LaunchActivity 对绑定账号同状态通知也重新应用原标题覆盖。自身 Profile 两处 Online 改为读取连接状态并订阅/注销 didUpdateConnectionState；不改变他人状态、不实现服务端 presence。复用已有中英文资源，最新状态需真机确认。
- 新增 `BotgramProtobufRequestMapper`、`BotgramProtobufResponseMapper`：21 个业务请求/响应使用精确 TL 类分派，15 类实体、5 类更新、4 类差分显式映射。普通请求不再反射编码 TL JSON，不更换原 UI/controller/storage；未知类和不支持的非默认选项明确拒绝。sfixed64 直接保留 long，消息 ID checked int32，毫秒转 TL 秒使用 floorDiv 并检查溢出。
- 用户批准追加 transport-only `resolve_identities` tag121；原业务 tags100–120不变。两个仓库各自保存相同 schema 和固定生成源码，当前共76个message。鉴权后仅解析已有 `TelegramIds`，返回配置域及 self summary，群组必须属于调用者；未知/缺失身份拒绝，不分配新身份、不改变 UUID/GID/TL ID。
- `BotgramProtobufIdentity` 缓存仅属于当前 session，在 requests 后台串行队列使用；切换/退出后重建，不把域从连接URL推导，不在UI/storage增加UUID字段。请求前后检查 session/owner/cancel，保留既有 scope 的绑定回调与任务排空屏障。
- `BotgramApi.rpc` 使用 `/api/v1/rpc`、128KiB请求/8MiB响应限制与递归深度32；禁止重定向，核对HTTP状态、媒体类型、major、request_id、outcome。中间件非Protobuf错误仍保留真实HTTP状态；无自动JSON回退或写入重试。登录/注册及主动退出仍沿用既有REST，WSS提示保持不变。
- 客户端纳入 Java lite generated 源码及 D 盘固定 protobuf-javalite 3.25.8 jar（构建时校验SHA-256）；增加 GeneratedMessageLite 字段保留规则。真实Standalone/R8构建exit0，最终configuration含该字段keep规则，mapping及DEX确认生产Mapper/Identity、RpcRequest/RpcResponse/UserSummary存在，信封oneof/major/requestId及用户tlUserId/gid字段名称保留。静态产物检查不代替设备运行时解析。
- 主执行独立复核 `Verify-BotgramProtobufMapping.ps1 -CompileMapperSources`：203项断言通过，generated bytes→生产Mapper→真实TL序列化/重新解析字节稳定，包含HTTP信封负例、无JSON回退及身份缓存隔离。采用临时D盘依赖库输出隔离后，Android主模块Java编译exit0；默认模式使用本轮Gradle编译类复跑203项同样通过。
- 新schema的隔离Java8与D8/R8 min-api23通过；会话scope宿主回归通过，契约清单/两仓schema一致检查通过。完整Debug APK已构建成功，v1/v2签名、包名/名称/版本及DEX中的生产Mapper/Identity/generated协议/runtime均核验通过；真实Standalone/R8已构建成功并核验v1/v2签名、包名/版本/名称/四ABI及不可debug，已更新两种交付APK和build-info；用户同意连接测试设备，但最新ADB列表仍为空，真机尚未验证。不部署、不执行P5或移除JSON入口。当前客户端普通请求改用typed入口，无自动JSON回退，不能据此声称与尚未升级的部署后端兼容。

## P6 WSS Protobuf 双协议（2026-11-02）

**当前状态：实现完成，本地功能回归通过；联合验收未通过，三轮TLS性能存在p95预算失败，真机仍阻塞。** P5 与 P6 剩余事项统一按 `PROTOBUF-TEST-RESULTS.md` 的“P5/P6 联合验收任务”执行；联合验收通过后再讨论是否启动 P7。APK 构建成功不证明生产服务器已支持新 WSS 子协议；新 Debug 必须连接兼容的独立测试后端。本地安全回归不等于真实 TLS/WSS 全链路安全验收。继续保留 JSON RPC、JSON WSS 及既有账号数据，不进行生产部署或协议退役。

- 用户授权独立推进 P6；不代表 P5 真机黑洞、ReadHistory 历史400归因或性能门槛已关闭。未部署正式服务或18524隔离实例，未退役JSON。
- 同一 `/api/ws?after=` 通过标准 `Sec-WebSocket-Protocol` 独立按连接协商，优先 `botgram.event-hint.v1.protobuf`，其次 `botgram.events.v1.json`；不复用RPC协议灰度。无子协议继续旧JSON；只提供未知子协议握手400。能力接口另列 `eventSubprotocols`。
- 新增强类型 `EventHint { protocol_major=1, cursor=2 }`（字段tag；运行时major值为1），仅包含事件水位，不含消息、JSON bytes、TL更新或pts。两仓独立proto及固定生成源码同步，现77个message，原RPC tags保持。
- Android每次连接提供上述两个子协议，旧服务器不选择时仍收JSON；PB连接只接受二进制、JSON连接只接受文本。安全补修替换固定nv 2.14的单个入站reader类（原jar校验SHA-256后过滤此类，其余entry逐字节保持）；在OPEN且读线程启动前设置PB消息1024字节、JSON消息8MiB累计预算。读取帧头后、分配和读取payload前检查单帧及跨分片累计大小，限制1024片，控制帧不得重置预算，非法头与超限后终止读取；不再误用发送侧setMaxPayloadSize。PB生成解析前以固定32项栈迭代验证wire group的深度、配对和完整性，33/34/100层拒绝，0/31/32层接受；length-delimited未知字段按不透明字节跳过（EventHint无嵌套message）。仍检查major、非负int64游标及生成解析结果；未知字段允许，非法/错帧关闭并走原重连，不能推进游标。防护来自有界预验证，不依赖javalite递归配置覆盖unknown group。
- 游标推进及唤醒仍经UI generation检查，退出/换账号/后台使旧回调无效；连接成功也唤醒追赶。差分入库、幂等和pts权威不变，前台轮询保留；RPC写超时不切JSON重发。
- 服务端按原权限过滤分页，单连接逐次await发送、10秒发送截止，避免无界发送队列；保留500ms检查及原会话失效关闭。未知事件不需PB新增业务类型，仍由差分决定是否可消费。既有全局游标空提示/活动节奏与撤销检查竞争语义未改变。
- 历史本地HTTP回归2872断言（含WS599）及77-message契约通过；本轮安全补修主执行复跑真实nv reader/loopback 214项和生产listener宿主232项通过。完整Debug APK构建、v1/v2签名、元数据及DEX中替代reader/bridge/wire guard/EventHint/两个子协议核验通过，已交付新Debug并更新build-info。固定工具链Check改用D盘唯一解压和检查目录后主执行exit0，3依赖哈希、重复生成/纳入C#一致、Java8编译和66组双向互操作通过；未改ACL，旧目录权限根因未定。未重建Standalone/R8。详情见 `PROTOBUF-TEST-RESULTS.md` 的P6节；后续联合实测覆盖状态如下，保留JSON，不进入P7。

## P5/P6 联合验收续测（2026-11-02）

- 最新APK源包/交付哈希一致 `EA269FF10EAFC3D8B0B8CA740B52BC739B4576C62FC4508E93D466D293E1015F`；后端测试DLL仍为 `E01952FD7152C3DEB9A521917AC74025B6C8D2621E321D5227DE3EC48D9EFC1F`。ADB检查exit0无设备，真机可靠性/UI/当前会话固定仍BLOCKED。
- 仅扩展后端测试：`--joint-tls` 实际exit0、649断言PASS（含604 WSS），独立随机端口/数据、Kestrel真实TLS，临时localhost leaf精确pin+平台主机名校验，不改系统信任。PB二进制hint、显式JSON文本及无协议旧JSON均101并真实接收；鉴权、会话失效、cursor恢复/权限过滤通过。丢提示后C#调用getDifference两页追平107条，pts1→108，不将WSS cursor110作为pts。非Android、非反向代理、公有受信链未测。
- 有界上行错误帧关闭及5秒资源观察PASS；CPU增量31.25ms、5次健康200，自有进程最终0、临时PFX清理。客户端reader214+listener232宿主重跑exit0；完整TLS攻击链路/Android释放/线程任务仍NOT TESTED/BLOCKED，不能以此关闭安全验收。
- TLS ReadHistory参数边界及新审计PASS；Android mapper直接保留原int max_id，未取得真实手机请求/会话关联，历史两次400保持INCONCLUSIVE，不改业务语义。
- 用户已批准服务端预算：至少3轮反序、并发1/2和TLS，PB p95≤JSON110%、新增非预期错误0且不劣化、单位请求CPU/峰值私有内存≤JSON110%；Android/APK阈值未批准。保留原240基线，补充首版两轮480、最终三轮720正式+180预热，全部HTTP200。三轮18配对中3个p95超限（2.8153/1.1190/1.1030倍），**延迟FAIL**，不能宣称性能达标；CPU多组超限且粒度不足、真实峰值未连续测量，归因/峰值INCONCLUSIVE。
- 只增加测试实现，无业务安全修复/协议更改。默认输出被既有进程锁定的build exit1保留，独立D盘输出build exit0；未终止既有进程。具体命令/退出码、构建/源码哈希、所有日志/样本位置及结论见测试结果文末“P5/P6 联合验收实测”。**联合退出条件不满足**，后续需真机、代理/安全矩阵、ReadHistory取证与性能调查；未操作18524/生产、不退役JSON、不进入P7。

### 性能诊断与测量增强续测

- 默认关闭的 `Protocol:DiagnosticStages` 仅采样Store写锁、快照、业务、PB编码、持久化编码/flush/替换及请求GC/pending，不改变事务与协议。新复现31.7586ms慢请求：写锁18.8319ms、flush10.5491ms、业务0.0784ms、PB编码0.0308ms；另捕获flush20.632ms。确认这些样本的刷盘耗时/锁放大，不能追溯证明旧44.4304ms的具体归因，不能完全排除调度。未实施猜测性业务修复，保留同步持久化保证。
- 关闭诊断日志后，修正测试并发请求重叠资源读取、提高每格为20预热/200正式，并以独立观察器全窗口采样私有内存。真实TLS三轮/反序/并发1、2，7200正式+720预热全部200，构建与执行exit0。18配对p95有7个超110%，CPU有2个超110%，**仍未达到预算**，不把样本数变化当作修复效果。私有内存采样峰值全部在预算内，但实际最大间隔56.6158ms，OS精确峰值仍INCONCLUSIVE。
- 测量后端DLL SHA256 `16FD42B73A6B244063ED66280C39C8C9A80FB0588A34C18C81B26FF3E6BA52CF`；详细命令/哈希及证据根见测试结果的“性能归因与资源采样续测”，旧证据保留。无参数HTTP回归exit0、2872断言通过。ADB复查无设备，真机继续BLOCKED。
- 新增 `--joint-proxy-tls`，固定并校验官方Caddy2.10.2，随机loopback TLS→HTTP Kestrel，禁admin/自动HTTPS/配置持久化，缓存/证书均D盘。实际构建与运行exit0，649断言通过：三种WSS真实101、权限/会话/cursor恢复、有界错误上行关闭、107消息差分追平及ReadHistory边界。主执行复核6个自有子进程已退出、PEM/私钥/PFX删除。测试DLL `93DD76DD2B6E88A576A362D6A3F4109910BCE072BEA3E1814443CBA753137473`，后端DLL不变；证据根及全部哈希见测试结果“独立真实反向代理TLS续测”。
- 真实反代功能子集PASS，但仅精确leaf pin，CA链/公共信任及证书负例未测；恶意TLS服务端到Android异常输入链路、代理半开/黑洞、逐线程/任务释放未完成。5秒后端资源观察不证明代理/Android无泄漏。**性能预算与P5/P6联合验收仍未通过**，不把代理功能通过等同完整安全通过。无生产修改、无18524操作、无JSON退役、无P7。


## P5/P6 本轮收敛结果（2026-11-02）

完整证据及未通过项继续记在PROTOBUF-TEST-RESULTS.md的“性能归因、保守优化与安全矩阵收敛”与“最终冻结版本”两节，未建立替代总报告。两仓既有改动保持，无生产/18524操作、无P7、无JSON入口退役、无Git提交；Android生产源码未改，不重建APK。

- **已实施保守优化**：PB SendMessage响应与updateNewMessage复用一次Message DTO，保持事务内映射/编码、权限、random_id、事件和同步落盘。没有改变Store回滚方案、Flush(true)或原子替换。新增诊断默认关闭，并修正关闭时标签字符串分配。
- **诊断证据**：独立7920请求对照，JSON83.79ms样本锁等待64.3179/Flush17.4099ms；PB79.5515ms样本锁等待11.9434/Flush65.8934ms，mapper0.0728/编码0.0376ms。双方均见锁放大/刷盘墙钟耗时，但不证明物理磁盘故障、历史44.4304ms根因或所有PB差异；CPU/内核I/O与框架JSON绑定仍有未拆分项。各计时包含关系、GC进程增量局限已记录。
- **局部测量**：三轮固定100消息、SendMessage幂等重试的同步mapper分配，前测6736、中间5344、最终5136B/次；最终还包含关闭诊断分配修正，不能当作新写入/端到端性能改善百分比。完整正式矩阵不支持性能达标结论。
- **正确性PASS（已测范围）**：最终后端构建exit0/0警告错误；无参数2913断言，21RPC/身份解析、JSON/PB响应/归一化快照、权限、并发8发送ID/pts/完整Message、取消重试、599 WS通过；八套Node回归、199真实TL样本回读、228 Android mapper/黑洞宿主断言通过。新增Windows原子替换失败回滚及旧快照重启通过；确定性Flush(true)失败仍NOT TESTED。fixtures输出跟随BOTGRAM_TEST_ROOT存D盘。
- **TLS/WSS新增PASS（有限范围）**：隔离CA七次真实握手/20断言覆盖合法/主机名/时间/根/中间链，CustomRootTrust无全局信任变更；真实TLS/WSS宿主16连接/92断言覆盖reader/listener大小、分片、group、错帧/断流/20s超时/手工cursor恢复，关闭执行器清空并终止。最终直连与独立Caddy各714断言（包括原649，未冒充全部新增）。这些不等同公有CA、Android、OS丢包黑洞、自动重连计时或后端/Caddy逐发送任务释放验收。
- **原110%预算FAIL**：最终7200正式+720预热、3轮反序/并发1、2/真实TLS，8/18 p95及3/18 CPU超限，样本HTTP错误0。保留前测6/18和2/18、中间5/18和2/18及更早历史FAIL，不截取最好运行。CPU高分辨率周期是辅助证据，未替代原CPU时间预算；采样私有内存比0.3421～0.9641/最大间隔18.7662ms，精确峰值仍INCONCLUSIVE，OS生命周期高水位未冒充测量窗口private峰值。
- **最终产物/证据**：D:/BotgramBuild/tmp/p6-next-delivery-bin/Botgram.Backend.dll SHA256 1C1411E12D2B7248EF49CA50DD2FA57FBA549AE2B27C15FA07986E72FCD3CB57；测试DLL 8D65BABA7D7FF3E1B91A5505F9AA9137774CCE3F054B5FC4FA49FF97912D5CBE。最终原始/汇总根p6-http-runs-fe35c2158a3040cfa48bbfb29bc72e54，18对详细指标p6-next-delivery-budget.json，证据清单p6-next-evidence-manifest.json，均在D:/BotgramBuild/tmp。
- **仍不满足联合退出条件**：无ADB设备，真机网络/切号/迟到回调/UI/消息与pts追平继续BLOCKED；历史ReadHistory400和精确峰值、CPU具体归因INCONCLUSIVE；公有CA/吊销、确定性Flush失败及完整代理/后端发送超时资源矩阵NOT TESTED。下一步需进程CPU/I/O/调度关联跟踪、确定性存储失败注入及授权真机。不建议P5/P6联合通过，不进入P7。


## P7.1—P7.3 开发准备与隔离回滚（2026-11-03）

用户本轮单独授权P7代码准备和隔离演练，未授权正式退役或生产切流。前文“未进入P7”是2026-11-02历史状态，保留不改；P5/P6性能FAIL、真机BLOCKED、历史ReadHistory400 INCONCLUSIVE仍然成立。本轮不重测或重新判定原110%性能预算。Android生产源码、protobuf schema、身份及持久化格式均未改，无APK重建、无18524/生产操作、无Git提交。

### 遗留协议及非退役JSON依赖表

| 类别 | 入口/组件及调用链 | 本轮处置 |
|---|---|---|
| 可独立禁用的旧RPC | POST /api/telegram/rpc → TelegramGateway.Execute → 共享typed services；Android BotgramTransport.java:289的tl-json-v1分支、encode/decode反射 | 保留源码，以LegacyJsonRpcEnabled控制；禁止误删整个Gateway |
| 现行PB RPC | POST /api/v1/rpc → ProtobufRpcEndpoint → ProtobufRpcMapper → 共享typed services；21业务+resolve_identities，Android显式mapper | 始终保留，不经JSON Gateway，不改schema |
| 可独立禁用的旧事件 | GET /api/ws，无子协议或botgram.events.v1.json → JSON EventPage；BotgramEventSocket文本reader/listener | 新LegacyJsonWssEnabled开关，保留JSON实现和cursor恢复 |
| 现行PB事件 | /api/ws，botgram.event-hint.v1.protobuf → Binary EventHint → getDifference唤醒 | 不改名字/schema，HTTP灰度与WSS选择独立 |
| 现行协商JSON | GET /api/protocol/capabilities → Android BotgramApi.negotiate、BotgramSession持久化pin | 必须保留；广告由与入口同一份启动配置生成，不是旧RPC |
| 现行认证REST JSON | POST /api/auth/register、/api/auth/login；POST /api/auth/logout（204），GET /api/me | 保留；LoginView注册/登录、Transport的auth.logOut特例与注销清理均不依赖旧RPC |
| 现行TL身份REST JSON | GET /api/telegram/me → TelegramGateway.CurrentUser；LoginView登录后读取；PUT /api/me/gid也返回CurrentUser | 必须保留。Gateway还被这些REST响应辅助函数使用，不能因RPC关闭删除其源码 |
| 现行GID REST JSON | GET /api/gid/regions，PUT /api/me/gid → Gids.Change；BotgramGidActivity | 保留，不改变UUID/GID/TL ID契约 |
| 其他现行REST JSON | GET /api/users；GET/POST /api/conversations；GET/POST /api/conversations/{id}/messages；POST /api/conversations/{id}/read；GET /api/events | 保留独立REST/共享服务，不随RPC/WSS旧协议开关关闭 |
| 保留的REST信令JSON | GET /api/rtc/config；GET/POST /api/calls；GET /api/calls/{id}；POST /api/calls/{id}/{action}、/signals | 保留，不声称原Telegram phone.*已实现 |
| 健康检查JSON | GET /health，botgram-v1标记 | 保留，不将其作为PB能力标识 |
| 磁盘快照JSON | Store.Json/Read/Write；Models/MessageEntities/EventPayloads转换器；原子文件和失败恢复 | 完全保留，PB RPC仍使用该持久化边界；不清库、不迁移格式 |
| 配置与固定数据JSON | appsettings.json，M49资源/manifest、protocol字段策略、工具链lock | 保留，与网络旧RPC退役无关 |
| 测试/迁移/回滚 | JSON/PB fixtures、TL二进制回读、旧快照加载器、Node测试、双协议HttpTests及历史性能原始数据 | 保留。默认兼容设置使双协议基准仍可运行，不通过关闭JSON改变比较对象 |

Android仍有**一处集中式JSON RPC发送分支**，供固定tl-json-v1会话使用，并非所有TL调用均已不再依赖JSON。PB固定会话不走该分支，退出仍走REST；WSS既可选PB也可选JSON，与HTTP pin独立。SessionScope、Controller及UI未改。

### 配置、拒绝与迁移契约

| 配置 | 默认值 | 作用/约束 |
|---|---|---|
| Protocol:LegacyJsonRpcEnabled | true（原默认不变） | false时已认证旧RPC返回410 BOTGRAM_JSON_RPC_DISABLED；要求PB rollout=100 |
| Protocol:LegacyJsonWssEnabled | true（新增） | false时已认证的无子协议及显式JSON WSS在升级前返回410 BOTGRAM_JSON_WSS_DISABLED；PB不受影响 |
| Protocol:ProtobufRolloutPercent | 100（原默认不变） | 0～100，仍按Bearer会话固定cohort选择HTTP协议；不限制显式PB入口，不决定WSS协议 |
| Protocol:UsageLogEnabled | false（新增） | 仅控制低敏事件日志；Meter计数器可由进程内listener/exporter订阅，无新增公开统计API |

ProtocolPolicy.Load在数据库初始化前校验，HTTP旧入口、WSS选择和能力广告共同使用不可变启动快照。修改这些协议控制值需受控重启才生效；不承诺配置文件热切或中途改协议。JSON RPC关闭且rollout不是100，或rollout超出范围，启动失败；WSS JSON关闭与任何合法rollout均可组合。两个开关独立，但保留原RPC灰度一致性约束。

认证先于退役判定：匿名401 unauthorized，失效令牌401 session_expired；有效认证后才可能410。未知WSS子协议400 unsupported_event_subprotocol；提供PB（即使同时提供JSON且JSON关闭）仍优先PB。成功PB/JSON分别101 binary/text；capabilities仅广告启用的RPC和eventSubprotocols，原negotiationVersion、capabilities字段及PB名字不变。

旧JSON固定会话不自动迁移、不清pin、不在超时/410后用PB重发。用户可通过现行REST退出/重登，由新会话重新协商PB；或恢复兼容配置后继续旧JSON。Android 410沿原TL_error回调传递，不按401清会话，不回官方MTProto。纯旧客户端如果不支持PB，关闭JSON RPC后无法继续业务RPC，登录成功不代表聊天可用；关闭JSON WSS只会拒绝旧事件连接，若JSON RPC仍启用可保留其已有差分轮询，不能宣称实时事件自动兼容。实际旧版本UI提示和恢复操作未真机验证。

### 低敏观测口径

新增ProtocolUsage及Meter Botgram.ProtocolUsage（v1.0）：
- botgram.protocol.attempts：RPC请求、capabilities HTTP协商、WSS握手各计一次，WSS成功在101时立即计数，不等连接关闭。
- botgram.protocol.errors：上面请求/握手的非success结果，含disabled、authentication_failed、unsupported_client、rejected、failed、cancelled；按transport/protocol聚合全部outcome的attempts作分母计算错误率。取消单列，不能当作业务写入一定失败；未形成HTTP响应时http_status=0。
- botgram.protocol.wss.ends：另计JSON/PB接收/发送循环结束，区分closed/cancelled、session_expired、client_payload_rejected、transport_error、send_timeout；其失败比例用结束事件自身作分母，不混入101握手错误率，也不等同资源均已释放。
- 固定标签仅transport、protocol、outcome、http_status；不含Bearer、密码、正文、UUID、GID、IP、会话哈希或request_id。认证失败发生在WSS选择前，protocol=unselected，不猜测客户端能力。未知子协议记录unknown，不记录原始字符串。
- UsageLogEnabled=true时输出PROTOCOL_USAGE/PROTOCOL_WSS_END同一组固定字段，默认false。本轮用MeterListener验证累积量、错误分子、101不重复计数及取消不冒充200，并用隔离服务日志验证真实入口；没有安装生产collector/exporter，计数不写用户库，进程重启后重新累计。
- capabilities HTTP200只证明服务端返回协商响应，不证明客户端接受；无法仅靠服务端识别“从未发起请求的老版本”或所有客户端解析失败。版本分布/兼容窗口仍需独立运营证据，不能由计数为零自动批准退役。

### 隔离验收与剩余条件

最终构建0警告/错误；A/B/C/D/E使用同一D盘隔离库、随机loopback真实TLS/WSS，448断言PASS；PB-only、恢复JSON、0/100灰度、认证顺序、REST/GID、广告与实际入口一致、消息幂等及身份/pts保持通过。核心2913断言、代理TLS/WSS714断言、八套Node、199真实TL样本回读、234 Android API/mapper宿主断言、SessionScope及214+232事件宿主通过。具体命令/模式/日志/哈希见PROTOBUF-TEST-RESULTS.md本轮P7节。

P7开发准备及**配置级**回滚已验证；没有演练正式环境、旧程序二进制降级、线上热切或真实Android退役提示。P5/P6的性能FAIL、真机BLOCKED、ReadHistory400 INCONCLUSIVE不变；用户版本分布、兼容窗口、线上累计观测、正式退役验收与生产切流授权均未具备。默认保持两个JSON协议启用，不删除Gateway、WSS、REST或持久化JSON。
