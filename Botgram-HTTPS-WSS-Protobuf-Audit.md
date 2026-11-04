# Botgram HTTPS/WSS 审计与 Protobuf 强类型迁移蓝图

审计日期：2026-11-01（Asia/Shanghai）。范围：当前两个仓库的工作区，而非仅已提交的 HEAD。客户端 HEAD 为 `37eea1e0d275f8bb0c1ab4ab796594573eed5b37`；后端 HEAD 为 `df7594834d68014fb2492975c1d4ebc705af0da0`。两个仓库均存在既有未提交修改。

**本轮只做审计、隔离测试和方案交付。未实施 Protobuf，未修改业务代码或生产配置，未部署、重启服务、变更防火墙/证书、写入正式账号或数据库，也未构建交付新 APK。**

配套交付：[RPC 完整静态清单](computer://W:/Work/Botgram/Botgram-RPC-Inventory.md)、[可复算 JSON 数据](computer://W:/Work/Botgram/Botgram-RPC-Inventory.json)。完整调用位置、字段、类型闭包及统计口径以这两份清单为准。

## 0. 阅读结论与证据规则

### 0.1 结论

1. 当前链路是 **HTTPS + Bearer + JSON TL 对象 RPC**，不是 MTProto。WSS 传递事件提示，原 Android `MessagesController.getDifference` 才负责 TL 差分入库。更换序列化格式不会自动修复现有业务或同步竞态。
2. 本轮只读公网探针验证了正常域名的受信任证书、TLS 1.2/1.3、TLS 1.0/1.1 被远端拒绝、健康检查以及未认证 API 返回 401。未验证 Android 真机/API23 的证书兼容性。
3. **发布前阻塞项：HTTP80 的观测结果不一致，部分返回 DNSPod 拦截页面。当前 ACME 配置依赖 HTTP-01；不能证明续期必然失败，但绝不能据此宣称续期链路已验证。**
4. **高优先级同步风险：差分响应进入控制器后，跨 UI/storage/stage 队列没有贯穿的会话代际校验；同槽位退出重登存在旧数据回灌的可成立时序。此项为代码推断，未动态复现。**
5. 后端存在 21 个规范 TL 方法处理分支，另有一个 `getNotifySettings` 名称别名。它们只是兼容子集，不能把静态扫描发现的所有 Android 请求、Telegram 官方 Schema 或完整多态类型闭包算作已支持。
6. 推荐迁移路径是：**先补安全/同步验收 → 提取强类型 Service 并让旧入口复用 → 设计冻结和生成协议 → 添加 Protobuf 适配器 → Android 显式 TL Mapper → 双协议验证/灰度 → 有条件退役 JSON RPC**。禁止 Protobuf 转 JSON 再调用旧 Gateway。

### 0.2 证据标签

- **[代码确认]**：已阅读当前源码/配置；只证明实现存在，不证明部署实例一致。
- **[本轮测试]**：本轮实际运行且观察到结果；适用边界逐项说明。
- **[架构推断]**：由实现和可成立调度推导，未复现，不宣称事故已经发生。
- **[未验证]**：缺少运行实例、真实设备、认证或时序证据。
- 方案、协议名、拟新增路径和阶段门槛均为**建议**，不是现有能力。

## 1. 实际结构与入口

| 范围 | 当前文件与职责 |
|---|---|
| 客户端地址 | [BotgramServerAddress.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramServerAddress.java)：默认 HTTPS8521、表单解析、5080迁移 |
| 客户端 HTTP | [BotgramApi.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramApi.java)：Bearer、TLS错误、重定向/大小限制 |
| 客户端会话 | [BotgramSession.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramSession.java)：账号槽位、Keystore持久化 |
| TL 适配与轮询 | [BotgramTransport.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramTransport.java)：反射JSON、请求取消、差分唤醒 |
| 客户端 WSS | [BotgramEventSocket.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramEventSocket.java)：连接generation、cursor、重连 |
| 原 Telegram 控制器 | [MessagesController.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java)：差分、UI缓存与存储队列 |
| HTTP/WSS 服务端 | [Program.cs](file:///W:/Work/Botgram.Backend/Program.cs)：路由、鉴权、限流、代理头、事件流 |
| JSON TL 兼容 | [TelegramGateway.cs](file:///W:/Work/Botgram.Backend/TelegramGateway.cs)：按$type分派、业务与TL JSON投影混合 |
| 数据/事务 | [Models.cs](file:///W:/Work/Botgram.Backend/Models.cs)、[Store.cs](file:///W:/Work/Botgram.Backend/Store.cs)：稳定ID、文件租约、原子快照与回滚 |
| 身份/GID | [Auth.cs](file:///W:/Work/Botgram.Backend/Auth.cs)、[Gids.cs](file:///W:/Work/Botgram.Backend/Gids.cs)：认证、权限、号码分配 |
| TLS终止 | [Caddyfile.https](file:///W:/Work/Botgram.Backend/Caddyfile.https)：现有Caddy配置的追加片段，不是完整运行配置 |
| 容器 | [compose.https.yaml](file:///W:/Work/Botgram.Backend/compose.https.yaml)、[Dockerfile](file:///W:/Work/Botgram.Backend/Dockerfile)：后端容器与外部网络 |
| 部署约定 | [DEPLOYMENT.md](file:///W:/Work/Botgram.Backend/DEPLOYMENT.md)：仅作定位和验证要求，不把历史记录当本轮证据 |

两个仓库各自持有 M49 数据；后端独立构建不依赖客户端。客户端继续保留原 Telegram UI、TL 类型与控制器，不能另建聊天模型绕过服务端。

## 2. HTTPS/TLS 审计

### 2.1 Android 地址与 HTTP

**[代码确认]** 地址解析器缺省补 `https://` 和8521，保留显式合法自定义端口，拒绝 userinfo/query/fragment/非根路径；`migrateLegacy` 将任意主机显式5080迁至8521，不只迁移历史默认域名。后续应确认这是产品预期，防止误改自定义5080服务。

**边界差异**：登录表单使用解析器，但 API 构造和会话恢复并非完全相同的规范化链。恢复只调用旧端口迁移，不统一补缺省8521；不能声称任意入口都自动补端口。

[BotgramApi.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramApi.java#L24-L85) 非 Debug 拒绝HTTP；Debug接受显式HTTP origin，范围并不限于localhost。关闭自动重定向，避免凭据被自动带至其他origin；连接/读超时10/20秒，响应上限8MiB，Bearer放header，未见信任全部证书或绕过主机名校验。

[AndroidManifest.xml](file:///W:/Work/Botgram/TMessagesProj/src/main/AndroidManifest.xml#L111-L114) 存在 `usesCleartextTraffic=true`，因此“整个APK禁用明文网络”不成立。其浏览器等路径与 Botgram API 自身的 release HTTPS限制需要分开审计。实际交付 debug APK允许HTTP，不能用release策略替代debug风险说明。不要未经任务授权全局关闭明文而破坏原应用浏览器。

**[代码确认]** [BotgramSession.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramSession.java) 使用 Android Keystore AES/GCM保存令牌，按账号槽位隔离偏好；取消登录后的 `clearIfCurrent` 防止清掉更新的token。public可变会话字段仍需要代际/不可变凭据快照约束。尚未做锁屏、重装、Keystore失效、API23设备测试。

### 2.2 代理与容器

**[代码确认]** [Caddyfile.https](file:///W:/Work/Botgram.Backend/Caddyfile.https) 配置TLS1.2/1.3、HSTS一年、128KB请求体、上游HTTP8521。禁用TLS-ALPN挑战，当前ACME仍依赖HTTP-01。HTTPS在Caddy终止，容器私网到后端为HTTP，这不是端到端TLS。

**[代码确认]** [compose.https.yaml](file:///W:/Work/Botgram.Backend/compose.https.yaml) 后端没有host `ports`映射，使用外部 `botgram_edge` 网络，后端/代理地址为172.28.52.3/.2。外部网络不等于Docker `internal:true`；同网其他容器的接入权限是安全边界。配置的非root、只读根、cap_drop、no-new-privileges是防护，但不等于已核实运行实例。Docker `EXPOSE`不代表发布端口。

**[代码确认/本轮测试]** [Program.cs](file:///W:/Work/Botgram.Backend/Program.cs#L12-L40) 仅配置指定KnownProxies，ForwardLimit=1，信任X-Forwarded-For/Proto，位于鉴权/限流前。Caddy覆盖对应header并移除Forwarded/X-Real-IP。三种隔离测试通过：禁用、不受信任、单跳受信任。仍须核实生产Docker实际IP/网络，不能为了“修复代理”信任任意来源。默认 `AllowedHosts=*` 与compose覆盖域名应区分。

**[未验证]** Caddy完整配置、宿主端口映射、证书存储卷持久化/备份权限、重启策略、镜像实际digest、远端防火墙/直连Kestrel可达性、自动续期结果。当前只提供Caddy片段而非Caddy服务定义。后端Docker镜像使用可变 .NET8 tag，建议下一轮锁定部署digest并记录升级策略。

### 2.3 只读公网观测

目标为配置中的 `nyarugram.moe.autos:8521`；不使用真实账号或生产令牌。

| 检查 | 本轮结果与限制 |
|---|---|
| HTTPS健康 | GET /health：200，协议标识botgram-v1 |
| 未认证API | GET /api/telegram/rpc：401 unauthorized；只证明鉴权拦截，不证明POST业务/媒体类型正确 |
| TLS1.2/1.3 | Node默认证书信任验证通过，authorized=true |
| TLS1.0/1.1 | 首探针本机禁用，无效；允许旧算法的独立探针后收到远端protocol_version alert70，确认远端拒绝 |
| 证书 | 域名CN/SAN匹配，Let's Encrypt YE1；有效期2026-11-01 06:35:35 GMT至2027-01-30 06:35:34 GMT |
| HSTS | max-age=31536000 |
| 错误SNI | 服务端拒绝握手；不能将它单独当成客户端hostname mismatch测试通过 |
| HTTP80 | GET /health及部分ACME样式路径返回302 DNSPod webblock；一次HEAD不存在ACME路径返回Caddy308，随后GET又302。存在方法/路径/网络时点差异，原因未查明 |

**续期处置建议（尚未执行）**：由有运维授权人员核实DNS、80转发/拦截、完整Caddy配置和现有ACME日志；在不影响正式证书的staging流程验证挑战、容器重启后证书卷和续期告警。不要对正式证书反复签发，也不要未经批准开放新端口。HTTP80异常不能推导当前HTTPS不可用，也不能忽略。

## 3. WSS、认证与差分审计

### 3.1 已实现链路

**[代码确认]** [BotgramEventSocket.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramEventSocket.java#L55-L163) 只连接HTTPS对应WSS，不把Bearer降级到ws。使用系统TLS和hostname验证，Authorization header，8MiB payload限制，15秒ping，10秒连接和20秒socket超时。session/server/token切换先停旧连接，清cursor，generation保护旧socket回调。异步close可短暂出现两个物理连接，不等于双重业务更新。

**[代码确认]** 收到batch先推进WSS cursor，再唤醒差分；连接成功也唤醒。WSS事件正文不直接作为聊天入库来源。因此cursor推进只代表提示已见，不代表业务消费完成；不能仅据此断言永久丢消息。

**[代码确认]** [BotgramTransport.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramTransport.java#L45-L151) 仅选中账号维持WSS，已激活绑定账号由前台3秒轮询兜底。后台不保证实时通知，不存在已经实现后台推送的证据。网络切换没有直接重建此WSS；亮屏与activity前台条件也不能混为一谈。

**[代码确认]** [Program.cs](file:///W:/Work/Botgram.Backend/Program.cs#L195-L264) 认证后升级；事件按recipients过滤并分页，服务器循环约500ms检查会话、发送事件。WebSocket keepalive20秒。不能把500ms称为撤销严格时限，因为数据库检查、事件读取、网络发送不原子。

### 3.2 风险与可复现调度

| ID/级别 | 结论、影响与证据状态 | 下一轮修复/验收建议 |
|---|---|---|
| W1 高 | **[架构推断]** Transport移除pending并派发后，控制器在UI/storage/stage多队列继续工作。退出取消pending无法召回已派发响应；同槽位重绑可能写入旧users/messages/pts。见[MessagesController.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java#L16955-L17101) | 捕获account+session epoch，所有异步边界与提交前校验；旧epoch不得写库/更新UI/推进pts。用可控队列阻塞复现“响应到达→退出→重登→释放旧队列” |
| W2 中 | **[架构推断]** ConnectionsManager先排stage，Transport执行时才捕获session；旧调用在真正建job前遇到重绑，可能用新凭据发送 | 请求创建时绑定epoch/不可变凭据；排队期间重绑应取消，不可移花接木 |
| W3 中 | **[代码确认/架构推断]** getDifference忙时直接return，无dirty补拉；提示可能被合并丢弃，通常由前台轮询兜底，并非已证实永久丢消息 | dirty位/完成后补拉；用事件恰在尾页计算后到达的时序验收 |
| W4 中 | **[代码确认]** 1→2→4…30秒退避无jitter，成功握手即重置；101后立刻断开可持续约1秒重连 | 有jitter、稳定连接后重置、401停止、429尊重重试策略；验证多客户端和反复短连接 |
| W5 中 | **[未验证]** ping/socket timeout参数存在，不代表已证明静默黑洞、pong期限、网络切换恢复上限 | Android真实库/设备断网、黑洞、Wi-Fi/蜂窝切换、休眠恢复测试 |
| W6 中 | **[代码确认/架构推断]** 服务端会话检查与Read/Send分离，撤销后可能多发一批；HTTP认证与写事务也分离 | 明确撤销线性化契约；敏感提交在事务内再次确认session，避免持锁网络发送；验收撤销与在途请求竞争 |
| W7 中 | **[代码确认]** 离线logout远端撤销失败被吞；本地退出不等于远端token已撤销 | 明确设备退出语义与会话管理，不能宣传即时远程撤销 |
| W8 中 | **[代码确认/架构推断]** loadCurrentState非401立即重试，快速500/429可放大请求 | 可取消退避和错误分类；不要自动切回原Telegram网络 |
| W9 低 | **[代码确认/架构推断]** 无关用户事件使空batch cursor前进并唤醒差分；不见正文泄漏，但可观察全局活动节奏且增加无效流量 | 产品选择是否使用每用户事件游标/只提示可见变化；不能复用TL pts冒充事件cursor |
| W10 中 | **[未验证]** 首次pts=0先getState，将当前水位设为已知；dialogs/history是否完整补齐期间事件尚无时序测试 | 初始化、旧缓存、并发消息和重启组合验收 |

W1建议在迁移前修复，不能把Protobuf上线当作其修复证据。目前未发现证据证明Bearer泄漏或跨账号事故已经发生。

### 3.3 游标不变量

- 全局事件ID、全局消息ID、每用户TL pts、WSS cursor四者不可互换。
- [TelegramGateway.cs](file:///W:/Work/Botgram.Backend/TelegramGateway.cs#L88-L95) 当前pts为 `1 + 可见且指定类型事件数`，不是事件ID。差分每页100；超界TooLong、空页Empty、有后续Slice。
- 当前TL关注message.created、receipt.updated、user.gid_changed；不要未经持久化迁移清理这些事件，否则Count型pts可能回退。
- 连续GID变更回放从当前User读GID，不是逐条历史payload值。下一轮必须决定保留“当前状态折叠”还是历史语义，不能在Mapper里悄悄改变。
- WSS恢复成功只能触发追赶；真正验收要检查消息完整、幂等、权限、pts提交和重启后连续性。

## 4. JSON RPC 与类型盘点解读

### 4.1 当前编码问题

[BotgramTransport.java](file:///W:/Work/Botgram/TMessagesProj/src/main/java/org/telegram/botgram/BotgramTransport.java#L208-L263) 反射全部public非static字段，Java完整类名作为$type，byte[]用Base64，递归深度32。decode按Java字段读取，缺失保留默认、未知字段忽略；Vector特殊处理。这样的兼容性是隐式规则，不是可靠Schema。

[TelegramGateway.cs](file:///W:/Work/Botgram.Backend/TelegramGateway.cs#L14-L48) 依赖字符串$type、JsonObject、缺省0/空字符串与checked整数转换；业务与TL投影耦合。完整Java类名、包路径变化、flags与字段不一致、构造类差异会影响反射和真实TL二进制编码。

**清单必须分层阅读**：静态发现请求 ≠ 活跃绑定账号可达 ≠ 服务端实现 ≠ 真机跑过。字段类型闭包包括继承和多态候选，不能把它作为本轮流量采集。清单无运行轨迹；无法靠静态分析证明“所有实际运行过的方法”。

| 统计项 | 数量/解释 |
|---|---|
| 扫描Android Java文件 / 检测到RPC定义 | 4,921 / 814 |
| 静态RPC候选并集 | 756，含构造补充，不等于实际运行全集 |
| send候选位置 / 静态解析成功 / 未解析 | 1,424 / 1,391 / 33；33处gap逐条保留 |
| 解析send对应不同RPC | 750 |
| 后端规范支持 / case标签 | 21 / 22，含一个别名；另1个logout走REST特例 |
| 明确未支持候选 | 734；与21+1合计756 |
| 请求/响应声明递归闭包 | 1,346类型 |
| 展开已知子类的多态上界 | 3,364类型，不能称实际嵌套运行集合 |
| 上界中的Java嵌套类 / 定义文件 | 3,361 / 24；嵌套类按二进制类名含$统计，不等同对象嵌套深度 |
| 支持请求声明闭包 / 多态上界 | 179 / 1,692 |
| 后端具体响应种子 / 声明闭包 | 63 / 204；含实体种子，区别于tl-schema脚本的56类口径 |
| 静态扫描测试文件 | 32，名字引用不能证明逐参数覆盖 |

清单使用有限词法/变量传播而非完整Java编译器和调用图，也未解析Gradle实际变体、JNI或动态构造。其“testsExecuted=0”只描述**生成清单的子任务未执行测试**，主审计实际执行结果见8.1；两者不是互相否定。完整运行全集仍需下一轮获授权后在隔离客户端采集。

清单P0/P1/P2/P3+是**RPC工作包分组**；本报告P0—P7是**总体实施阶段**，不直接一一对应。清单P1/P2均需在总体P1提取共享业务，再经总体P2—P5迁移；清单P3+为未支持功能，明确排除本次协议迁移范围。

### 4.2 规范处理子集

当前21方法：updates.getState/getDifference；messages.getDialogs/getPinnedDialogs/getPeerDialogs/getHistory/getMessages/sendMessage/readHistory/createChat/getPeerSettings/getFullChat/getChats；contacts.search/resolveUsername/getContacts/resolvePhone/importContacts；users.getUsers/getFullUser；account.getNotifySettings。命名对应当前Java类，而不是官方Schema推断。

注意已有能力限制：pinned/folder返回空兼容结果；联系人search为精确username匹配而非任意全文；schedule/reply_markup、替代send_as、部分reply模式明确拒绝。其他TL方法501 `BOTGRAM_RPC_UNSUPPORTED:<method>`；附件、原生phone.*、MTProto、后台推送仍不支持。REST WebRTC信令不能算原通话打通。

迁移覆盖每个方法的字段、嵌套对象、返回分支、忽略/拒绝行为、业务依赖、测试及建议tag，以配套清单逐行追踪，不从官方TL全量自动生成万能协议。

## 5. Protobuf Schema 设计（建议，未实施）

### 5.1 协议边界

建议独立版本化POST入口 `/api/rpc/v1`，Content-Type/Accept 为 `application/x-protobuf`，继续HTTPS/Bearer，不把token放消息或URL。现有JSON入口保持兼容。能力协商是建议新增接口/握手，不把当前 `/health` 的botgram-v1标识当作支持Protobuf。

不要求gRPC：现有HTTP请求/响应和WSS提示足够，先降低Android与代理变更范围。WSS先保持现状，后续独立迁移二进制EventHint，必须协商子协议或版本，不能将JSON文本和二进制混发给旧客户端。

### 5.2 Envelope草案

以下是**设计示例，不是已编译协议**；21个payload字段和请求/响应映射需按清单冻结。不得上线一个bytes/JSON万能payload替代具体类型。

```proto
syntax = "proto3";
package botgram.rpc.v1;
option java_package = "org.telegram.botgram.proto.v1";
option java_multiple_files = true;
option csharp_namespace = "Botgram.Contracts.V1";

message RpcRequest {
  uint32 protocol_major = 1;
  string request_id = 2;
  oneof operation {
    // 具体方法使用清单建议tag；冻结后永不复用。
    SendMessageRequest send_message = 107; // 与清单登记表一致
  }
}
message RpcResponse {
  uint32 protocol_major = 1;
  string request_id = 2;
  oneof outcome {
    RpcError error = 10;
    SendMessageResult send_message = 107; // 同方法对应，但响应独立定义
  }
}
message RpcError {
  string code = 1;
  string message = 2;
  bool retryable = 3;
  optional uint32 retry_after_ms = 4;
}
message UserRef {
  string server_domain = 1;
  string user_id = 2;
}
message PeerRef {
  oneof peer {
    UserRef user = 1;
    string group_id = 2;
    bool self = 3; // 必须true，false视为无效输入
  }
}
message SendMessageRequest {
  PeerRef peer = 1;
  sfixed64 random_id = 2;
  string text = 3;
  repeated MessageEntity entities = 4;
  optional int64 reply_to_message_id = 5;
  bool silent = 6;
}
message MessageEntity {
  uint32 offset_utf16 = 1;
  uint32 length_utf16 = 2;
  oneof kind {
    PlainEntity bold = 10;
    PlainEntity italic = 11;
    PreEntity pre = 12;
    TextUrlEntity text_url = 13;
    // 其余当前支持实体显式列举；不使用$type或任意Struct。
  }
}
message PlainEntity {}
message PreEntity { string language = 1; }
message TextUrlEntity { string url = 1; }
// SendMessageResult等见下列业务模型及清单映射要求。
```

草案故意不伪称可直接编译：完整协议文件和生成代码属于下一轮实施；此轮设计冻结输入是清单、字段规则与结果矩阵。示例send_message采用清单107号；21方法完整编号以清单100—120登记表为唯一依据，不能同时维护两套编号。真实通知请求类为 `org.telegram.tgnet.tl.TL_account$getNotifySettings`，与规范后端标签共用120，别名不另分tag。

### 5.3 类型与身份决策

| 概念 | 建议类型/语义 |
|---|---|
| 用户身份 | `UserRef { server_domain:string, user_id:string }`；user_id始终字符串，不以GID/username/TL long替代 |
| 当前身份来源 | 现有User.Id是UUID字符串；REST id即该值。建议v1沿用为稳定user_id，server_domain为规范服务器身份；需在协议冻结前批准，不擅自创建新ID或重写旧UUID |
| TL兼容ID | 独立 `int64 tl_user_id/tl_chat_id` 兼容字段或服务器映射结果；不是全局用户主键。群组及conversation ID继续字符串 |
| GID | string，规范11位数字，对外展示另格式化；可变，不参与用户主键/路由。TL phone只兼容，不拨号 |
| 消息ID | int64业务序列；写入原TL int32时checked，达到上限明确失败/规划升级，不能截断 |
| random_id | sfixed64保留Java signed long全部位；request_id仅关联，不代替业务幂等键 |
| pts/qts/seq | 与原控制器匹配的int32范围，checked；不要换成事件cursor |
| WSS cursor/event ID | int64非负，不作TL pts |
| 时间 | 统一int64 Unix毫秒或Timestamp；本方案推荐Unix毫秒；TL秒投影显式范围检查 |
| 文本实体 | offset/length是UTF-16 code unit，非UTF-8字节或Unicode标量；支持的15种实体逐一oneof |
| 可选标量 | 有“未设置”语义时使用proto3 optional；否则不得用0/空串同时表达缺失和有效值 |
| 多态 | Peer/Message/Update/Difference/Entity用oneof；缺失oneof报协议错误，不默认成功 |
| 空集合 | repeated默认空；如需区分未提交和清空，用外层message presence，不发明null数组 |
| 大整数 | protobuf原生64位，不经JavaScript Number/JSON；辅助JSON导出用字符串或可验证规则 |

**身份阻塞项**：现存系统没有server_domain持久身份、跨服user_id或联邦成员模型；不得把“Schema预留”写成“S2S已实现”。v1仅接受本地authority，未知域返回明确unsupported；未来联邦需另做身份认证、域名变更和签名设计。本轮不实现S2S。

### 5.4 请求/结果模型与21方法映射

所有请求定义只承诺已实现行为；清单中的现存但不支持/忽略字段必须有明确处置。建议对可能改变业务意图的非默认未知功能返回unsupported，不静默降级。旧入口现状保持到独立行为变更获批。

| 方法组 | 请求重点 | 强类型结果/分支 | Android投影 |
|---|---|---|---|
| getState | 无业务参数 | SyncState | TL_updates_state |
| getDifference | pts/date/qts及现有限制参数 | oneof Empty/TooLong/Complete/Slice，分页水位、messages/updates/users/chats | 四种真实TL difference构造类 |
| getDialogs | offset消息/peer/date、limit、folder、hash等逐项登记 | DialogPage + count + slice标记 | dialogs/dialogsSlice |
| getPinnedDialogs/getPeerDialogs | folder或Peer列表 | PeerDialogs + state | messages_peerDialogs |
| getHistory/getMessages | Peer、offset/min/max/add_offset/limit；消息引用oneof只开通已支持id | MessagePage | messages_messages；不能虚构所有Telegram分页能力 |
| sendMessage | Peer、signed random_id、text、entities、reply、silent、send_as限制 | MessageSent，canonical message、random_id映射、pts、users/chats | updateMessageID + updateNewMessage + Updates |
| readHistory | Peer、max_id | AffectedMessages(pts,pts_count) | messages_affectedMessages |
| contacts search/resolve | username/GID；精确匹配语义 | FoundPeers/ResolvedPeer | contacts_found/resolvedPeer |
| getUsers/getContacts | UserRef列表/现有hash字段 | UserList/ContactList | Vector/contacts_contacts |
| importContacts | client_id、GID、名字；notes/flags约束 | ImportedContacts + users/retry/popular的显式类型 | contacts_importedContacts |
| createChat | title、UserRef列表及不支持字段策略 | GroupCreated + Updates | messages_invitedUsers，不是裸Updates |
| getPeerSettings/getFullUser | Peer/UserRef | PeerSettingsResult/UserFullResult | 现有包裹类及嵌套设置 |
| getFullChat/getChats | group ID/列表 | GroupFull/GroupList | messages_chatFull/chats |
| getNotifySettings | notify target的显式oneof | NotifySettings | peerNotifySettings默认值 |

领域结果至少定义 UserSummary、Contact、GroupSummary、Participant、Message（普通/服务oneof）、ReplyRef、Dialog、ReadReceipt、SyncState、Update（消息/ID映射/回执/GID oneof）及上述分页容器。不要把Java flags当业务真相；由显式TL Mapper根据presence/类型重新计算flags，并用真实TL序列化回读验证。原协议的空photo/media/status也应由Mapper生成，不要求业务Service返回Java类名。

### 5.5 版本、错误和安全

- 字段编号一经使用永不复用，删除后reserved编号和名称；100以上方法tag由唯一登记表冻结，错误保留独立区间。枚举0为UNSPECIFIED，不映射成功。
- 同major只做兼容增加；改变语义、oneof成员迁移、数值单位/身份含义需新major。unknown字段可被runtime保留，但**未知operation/必须理解的实体应拒绝**，不能接受后丢掉。
- 无payload、超范围、重复逻辑引用、无效UTF-16范围/URL、超长集合在边界校验。设置解析深度/大小、集合/文本上限和现有128KiB HTTP限额，不能因为二进制更小就取消DoS防护。
- HTTP401/403/409/413/415/429/501与业务错误code保留；避免200+error伪装。未鉴权/代理错误可能不是protobuf，客户端按status/content-type有限解析，禁止HTML当业务消息。
- 超时/断线不代表写失败；send重试保留random_id。mutation遇到5xx/网络错误不能自动退回JSON再写，除非明确同一幂等键且服务端共享去重并已验收。
- 相关ID为非敏感request_id；不记录token、密码、完整登录体、消息正文。日志区分协议、方法、结果、耗时、尺寸、重试和epoch，限制基数。
- 建议Java lite runtime与C# Google.Protobuf；现有后端零第三方运行依赖，新增包是需要批准/锁定的新依赖。protoc、Java/C# runtime选兼容固定版本，锁校验值、生成命令和CI差异检查；新生成代码不能配旧runtime。本轮没有选择未经测试的版本号。
- Android API23、D8/R8、方法数、APK体积、lite未知字段/oneof行为、现有Gradle兼容均需验证；新下载与缓存全部D盘。先使用HTTP能力，不为了格式切换额外引入gRPC网络栈。

## 6. C# 强类型业务重构（建议，未实施）

### 6.1 目标调用链

旧JSON HTTP → JsonTlRequestAdapter → **typed Command → 共享Service → typed Result** → JsonTlResponseAdapter。

新Protobuf HTTP → generated Request + 校验/Mapper → **同一Command/Service/Result** → generated Response。

REST → REST DTO Mapper → 同一Service（显式保留REST策略差异）。

禁止新链经过 JsonObject/JsonElement/Dictionary<string,object>/dynamic、$type字符串探测、JsonSerializer往返或旧Gateway.Execute。生成protobuf DTO只留传输层，不直接作为持久化/领域对象；禁止用Struct/Any/bytes包JSON逃避强类型。

### 6.2 最小服务拆分

| 服务 | 复用/迁移范围 | 必须保留 |
|---|---|---|
| IdentityService | Auth密码/摘要/session、Gids分配变更、公开用户 | 不泄露Salt/PasswordHash；GID更新与事件同事务；UUID/TL映射稳定 |
| ContactService | 联系人查询、导入、名字、互为联系人 | owner隔离、GID规范化、client_id、100项限制 |
| ConversationMessageService | direct/saved/group、历史、发送、读回执 | 成员授权、幂等、回复同会话、服务消息、分页顺序 |
| SyncService | typed事件分页、pts、差分、状态 | 收件人过滤、顺序、重放、水位、100页边界 |
| CallService（独立保留） | 已有REST信令 | 参与者授权/超时；不宣称原phone.*兼容 |

无需为每个RPC建立一个空Service类。原Gateway逐方法瘦身，每次先提取行为再让旧入口使用，随后新适配器接入。认证HTTP中间件仍处于适配层，不能把HttpContext传入领域。

### 6.3 事务与持久化

[Store.cs](file:///W:/Work/Botgram.Backend/Store.cs) 是核心约束：一次顶层业务操作只有一个Store.Write；锁内完成鉴权必要重查、成员校验、ID分配、去重、数据和事件写入，Flush/原子替换成功才返回成功。Service组合共享同一事务上下文，不各自嵌套Write，不持锁发HTTP/WSS，不把可变db引用带出锁。

现有看似读RPC也可能惰性创建direct/saved会话或Telegram ID。提取时先分类，不能一律换为Store.Read。只读接口的惰性分配消除应是显式设计与迁移，不是“性能清理”。写失败必须回滚ID计数、事件、联系人、消息，不能只回滚主表。

[Models.cs](file:///W:/Work/Botgram.Backend/Models.cs) 的 `ChatEvent.Payload:object` 和 `Message.TelegramEntities:JsonElement?` 是业务强类型化阻塞。建议使用明确的EventPayload层次/判别类型及MessageEntity领域类型；兼容旧快照的Json解析只允许在存储边界，一次读取即变为typed，不进入handler/Service。磁盘快照可继续JSON；退役JSON RPC不等于删除持久化JSON。

旧事件object重启后成为JsonElement，必须覆盖旧库、缺省字段、未知事件版本、回滚旧二进制。实体当前用GetRawText比较幂等，新结构等价比较不能改变历史重试结果；在迁移前建立固定样本和排序/规范化规则。不要用清库解决兼容问题。

### 6.4 REST与TL的行为差异不能机械合并

当前REST建群不发TL服务消息，TL建群发；title Trim行为不同；REST幂等主要比text，TL还比reply/silent/entities；REST read要求消息存在，TL归一到<=max_id的实际消息；REST history升序，TL降序；TL可自动建direct/saved。

共享Service应由显式typed选项或不同Command表达这些合法差异，适配层不复制业务。若决定统一语义，需要另列破坏性变更及客户端验收，不能在本轮“重构”中默默改变。

## 7. 分阶段执行、验收与回滚

复杂度仅为相对S/M/L，不是工期预测。每阶段需独立代码评审与授权发布；本轮未执行以下实施步骤。

| 阶段/复杂度 | 目标与主要改动边界 | 依赖/风险 | 退出验收 | 回滚 |
|---|---|---|---|---|
| P0 基线与阻塞收敛 / M | 固化清单/fixture；补TLS续期证据、会话epoch、重连/dirty行为测试；改动集中现有Transport/EventSocket/Controller测试边界 | 先取得运维/客户端修改授权；不能用服务端测试代替设备 | W1/W2受控时序不回灌，80挑战/证书持久化有证据，旧协议回归通过 | 保留现有协议/程序；安全修复单独评估，禁止为了回退恢复危险行为 |
| P1 业务提取 / L | 逐组从Gateway/REST提取typed Commands/Results与4组Service，旧入口立即改用它们；旧动态JSON仅适配层 | 事务、惰性读写、REST差异；不新增协议 | 原集成、权限、幂等、真实TL fixtures一致；磁盘失败回滚 | 未改变磁盘格式时回退程序；保持旧Gateway适配能力，不能维护第二套业务 |
| P2 Schema冻结与工具链 / M | 确定UserRef身份；冻结21方法tag、字段/结果/错误；固定protoc/runtime，D盘依赖 | 身份策略、API23/lite、生成可复现；本阶段不部署新流量 | 全部清单字段有映射/拒绝/明确忽略决定；Java↔C# golden bytes、旧/新版本兼容通过 | 撤销未使用协议入口/依赖，不影响现有JSON |
| P3 Protobuf服务端 / L | 加版本化HTTP适配，强类型校验/映射、错误和大小限制，共享P1 Service | 禁止转JSON；持久化兼容与运行依赖 | 同一输入/数据库快照的JSON与protobuf语义等价；malformed/unauthorized/cancel/重试通过 | 停止广告新能力并撤回新入口；旧客户端保持JSON；数据库格式必须可回退 |
| P4 Android显式Mapper / L | TLObject→generated request、typed response→真实TL；保留原UI/controller/storage；请求epoch贯穿 | flags、constructor、int32、entity、R8；绑定账号不回Telegram | standalone编译/签名检查、真实TL序列化回读、真机单聊/群聊/联系人/GID/前后台/多账号 | 选择上一已验证APK；本地新缓存格式若有变化必须先设计向后兼容；不删session |
| P5 双协议验证/受控灰度 / L | 明确能力协商；先只读比对，写入仅单执行后比较结果，不向两入口各发一次写 | 5xx降级重复写、旧客户端、取消和撤销竞争 | 所有已支持RPC逐项通过，>100差分页、重启、写失败、网络黑洞和权限覆盖；错误率/耗时无未解释回归 | 按会话确定协议，停止新流量；在途写不自动改协议重试；保留旧入口 |
| P6 WSS二进制（可选后续） / M | 独立EventHint协商，不承载第二套消息业务；游标语义保持 | 文本旧客户端、未知事件、大小/背压 | 断线/漏提示能靠差分完整恢复；不混淆pts | 保留原文本WSS/轮询，按连接协商回退 |
| P7 JSON RPC退役 / M | 确认可支持客户端已迁移，移除旧网络适配器而非Service/快照JSON | 仍有旧APK、无遥测、用户会话长期存在 | 观察证据与客户端支持政策批准、归档清单/fixtures、支持窗口结束 | 在约定窗口内保留可重新启用兼容版本；超过窗口另作版本策略 |

### 7.1 每阶段共同门槛

- 两仓库现有修改隔离审查，不提交秘密/生产库；仅用户要求才提交或部署。
- 不改变UUID、GID、TL IDs、会话、成员关系和历史消息关联。
- 新方法无权限即失败；不支持方法/功能明确501，不返回假成功空值。
- 每次实际交付更新真实哈希/版本/验证，不抄历史build-info。本轮未更新该文件。
- 发布后回滚前先停服务/监控并等租约释放，备份受限ACL数据/config；若已有不可逆快照变更，不允许仅换回旧DLL。不得在运行中并行打开正式库。

## 8. 测试证据、缺口与下一轮验收矩阵

### 8.1 本轮实际执行

环境：Node24.15.0，.NET SDK8.0.204，已有Java21.0.11。隔离测试使用D盘临时根/独立数据库及loopback动态端口；没有生产账号、没有新安装依赖。结果仅针对当前工作区。

| 项目 | 结果 |
|---|---|
| dotnet build --nologo --no-restore -p:NuGetAudit=false | PASS，0 warning/error |
| [tl-schema.mjs](file:///W:/Work/Botgram.Backend/tests/tl-schema.mjs) | PASS，56个Gateway响应类匹配Android源码；不是全部字段/行为/二进制验证 |
| [m49.mjs](file:///W:/Work/Botgram.Backend/tests/m49.mjs) | PASS，2026-11-01快照、29区域、249国家 |
| [forwarded-headers.mjs](file:///W:/Work/Botgram.Backend/tests/forwarded-headers.mjs) | PASS，3个代理信任场景 |
| [smoke.mjs](file:///W:/Work/Botgram.Backend/tests/smoke.mjs) | PASS，隔离HTTP/WSS基础回归；不是Android生命周期测试 |
| [gid.mjs](file:///W:/Work/Botgram.Backend/tests/gid.mjs) | PASS，隔离GID/身份/更新回归 |
| [contacts.mjs](file:///W:/Work/Botgram.Backend/tests/contacts.mjs) | PASS，26个TL fixtures生成；不等于本轮已用真实Android类逐个回读 |
| [BotgramServerAddressSmoke.java](file:///W:/Work/Botgram/Tools/tests/BotgramServerAddressSmoke.java) | PASS，独立D盘目录编译真实解析器，34断言 |
| [Verify-BotgramServerAddress.ps1](file:///W:/Work/Botgram/Tools/Verify-BotgramServerAddress.ps1) 固定目录 | 原执行class文件写失败，放宽sandbox重试仍失败；未改ACL/删旧文件，改独立目录后通过。固定目录问题未定位 |
| [default-port.mjs](file:///W:/Work/Botgram.Backend/tests/default-port.mjs) | BLOCKED，127.0.0.1:8521 EADDRINUSE；未停已有进程，不用动态端口成功冒充默认端口通过 |
| 公网只读HTTPS探针 | 见2.3；HTTP80异常，认证WSS未执行 |

未运行 [https.mjs](file:///W:/Work/Botgram.Backend/tests/https.mjs)，因为其默认公网目标并包含注册/发送等副作用。未执行客户端完整构建、真实Android TL宿主回读、安装或真机操作；不得使用前次记录替代本轮结果。已有auth-host使用Transport替身，不能证明真实EventSocket连接生命周期正确。

### 8.2 下一轮必须新增或扩展的验收

| 类别 | 关键用例 | 通过标准 |
|---|---|---|
| 协议互通 | 每RPC Java→C#请求/C#→Java响应，所有oneof/presence/unknown字段、int64边界 | 不经过JSON；与清单逐行对应，真实TL再序列化/解析 |
| 业务一致 | JSON与protobuf使用同一初始隔离快照；读结果及写后状态/事件/ID对比 | 单次执行；语义一致，差异有显式批准 |
| 权限 | 非成员会话/群/消息/回执、伪造Peer/GID、token撤销/过期 | 无数据正文/成员泄漏，错误明确，无状态变化 |
| 消息 | random_id并发/重复/变内容、reply同/跨会话、silent、15实体、emoji边界 | 无重复、冲突409、UTF16合法、重启重试一致 |
| 事务 | 注入写盘/替换失败，重启恢复，租约冲突 | 不返回成功、不留下半写、不消耗/重复错误ID、不破坏原库 |
| 差分 | 0/1/越界pts、101/201+事件、并发插入、GID连续改/读回执 | 页间无漏无重、水位不倒退、最终与历史一致 |
| 多账号 | 旧响应派发后退出、同槽位重登、切换server/token、队列尚未建job | 旧epoch不发送新凭据，不写新库/缓存/pts |
| WSS | 401、429、101即断、黑洞、分片/超大、断网/网络切换、后台/亮屏 | 有界退避/取消、无token降级泄漏，恢复靠差分补齐 |
| TLS运维 | API23设备证书、错误域名/自签、HTTP跳转、ACME staging/持久卷/告警 | 无trust-all；拒绝非法证书；续期证据闭环 |
| 资源与观测 | protobuf畸形/深层/大集合、慢客户端、并发与限流 | 4xx可控、内存有界、日志不含秘密 |
| 性能 | 相同数据/机器/次数下比较bytes、CPU、内存、p50/p95、APK体积 | 记录真实样本/基线再定预算，不在无测量时承诺百分比收益 |

## 9. 风险与阻塞清单

| ID | 优先级 | 当前状态 | 解除条件 |
|---|---|---|---|
| R01 HTTP80/ACME链路不一致 | 高/发布阻塞 | 本轮观测异常，根因未验证 | 运维只读证据+授权staging续期、证书持久化/告警 |
| R02 跨队列旧epoch回灌 | 高/迁移前置 | 代码推断，未复现 | 可控时序测试及端到端epoch提交屏障 |
| R03 身份模型与server_domain | 高/Schema冻结阻塞 | UUID/TL/GID现状确认，authority策略未批准 | 明确字符串user_id沿用策略及域名规范/生命周期 |
| R04 动态事件/实体穿入业务 | 高/强类型验收阻塞 | object/JsonElement代码确认 | typed领域事件/实体，旧快照读取限制存储层 |
| R05 Count型pts与清理/分页 | 高/数据迁移阻塞 | 实现确认，>100页专项未覆盖 | 固化游标不变量、完整分页/保留策略及迁移测试 |
| R06 TLS/API23与WSS真实设备 | 中高/客户端交付阻塞 | 未验证 | 真实设备/库故障矩阵通过 |
| R07 默认端口启动 | 中 | 本轮被已有监听阻塞 | 在授权可用环境验证默认配置，不能抢占正式端口 |
| R08 请求风暴/网络恢复 | 中 | 退避/无dirty/立即重试代码确认 | jitter、取消/dirty策略、故障注入结果 |
| R09 事务和旧快照回滚 | 高/发布阻塞 | 当前Store有原子机制；新方案未实现 | 磁盘失败、旧库、旧程序回退可验证 |
| R10 protobuf版本/供应链 | 中/工具链冻结阻塞 | 未选择/安装依赖 | 固定兼容版本与校验、D盘缓存、API23/R8验证 |
| R11 覆盖率误读 | 中 | 静态请求不等运行支持 | 分层清单+真实运行轨迹/逐RPC验收，不报虚假全覆盖 |
| R12 过早删JSON/自动降级写 | 高/退役阻塞 | 仅规划 | 支持窗口、客户端迁移证据、单次写与共享幂等验证 |

本轮未确认可直接利用的严重凭据泄漏漏洞；以上高风险包含可靠代码推断与发布证据缺口，不能混称已发生的安全事故。若下一轮发现可复现跨账号读写或令牌泄漏，应先报告证据和影响，再按授权修复，不直接改生产。

## 10. 官方设计依据与停止点

- [Protobuf proto3 指南](https://protobuf.dev/programming-guides/proto3/)：字段编号、oneof、保留编号、类型演进。
- [Field presence](https://protobuf.dev/programming-guides/field_presence/)：标量presence及optional。
- [跨版本运行时保证](https://protobuf.dev/support/cross-version-runtime-guarantee/)：生成器/runtime兼容约束，不保证任意混搭。
- [Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https)：证书自动管理的挑战可达/持久存储前提；文档不替代运行实例验证。

**停止点：本轮只交付审计与设计。下一轮首先确认身份策略、运维验证授权与P0/P1实施范围；在获得实施指令之前，不新增.proto/生成代码、不改旧JSON入口、不部署。**
