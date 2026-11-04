# 旧 JSON RPC P0 基线覆盖矩阵

> 2026-11-02 续测更新：下文原始83条基线及缺项保留为历史记录，以文末“P0 边界续测”修订为准；不表示全覆盖。

验证日期：2026-11-02。后端起点：`67d5806`。本次仅修改后端测试，未实现或启用 Protobuf。

## 统计口径

规范方法与建议 tag 来自 `Botgram-RPC-Inventory.json` 的 `suggestedProtoOneofTags`，共 21 项。只统计本次隔离 HTTP 执行产生的 `../Botgram.Backend/obj/{smoke,gid,contacts}-rpc-fixtures.json`，不把静态 case、手工响应、REST 测试或 Android 调用点当作 RPC 实测覆盖。`getNotifySettings` 按清单别名归一到 `TL_account_getNotifySettings`（120），不是新增方法。

三个 fixture 文件共 83 条调用：59 条 HTTP 200、24 条非 200。21/21 规范方法均至少一次成功；仅 7/21 方法有非 200 样本，另有一个非基线方法的 501 样本。这是方法入口基线，不是字段、分支、状态或端到端全覆盖。

| tag | 规范 RPC | HTTP 200 次数 | 已观察非 200 状态 | 证据套件 |
|---|---|---:|---|---|
| 100 | TL_updates_getState | 4 | 401 | smoke, gid, contacts |
| 101 | TL_updates_getDifference | 6 | — | smoke, gid, contacts |
| 102 | TL_messages_getDialogs | 2 | — | smoke |
| 103 | TL_messages_getPinnedDialogs | 1 | — | smoke |
| 104 | TL_messages_getPeerDialogs | 1 | — | smoke |
| 105 | TL_messages_getHistory | 4 | 404 | smoke, contacts |
| 106 | TL_messages_getMessages | 2 | — | smoke |
| 107 | TL_messages_sendMessage | 6 | 400, 403, 409 | smoke, contacts |
| 108 | TL_messages_readHistory | 1 | — | smoke |
| 109 | TL_contacts_search | 1 | — | smoke |
| 110 | TL_contacts_resolveUsername | 1 | 400 | smoke |
| 111 | TL_users_getUsers | 2 | — | smoke, gid |
| 112 | TL_contacts_getContacts | 14 | 401 | contacts |
| 113 | TL_contacts_resolvePhone | 2 | 400 | contacts |
| 114 | TL_contacts_importContacts | 6 | 400, 401, 501 | contacts |
| 115 | TL_messages_createChat | 1 | — | smoke |
| 116 | TL_messages_getPeerSettings | 1 | — | smoke |
| 117 | TL_users_getFullUser | 1 | — | smoke |
| 118 | TL_messages_getFullChat | 1 | — | smoke |
| 119 | TL_messages_getChats | 1 | — | smoke |
| 120 | TL_account_getNotifySettings | 1 | — | smoke |

`—` 表示未实测该方法的非 200 情形，不表示不存在错误分支。HTTP 200 也可能是隔离后空结果（如局外人 getMessages），不能一律解读为业务数据可见。

## 已执行场景与断言边界

- smoke：新对话历史与用户、收藏夹、用户 Vector、用户名搜索/大小写解析与缺失、状态初始 pts=1、单聊与回复、self send_as 与冒充拒绝、random_id 去重/冲突、群创建和群服务消息、富文本 bold/静音/同会话回复、跨会话回复拒绝、非成员历史拒绝、差分和空差分、群成员读取、会话列表与 limit=1 切片、已读、按 ID 重载和非成员空结果。
- contacts：空列表、GID 解析/导入、私有姓名与所属者隔离、互为联系人标志、重复导入、缺失/自身、非法号码/姓名/批量/输入对象、notes 不支持、批次回滚、GID 变更差分、添加后消息、重启及旧快照。无认证 getContacts/importContacts 也被记录。
- gid：区域分配与回退、29 区域、号码校验、同主号码、碰撞/并发、重启与迁移；RPC 样本仅是 getState/getDifference/getUsers，REST 场景不计入 RPC 次数。原手工 Vector 已改为真实 getUsers 响应。
- 新增关键错误字符串断言：`USERNAME_NOT_OCCUPIED`、无认证 `unauthorized`、logout 后 `session_expired`、未支持方法 `BOTGRAM_RPC_UNSUPPORTED:TL_messages_deleteMessages`。其余既有错误场景仍主要断言 HTTP 状态，fixture 保存实际错误正文，并非所有错误字符串都有断言。
- getPinnedDialogs/getChats/getNotifySettings 等已有调用主要检查 HTTP 成功并留样，不宣称已穷举返回字段的语义。

## P0 尚缺与迁移阻断项

1. 尚无 Protobuf 编解码器、schema/tag 实现、JSON↔Protobuf 差分、双协议回退或协商测试；文件名不代表协议已迁移。
2. fixture 是测试端构造 JSON 请求及服务端响应，不是 Android 真实编码器输出；本次未执行 Android TL 二进制往返、APK、真机页面、取消/迟到回调、账号切换或绑定账号禁止回原生网络验证。
3. JSON `$type` 实际类名有样本；未覆盖所有字节数组/base64、int64 极值、未知类型/字段、缺省/null、畸形 JSON、非法反射类型或请求体限制。样本未含 bytes 字段，不声明 bytes 冻结完成。
4. 差分正常/空结果与 GID 更新已有；超过 100 条 differenceSlice、differenceTooLong、全部已读更新顺序/重放边界、全部历史锚点/过滤组合未覆盖。
5. 21 个方法并未逐一验证未认证、失效会话、权限、参数边界及完整错误字符串；通知设置仅实测 Java 类名别名入口，未单独调用规范旧类名入口。
6. 消息实体只实测 bold，未覆盖所有 UTF-16/代理对边界、实体类型、定时/markup/reply unsupported 分支；联系人全部长度极值、群规模/标题上限等仍有缺项。
7. 仅 deleteMessages 验证通用 unsupported 501，不代表全部未实现 RPC 已验证；原生 phone.*、附件、后台推送仍不能宣称可用。REST WebRTC 信令 PASS 不等于原生通话接通。

## fixture 格式与安全范围

每条包含 `requestType`、完整 JSON `request`、实测 `status`、断言预期 `expectedStatus`、实际 `response`。在状态断言前记录，finally 停服后写入，因此 HTTP 非预期失败也可留证；网络失败或无效 JSON 在当前解析方式下不会形成完整记录，属于限制。数组下标是文件内执行顺序，不是跨套件事件游标。

仅记录 `/api/telegram/rpc` 的 JSON body，不保存请求头、Bearer、密码、注册/登录正文或响应；用户与 GID 均来自隔离测试库。保留原 `tl-fixtures.json`、`gid-tl-fixtures.json`、`contact-tl-fixtures.json` 的成功响应结构，避免现有宿主 TL 验证消费者误读错误对象。

## P0 边界续测（2026-11-02）

实际新增 `../Botgram.Backend/tests/rpc-boundaries.mjs`，并在 smoke 加入正常 difference 具体类型断言；业务及 Android 源码未改。隔离运行两套均 PASS。

- 差分四分支：Empty、difference、differenceSlice、TooLong；105条消息按100+5分页，精确核对ID顺序、无重复遗漏、pts=101/106、pts=0与1起点等价、切片重放、终点Empty与超前107 TooLong。另验证已读游标不回退、一次Inbox更新及pts递增；未穷举所有消息/回执/GID混排跨页。
- `random_id`：long最小/最大值、0、-1。以原始JSON数字文本发送，核对响应原始数字、幂等重试及正文变更409，不经过JS Number转换。成功TL fixture直接拼接原始响应JSON，保留int64；普通解析后的request/response仍可能舍入，精确证据是额外`rawRequest/rawResponse`字段和成功TL文件。
- 全部15种已支持实体在合法UTF-16代理对区间原样返回，包括Pre.language/TextUrl.url；拒绝代理对起点/终点切半、负/零/越界长度、101实体、非法URL与不支持实体。不是全部实体排列组合、全部长度极值或安全URL策略测试。
- 可选字段：缺省、null、空entities、false/true silent、合法reply/self send_as、flags；schedule/markup和高级reply明确501。权限：冒充403；局外人群send/read/fullChat/peerSettings为404，getMessages为空。新增失败均严格断言错误字符串。

新增 `obj/boundary-rpc-fixtures.json`：156条=131成功+25失败（400×10、403×1、404×4、409×4、501×6）；成功响应 `obj/boundary-tl-fixtures.json` 共131条，格式与tl-fixtures一致。与原三套合计239条=190成功+49失败；入口仍21/21，非200入口增至10/21，不称全覆盖。新增非200方法是readHistory/getFullChat/getPeerSettings。

主执行agent本轮实际完成真实TL往返smoke30+gid2+contacts26（58份base64二进制留样），SessionScope PASS，Android standalone debug构建exit0；新增boundary 131份仍待主agent宿主回读，不能将HTTP PASS等同于其二进制PASS。未交付新APK、未真机。Protobuf、bytes、畸形/超限请求、全方法权限与字段组合等原剩余门槛继续保留。
