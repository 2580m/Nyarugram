# 实现剩余 messages 接口，强制 Protobuf-only 与 PostgreSQL-only

请在现有 Botgram Android 客户端及同级 Botgram.Backend 后端中，实现本文末尾列出的全部 209 项 messages 请求，完成原页面接入、协议、持久化、同步、权限校验、回归和可安装交付。

**通信强制 Protobuf-only。后端业务持久化强制 PostgreSQL-only。弃用 JSON-TL 通信、JSON 文件数据库和整库 JSON 快照桥的运行时路径。**

这两条是最终验收的必要条件。需要把功能接入真实业务入口，并提供可复查的实现及验证证据。

## 一、工作区与现有基础

- 客户端为 Botgram，后端为同级 ../Botgram.Backend。读取各自适用的 AGENTS.md。
- 阅读 Docs/Botgram.Android.md、Docs/Botgram.GID.md、Docs/Botgram.Chinese.md、Docs/Botgram-E2EE.md 及后端当前协议、存储和迁移文档。
- 请求范围来自 Docs/artifacts/remaining-api-list.md 的 messages 分组，基于原有 756 项静态候选集合，本 Prompt 固定列出其中尚未映射的 209 项。开始时核对当前源码及工作区修改。
- 已有普通文字、历史、会话、差分、已读、账号、联系人、隐私、一对一通话和专用 E2EE 实现。复用其身份、事务、消息、事件及控制器。
- 当前已有 PostgreSQL 主模式、版本化 schema、Protobuf 认证与账号入口、JSON API 弃用闸门、原事件 pts 的幂等重试和 PostgreSQL 单权威 Outbox 完成工作进程。
- 保留已经完成的隐私页 E2EE 入口和响铃拒接后状态释放修复。用户工作区已有修改需要先辨认并保留。
- 项目文档统一放在 Docs/，任务说明放 Docs/Prompt/，交付报告放 Docs/artifacts/。AGENTS.md 保留原位，APK、ZIP 和构建元数据按现有 artifacts 约定交付。

## 二、必须落实的 Protobuf-only 约束

1. 每项请求通过原 TL 对象入口映射到明确的 Protobuf 请求，再映射回原 Android TL 响应和更新。保留 Telegram 原页面、控制器及序列化行为。
2. 业务 RPC 使用 /api/v1/rpc，认证和账号使用现有 Protobuf 入口，事件使用既有 Protobuf WebSocket 子协议。文件传输的必要新入口也采用明确的二进制协议与认证约定。
3. 禁止将完整 TL JSON 或任意 JSON 请求体包装进 string/bytes，作为新增接口的通用执行方式。请求参数、响应和错误必须有明确的协议字段及类型。
4. JSON-TL、旧 JSON REST 和 JSON WebSocket 保持弃用与错误响应。旧开关、灰度、请求类型、账号状态或异常都不能重新启用 JSON 通信。
5. 已绑定但过期的账号继续隔离 Telegram 原生网络。新请求遇到未实现或配置缺失时明确报错，禁止回落到 Telegram 公网或旧 JSON API。
6. 扩展现有 proto，分配稳定字段编号，维护兼容性、未知字段、字段存在性和错误语义。不要修改已有字段编号、类型或含义。
7. 使用现有生成脚本同步 C#、Java lite 及校验信息。真实 Android TL 类必须能解码、序列化和重新解析对应结果。

## 三、必须落实的 PostgreSQL-only 约束

1. 正常后端只从 PostgreSQL 读写业务数据。新接口直接使用已有关系表、仓储、消息接受事务及必要的新增表。
2. 正式启动要求显式 PostgreSQL 主模式。LegacyOnly、JSON 文件数据库、SnapshotBridge 等旧业务存储选择应明确拒绝，配置缺失时也必须在创建 Store、JSON 文件或锁文件之前失败。
3. 已迁移实例的启动器和发布检查必须固定要求新存储模式。覆盖 appsettings.Production.json 遗漏、连接串缺失、密钥目录丢失、环境变量冲突、schema 不兼容及数据库不可达。
4. 旧 botgram.json、旧备份和密钥保留用于审计、离线迁移和恢复。旧存储代码如有迁移用途，应隔离在离线维护范围。新增接口无需另建 JSON 存储业务实现。
5. 禁止通过 PostgreSQL 中的整库 JSON 单行快照替代已有关系化数据模型。现有关系表的 JSONB 列可按字段职责使用，并保持必要的约束和索引。
6. 配置文件、协议定义和离线只读导入文件按各自用途处理。弃用业务 JSON 文件数据库时，保留合法配置及一致备份。
7. PostgreSQL 单独运行必须支持全部新增功能。正常启动、消息接受、Outbox 完成、历史及差分不依赖 Redis、Kafka、Scylla 或外部对象存储。
8. 附件、导入数据和媒体资源的服务端权威数据使用 PostgreSQL 表、分块 bytea 或 Large Object 等合适机制。采用有界流式读写、大小限制和配额，避免整文件和整个数据库载入内存。
9. 文件系统仅承担必要的受限配置、密钥、日志、备份及可清理的临时工作文件。上传未完成、取消、失败或进程重启后，不能遗留可见的半成品或错误的消息引用。
10. 复用当前 PostgreSQL Outbox 生命周期、背压及持久事件链路。新增事件需真实完成处理、可重放及可恢复，保持容量检查有效。

## 四、实现范围与行为要求

### 基础消息及会话操作

- 完成删除、编辑、转发、输入状态、草稿、置顶、未读标记、阅读详情、消息搜索、文件夹、收藏分类及标签。
- 正确处理双方删除权限、作者/管理员权限、关联回复、实体偏移、历史分页、未读数量、最近消息和搜索可见性。
- 删除或编辑后通过原更新及差分同步到相关账号，重启后结果一致。已删除内容不能从搜索、导出、媒体引用或其他读取入口重新泄露。
- 输入状态等临时事件设置正确过期边界，避免作为永久历史反复重放。

### 媒体、附件及历史导入

- 完成发送媒体、相册、GIF、贴纸相关附件，以及所需最小上传、下载和资源引用依赖。
- 处理分块、重传、取消、配额、内容类型、权限、缩略图、文件引用、相册顺序、失败回滚和临时数据清理。
- 历史导入需要验证源格式、目标成员及权限，保持消息排序、原时间和幂等，避免导入失败留下部分可见历史。

### 群管理、邀请、评论及消息权限

- 接通成员增删、管理员与限制、资料修改、邀请链接、入群申请、群迁移及讨论回复。
- 邀请过期、撤销、重复加入、成员资格、历史可见性和分页必须有真实数据行为。
- 涉及 channels.*、upload.* 或其他请求族时，只补齐使本任务能通过正常客户端入口运行所需的依赖，并在报告中明确列出。

### 回应、投票、待办和定时消息

- 回应及计数、投票选择及撤销、待办修改、定时发送、快捷回复都必须持久化并同步。
- 定时工作采用持久任务与并发领取，重启续跑，并发或执行结果丢失重试都不能重复发送。
- 相关计数、已读标记、排序与删除操作必须与真实记录一致。

### 机器人、小程序及网页授权

- 接通 startBot、回调按钮、内联查询/结果、请求分享对象、附件菜单和 WebView 数据交换的必要业务链路。
- 验证机器人身份、所有者、会话范围、用户授权、回调过期及 WebView 来源，禁止将客户端提交的机器人身份或任意 URL 当作已授权目标。
- 机器人服务和网页来源由部署者配置，保持自部署要求和现有账号隔离。

### 原 Telegram Secret Chat 请求

- 核对这些请求的原客户端入口、密钥状态和协议语义，完成其 Protobuf 传输适配及仅密文的服务端存储和转发。
- 保留现有专用 E2EE 的单方/双方语义、PIN、本地密钥封装、身份确认和历史边界。
- 所有端到端加密路径都必须保持客户端掌握私钥，服务端不接触 PIN、私钥或消息明文。使用既有成熟密码学实现，禁止自创加密算法。
- 历史单方占位消息永久保持占位。旧 Secret Chat 请求与专用 E2EE 请求之间的关系须在代码和验收中明确，不能仅返回成功或静默转换后丢失原语义。

### 其他 messages 功能

- 覆盖附件清单中的贴纸与表情目录、主题、链接预览、位置、举报、广告、Stars 回应、翻译、转写及 AI 请求。
- 目录查询有真实的数据源、分页和更新机制。空目录需要与真实空状态一致，禁止给有数据的系统固定返回空结果。
- 外部处理能力使用部署者显式配置的服务。对接与测试须覆盖请求、鉴权、超时、取消和错误传播。未配置时返回明确可识别的配置错误，并在交付矩阵标为配置依赖。
- 配置依赖入口要验证已配置时的真实成功路径。替身测试、未配置错误和实际外部服务验收分别记录。
- 保留发送礼物暂时禁用、相关设置隐藏等用户产品约定。Stars 回应与发送礼物分别按各自的产品语义处理。

## 五、事务、身份、同步与安全

- 认证与成员权限在所有入口验证。保持 UUID、Gram ID、TL ID、会话关联和既有消息身份稳定。
- 复用现有事务及锁顺序，覆盖并发、取消、部分失败和提交响应丢失。新增幂等键或任务键必须有数据库约束及内容冲突判定。
- 消息、用户 pts、事件、接收人、Outbox 及业务状态在需要时共享同一提交边界。保持原消息事件 pts 的重试语义。
- 查询、修改、删除、搜索、导入导出、资源访问和下载都执行同一业务权限规则。
- 新表及索引使用版本化迁移，验证既有库升级、新库初始化、重复运行和事务失败回滚。
- 网络、文件和反查定位放后台，UI 回到主线程。保留账号切换、销毁、取消和迟到回调隔离。
- 原生页面继续使用现有列表、输入框、主题和弹窗。新增文案同时维护英文资源、中文导入字典及生成资源。

## 六、实施和验收

1. 建立 209 项逐接口矩阵，至少记录 TL 类、Protobuf 操作/字段、客户端入口、后端处理、PostgreSQL 表或任务、权限、同步事件、实际测试及剩余限制。
2. 按依赖组织实现顺序，先完成协议、数据模型和必要基础能力，再接入各功能及原 UI。持续完成独立部分，直到附件范围逐项有可复查结果。
3. 本 Prompt 的“实现”要求正常参数能完成真实业务。固定 501、空实现、伪造成功、吞错、绕过存储或只隐藏按钮都不能计入完成。
4. 对产品明确禁用或外部服务尚缺配置的条目，准确记录限制及已验证边界。不得把限制说明改写成完整功能验收通过。
5. 真实 PostgreSQL 测试覆盖每项操作的正常、参数错误、权限拒绝、并发、重试、重启、分页及相关同步行为。
6. 增加 Protobuf-only 与 PostgreSQL-only 启动及入口回归，证明旧 JSON 接口持续拒绝，运行中不会创建或修改 botgram.json，也不会调用旧快照桥。
7. 使用明确隔离的数据库及测试账号。正式库禁止创建测试账号或执行破坏性回归。
8. 执行相关协议生成校验、C#/Java 互操作、真实 Android TL 二进制、消息/差分/E2EE/通话及迁移回归。保留既有关键回归覆盖。
9. 所有源代码修改完成后构建 standalone APK，核验签名、包名、版本、资源与交付副本哈希。新增依赖、缓存和工具全部放在 D 盘。
10. 文档及逐接口矩阵写入 Docs/，交付报告写入 Docs/artifacts/，更新 Docs/artifacts/remaining-api-list.md 与 artifacts/build-info.json 的实际结果。
11. 提交、推送及正式部署遵循执行会话已有授权和仓库发布要求。部署时保留真实数据、密钥、一致备份与回退资料，检查实际 PostgreSQL 模式和必需工作进程。
12. 真机页面、双机加密、媒体发送、通话、前后台和后台送达分别验收。未执行的设备测试必须如实记录。

最终报告列出实际完成接口数量、完整矩阵位置、协议及 schema 变化、测试和产物、实际部署状态，以及具体未完成项。任务有阻塞时写清原因和剩余步骤，继续完成不依赖该阻塞的工作。

## 七、完整接口范围

以下 209 个请求类逐项纳入上述实现与验收矩阵。

- `org.telegram.tgnet.TLRPC$TL_messages_acceptEncryption`
- `org.telegram.tgnet.TLRPC$TL_messages_acceptUrlAuth`
- `org.telegram.tgnet.TLRPC$TL_messages_addChatUser`
- `org.telegram.tgnet.TLRPC$TL_messages_addPollAnswer`
- `org.telegram.tgnet.TLRPC$TL_messages_appendTodoList`
- `org.telegram.tgnet.TLRPC$TL_messages_checkChatInvite`
- `org.telegram.tgnet.TLRPC$TL_messages_checkHistoryImport`
- `org.telegram.tgnet.TLRPC$TL_messages_checkHistoryImportPeer`
- `org.telegram.tgnet.TLRPC$TL_messages_checkUrlAuthMatchCode`
- `org.telegram.tgnet.TLRPC$TL_messages_clearAllDrafts`
- `org.telegram.tgnet.TLRPC$TL_messages_clearRecentReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_clearRecentStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_clickSponsoredMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_composeMessageWithAI`
- `org.telegram.tgnet.TLRPC$TL_messages_composeRichMessageWithAI`
- `org.telegram.tgnet.TLRPC$TL_messages_declineUrlAuth`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteChat`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteChatUser`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteExportedChatInvite`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteParticipantReaction`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteParticipantReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_deletePhoneCallHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_deletePollAnswer`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteQuickReplyMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteQuickReplyShortcut`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteRevokedExportedChatInvites`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteSavedHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_deleteScheduledMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_discardEncryption`
- `org.telegram.tgnet.TLRPC$TL_messages_editChatAbout`
- `org.telegram.tgnet.TLRPC$TL_messages_editChatAdmin`
- `org.telegram.tgnet.TLRPC$TL_messages_editChatDefaultBannedRights`
- `org.telegram.tgnet.TLRPC$TL_messages_editChatParticipantRank`
- `org.telegram.tgnet.TLRPC$TL_messages_editChatPhoto`
- `org.telegram.tgnet.TLRPC$TL_messages_editChatTitle`
- `org.telegram.tgnet.TLRPC$TL_messages_editExportedChatInvite`
- `org.telegram.tgnet.TLRPC$TL_messages_editMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_editQuickReplyShortcut`
- `org.telegram.tgnet.TLRPC$TL_messages_exportChatInvite`
- `org.telegram.tgnet.TLRPC$TL_messages_faveSticker`
- `org.telegram.tgnet.TLRPC$TL_messages_forwardMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_getAdminsWithInvites`
- `org.telegram.tgnet.TLRPC$TL_messages_getAllDrafts`
- `org.telegram.tgnet.TLRPC$TL_messages_getAllStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getArchivedStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getAttachMenuBot`
- `org.telegram.tgnet.TLRPC$TL_messages_getAttachMenuBots`
- `org.telegram.tgnet.TLRPC$TL_messages_getAttachedStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getAvailableEffects`
- `org.telegram.tgnet.TLRPC$TL_messages_getAvailableReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_getBotApp`
- `org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer`
- `org.telegram.tgnet.TLRPC$TL_messages_getChatInviteImporters`
- `org.telegram.tgnet.TLRPC$TL_messages_getCommonChats`
- `org.telegram.tgnet.TLRPC$TL_messages_getCustomEmojiDocuments`
- `org.telegram.tgnet.TLRPC$TL_messages_getDefaultTagReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_getDialogFilters`
- `org.telegram.tgnet.TLRPC$TL_messages_getDialogUnreadMarks`
- `org.telegram.tgnet.TLRPC$TL_messages_getDiscussionMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiGameInfo`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiGroups`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiKeywords`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiKeywordsDifference`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiProfilePhotoGroups`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiStatusGroups`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiStickerGroups`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getEmojiURL`
- `org.telegram.tgnet.TLRPC$TL_messages_getExportedChatInvite`
- `org.telegram.tgnet.TLRPC$TL_messages_getExportedChatInvites`
- `org.telegram.tgnet.TLRPC$TL_messages_getExtendedMedia`
- `org.telegram.tgnet.TLRPC$TL_messages_getFavedStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getFeaturedEmojiStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getFeaturedStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getInlineBotResults`
- `org.telegram.tgnet.TLRPC$TL_messages_getMaskStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getMessageEditData`
- `org.telegram.tgnet.TLRPC$TL_messages_getMessageReactionsList`
- `org.telegram.tgnet.TLRPC$TL_messages_getMessageReadParticipants`
- `org.telegram.tgnet.TLRPC$TL_messages_getMessagesReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_getMessagesViews`
- `org.telegram.tgnet.TLRPC$TL_messages_getMyStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getOldFeaturedStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getOnlines`
- `org.telegram.tgnet.TLRPC$TL_messages_getOutboxReadDate`
- `org.telegram.tgnet.TLRPC$TL_messages_getPaidReactionPrivacy`
- `org.telegram.tgnet.TLRPC$TL_messages_getPollResults`
- `org.telegram.tgnet.TLRPC$TL_messages_getPollVotes`
- `org.telegram.tgnet.TLRPC$TL_messages_getPreparedInlineMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_getQuickReplies`
- `org.telegram.tgnet.TLRPC$TL_messages_getQuickReplyMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_getRecentLocations`
- `org.telegram.tgnet.TLRPC$TL_messages_getRecentReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_getRecentStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getReplies`
- `org.telegram.tgnet.TLRPC$TL_messages_getSavedDialogs`
- `org.telegram.tgnet.TLRPC$TL_messages_getSavedDialogsByID`
- `org.telegram.tgnet.TLRPC$TL_messages_getSavedGifs`
- `org.telegram.tgnet.TLRPC$TL_messages_getSavedHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_getSavedReactionTags`
- `org.telegram.tgnet.TLRPC$TL_messages_getScheduledHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_getScheduledMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_getSearchCounters`
- `org.telegram.tgnet.TLRPC$TL_messages_getSearchResultsCalendar`
- `org.telegram.tgnet.TLRPC$TL_messages_getSearchResultsPositions`
- `org.telegram.tgnet.TLRPC$TL_messages_getShareThemeBackdrops`
- `org.telegram.tgnet.TLRPC$TL_messages_getSponsoredMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_getStatsURL`
- `org.telegram.tgnet.TLRPC$TL_messages_getStickerSet`
- `org.telegram.tgnet.TLRPC$TL_messages_getStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_getSuggestedDialogFilters`
- `org.telegram.tgnet.TLRPC$TL_messages_getTopReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_getUnreadMentions`
- `org.telegram.tgnet.TLRPC$TL_messages_getUnreadPollVotes`
- `org.telegram.tgnet.TLRPC$TL_messages_getUnreadReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_getWebPage`
- `org.telegram.tgnet.TLRPC$TL_messages_hideChatJoinRequest`
- `org.telegram.tgnet.TLRPC$TL_messages_hidePeerSettingsBar`
- `org.telegram.tgnet.TLRPC$TL_messages_importChatInvite`
- `org.telegram.tgnet.TLRPC$TL_messages_initHistoryImport`
- `org.telegram.tgnet.TLRPC$TL_messages_installStickerSet`
- `org.telegram.tgnet.TLRPC$TL_messages_markDialogUnread`
- `org.telegram.tgnet.TLRPC$TL_messages_migrateChat`
- `org.telegram.tgnet.TLRPC$TL_messages_prolongWebView`
- `org.telegram.tgnet.TLRPC$TL_messages_rateTranscribedAudio`
- `org.telegram.tgnet.TLRPC$TL_messages_readDiscussion`
- `org.telegram.tgnet.TLRPC$TL_messages_readEncryptedHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_readFeaturedStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_readMentions`
- `org.telegram.tgnet.TLRPC$TL_messages_readMessageContents`
- `org.telegram.tgnet.TLRPC$TL_messages_readPollVotes`
- `org.telegram.tgnet.TLRPC$TL_messages_readReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_readSavedHistory`
- `org.telegram.tgnet.TLRPC$TL_messages_receivedQueue`
- `org.telegram.tgnet.TLRPC$TL_messages_reorderPinnedDialogs`
- `org.telegram.tgnet.TLRPC$TL_messages_reorderPinnedSavedDialogs`
- `org.telegram.tgnet.TLRPC$TL_messages_reorderQuickReplies`
- `org.telegram.tgnet.TLRPC$TL_messages_reorderStickerSets`
- `org.telegram.tgnet.TLRPC$TL_messages_report`
- `org.telegram.tgnet.TLRPC$TL_messages_reportEncryptedSpam`
- `org.telegram.tgnet.TLRPC$TL_messages_reportMusicListen`
- `org.telegram.tgnet.TLRPC$TL_messages_reportReaction`
- `org.telegram.tgnet.TLRPC$TL_messages_reportReadMetrics`
- `org.telegram.tgnet.TLRPC$TL_messages_reportSpam`
- `org.telegram.tgnet.TLRPC$TL_messages_reportSponsoredMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_requestAppWebView`
- `org.telegram.tgnet.TLRPC$TL_messages_requestChatJoinWebView`
- `org.telegram.tgnet.TLRPC$TL_messages_requestEncryption`
- `org.telegram.tgnet.TLRPC$TL_messages_requestMainWebView`
- `org.telegram.tgnet.TLRPC$TL_messages_requestSimpleWebView`
- `org.telegram.tgnet.TLRPC$TL_messages_requestUrlAuth`
- `org.telegram.tgnet.TLRPC$TL_messages_requestWebView`
- `org.telegram.tgnet.TLRPC$TL_messages_saveDefaultSendAs`
- `org.telegram.tgnet.TLRPC$TL_messages_saveDraft`
- `org.telegram.tgnet.TLRPC$TL_messages_saveGif`
- `org.telegram.tgnet.TLRPC$TL_messages_saveRecentSticker`
- `org.telegram.tgnet.TLRPC$TL_messages_search`
- `org.telegram.tgnet.TLRPC$TL_messages_searchCustomEmoji`
- `org.telegram.tgnet.TLRPC$TL_messages_searchEmojiStickerSets`
- `org.telegram.tgnet.TLRPC$TL_messages_searchGlobal`
- `org.telegram.tgnet.TLRPC$TL_messages_searchStickerSets`
- `org.telegram.tgnet.TLRPC$TL_messages_searchStickers`
- `org.telegram.tgnet.TLRPC$TL_messages_sendBotRequestedPeer`
- `org.telegram.tgnet.TLRPC$TL_messages_sendEncrypted`
- `org.telegram.tgnet.TLRPC$TL_messages_sendEncryptedFile`
- `org.telegram.tgnet.TLRPC$TL_messages_sendEncryptedMultiMedia`
- `org.telegram.tgnet.TLRPC$TL_messages_sendEncryptedService`
- `org.telegram.tgnet.TLRPC$TL_messages_sendInlineBotResult`
- `org.telegram.tgnet.TLRPC$TL_messages_sendMedia`
- `org.telegram.tgnet.TLRPC$TL_messages_sendMultiMedia`
- `org.telegram.tgnet.TLRPC$TL_messages_sendPaidReaction`
- `org.telegram.tgnet.TLRPC$TL_messages_sendQuickReplyMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_sendReaction`
- `org.telegram.tgnet.TLRPC$TL_messages_sendScheduledMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_sendScreenshotNotification`
- `org.telegram.tgnet.TLRPC$TL_messages_sendVote`
- `org.telegram.tgnet.TLRPC$TL_messages_sendWebViewData`
- `org.telegram.tgnet.TLRPC$TL_messages_setChatAvailableReactions`
- `org.telegram.tgnet.TLRPC$TL_messages_setChatTheme`
- `org.telegram.tgnet.TLRPC$TL_messages_setEncryptedTyping`
- `org.telegram.tgnet.TLRPC$TL_messages_setHistoryTTL`
- `org.telegram.tgnet.TLRPC$TL_messages_setShareTheme`
- `org.telegram.tgnet.TLRPC$TL_messages_setTyping`
- `org.telegram.tgnet.TLRPC$TL_messages_startBot`
- `org.telegram.tgnet.TLRPC$TL_messages_startHistoryImport`
- `org.telegram.tgnet.TLRPC$TL_messages_summarizeText`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleBotInAttachMenu`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleDialogFilterTags`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleDialogPin`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleNoForwards`
- `org.telegram.tgnet.TLRPC$TL_messages_togglePaidReactionPrivacy`
- `org.telegram.tgnet.TLRPC$TL_messages_togglePeerTranslations`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleStickerSets`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleSuggestedPostApproval`
- `org.telegram.tgnet.TLRPC$TL_messages_toggleTodoCompleted`
- `org.telegram.tgnet.TLRPC$TL_messages_transcribeAudio`
- `org.telegram.tgnet.TLRPC$TL_messages_translateRichMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_translateText`
- `org.telegram.tgnet.TLRPC$TL_messages_uninstallStickerSet`
- `org.telegram.tgnet.TLRPC$TL_messages_unpinAllMessages`
- `org.telegram.tgnet.TLRPC$TL_messages_updateDialogFilter`
- `org.telegram.tgnet.TLRPC$TL_messages_updateDialogFiltersOrder`
- `org.telegram.tgnet.TLRPC$TL_messages_updatePinnedMessage`
- `org.telegram.tgnet.TLRPC$TL_messages_updateSavedReactionTag`
- `org.telegram.tgnet.TLRPC$TL_messages_uploadImportedMedia`
- `org.telegram.tgnet.TLRPC$TL_messages_uploadMedia`
- `org.telegram.tgnet.TLRPC$TL_messages_viewSponsoredMessage`
