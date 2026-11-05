# 修复特别关心状态恢复与来电通知闪退

请直接检查并修复下列问题，完成相关回归、Android 编译与 APK 交付，不只给出方案。客户端：`W:\Work\Botgram`；后端：`W:\Work\Botgram.Backend`。开始前阅读各仓库 `AGENTS.md` 和客户端 `Botgram.Android.md`、`Botgram.GID.md`、`Botgram.Chinese.md`。涉及运行服务时再阅读后端 `DEPLOYMENT.md`。

保留当前用户及其他任务的修改，不重置工作区、不做无关重构。新工具、依赖和缓存全部放在 D 盘。不修改包名、签名或恢复旧聊天页入口。

## 当前环境与不可改变的产品约定

- 手机测试后端已升级，固定地址为 `http://192.168.2.200:18524`，部署根目录 `D:\BotgramBuild\native-calls-test`。旧 18521 已停用；正式 8521 不属于本任务范围。实际部署版本、进程与路径读取 deployment.json/process.json，不硬编码历史 PID。
- 当前测试后端为 Protobuf-only RPC/事件，注册登录等 REST 仍使用 JSON。不得为解决问题启用 TL JSON、自动回退或重建账号。
- 特别关心一对一来电默认频道重要性为 4；只有系统允许时才可全屏。普通、身份未知及群来电默认 3，不在后台自动铺满屏幕。用户点击通知或接听后正常打开原通话页面。
- 不能通过给所有来电补全屏意图、提高普通来电重要性、重建频道或绕过系统权限来消除闪退。尊重用户已调整的频道重要性、声音、震动与免打扰设置。
- 特别关心只保留联系人资料页菜单及联系人管理列表入口；聊天页展开菜单入口已经移除，不重新加入。更新报告时按实际最终行为表述。
- 本任务授权修改源码和生成交付物，不授权再次切换、重启、迁移测试或正式后端，不安装 APK、不自动拨打电话。可只读查看当前运行元数据；隔离测试使用临时数据。

## 一、修复普通来电的 CallStyle 通知崩溃

用户反馈：来电人不是接听者的特别关心时，接听者闪退。当前源码有如下与现象高度吻合的缺陷；没有采集到本次手机崩溃堆栈，不能把源码分析写成已完成真机复现。

- `MessagesController` 在 Android 13+ 进入 `VoIPPreNotificationService.show()`，此阶段尚未启动真正的 VoIP 前台服务。
- `IncomingCallPolicy.Decision.claimFullScreen()` 对普通 3 级来电返回 false。
- `VoIPPreNotificationService.makeNotification()` 仍无条件使用 `Notification.CallStyle.forIncomingCall(...)`。
- receivedCall 成功后的 UI 异步回调直接调用 `NotificationManager.notify()`，没有对应的异常处理。最初调用 show() 外部的 try/catch 无法捕获这个稍后执行的回调异常。
- Android NotificationManagerService 对既不属于 foreground service/user initiated job、又没有 full-screen intent 或系统认可的 FSI 请求状态的 CallStyle 通知抛 `IllegalArgumentException`：`CallStyle notifications must be for a foreground service or user initiated job or use a fullScreenIntent.`

参考平台检查：
https://android.googlesource.com/platform/frameworks/base/+/9f63d6746f1f/services/core/java/com/android/server/notification/NotificationManagerService.java

修复要求：

1. 通知样式按发布时的真实条件选择：普通来电、特别关心但全屏权限不足、没有取得本次 FSI claim 的重投/更新等，在未绑定真实前台服务时使用可合法发布的普通模板，保留来电人、语音/视频区分、接听、拒接和点击打开能力。可以复用项目已有通知布局/组件。
2. 特别关心且本次通知实际带合法全屏意图时可使用 CallStyle；真实 VoIP 前台服务通过 startForeground 发布的通知也可使用合法 CallStyle。根据最终选定方案验证实际发布方式，不用伪造 `FLAG_FOREGROUND_SERVICE`、UIDT 标记或仅启动一个无关服务欺骗校验。
3. 普通 3 级来电不加任何隐藏的全屏意图，也不从其他后台 startActivity、Telecom 或服务接管路径抢屏。保留主动点击接听/拒接和原通话媒体链路。
4. 同时修复 `VoIPGroupNotification`：它也使用 CallStyle 后直接 nm.notify，且群来电按约定没有全屏意图，存在同类风险。群来电保持普通 3 级，不因邀请者特别关心提升。
5. 审计所有使用 CallStyle 的原始/更新/重复发布路径，包括特别关心的 FSI 权限关闭、系统撤销权限、通知已更新但单次 FSI claim 已消耗、pre-notification 到 service 接管和重启恢复。不要只修复第一次普通语音来电。
6. 单次 full-screen claim 的结果与实际创建的通知保持一致；不要为了判断模板提前消费 claim，再在设置意图时调用第二次而丢失全屏能力。通知构造、发布失败时也不能使后续必要提醒无条件消失或重复抢屏。
7. 在真正执行 notify/startForeground 的异步边界处理可预期的通知权限、模板或系统拒绝异常；记录不含身份/凭据的阶段和异常类型，并给出合法降级或清理。不能仅吞异常后继续把未发布通知当成功、无限响铃或留下悬挂的来电状态；不要宽泛捕获后伪造已接听、拒接或服务端成功。
8. 不更改普通/特别关心频道的 3/4 级默认值，不删除重建已有频道；不影响正在通话的 foreground notification、媒体、未接通知及其去重。

## 二、修复删除/屏蔽失败后特别关心被永久抑制

当前已用真实 `BotgramSpecialCareState` 宿主类复现：

1. 服务端快照包含联系人 B，B 为特别关心。
2. `ContactsController.deleteContact` 在请求发出前调用 `BotgramSpecialCare.invalidate(B)`；`MessagesController.blockPeer` 也有类似调用。
3. invalidate 立即 suppress、持久化并刷新。
4. 删除或屏蔽请求失败；现有失败回调没有回滚抑制。
5. 后续权威快照仍包含 B，即便 revision 更高，`apply()` 只移除“不在快照中的 ID”的 suppression，因此 B 仍不是特别关心；该错误可持久化至重启，影响消息增强提醒和 4 级来电。

相关文件：`ContactsController.java`、`MessagesController.java`、`BotgramSpecialCare.java`、`BotgramSpecialCareState.java`。

修复要求：

- 将临时抑制与具体删除/屏蔽操作、目标、账号及认证 generation 绑定。服务器确认成功后才完成永久清理；失败需撤销该次操作的临时抑制，并恢复/同步后端真实状态。不要让旧刷新结果在操作未决时重新升级提醒。
- 同一联系人多个操作并发、不同联系人并发、删除与屏蔽交错、重新启用、撤销删除和页面销毁均保持一致。旧操作失败不能解除另一次仍有效的抑制；旧成功或旧回包也不能覆盖新会话。
- 区分临时未决状态与服务端已确认的清理。设计明确的进程重启恢复与失败重试策略，避免网络中断后永久压制，也避免确认删除/屏蔽后自动恢复特别关心。
- 核对本地 `user.contact`、blocked peers 与服务端状态的一致性：如果仅解除 suppression 但原乐观删除/屏蔽状态仍错误，菜单资格或消息提醒依然可能异常。只修复相关流程必要的回滚和同步，不扩大为全局重写。
- 不在失败回调中机械重新启用特别关心；恢复应服从服务器最新事实和当前操作顺序。

最小回归必须包含：已启用 B → 删除/屏蔽请求失败 → 后端仍含 B → 本地重新显示特别关心并正确路由提醒。成功删除/屏蔽后重新添加/解除屏蔽则不能自动恢复。

## 三、修复迟到磁盘缓存恢复旧 suppression

当前 `BotgramSpecialCare.bind()` 的磁盘恢复回调先执行 `restoreSuppressed(generation, ...)`，再执行 `state.apply(..., disk=true)` 校验磁盘快照是否仍可应用。

已用真实状态类复现：服务端 revision 7 的启用状态已应用；迟到磁盘携带 revision 5 的 suppression；磁盘 apply 返回 false，但 suppression 已写入，刚确认的特别关心失效。

修复要求：

- 在同一个受同步保护的状态操作中，先校验 owner/generation、磁盘恢复资格与版本，再一起恢复 users、revision、suppression。拒绝的磁盘快照不得产生任何副作用。
- 缓存读取发起后若发生本地操作，迟到缓存不能覆盖新临时抑制、操作结果或显式重新启用；不能仅移动两行顺序而忽略并发与本地操作时序。
- 新服务端状态、退出、切号或同槽位重新认证优先于旧磁盘结果。旧服务端回包同样不能覆盖新 revision。
- 持久化状态保持账号、服务器、稳定身份及认证会话隔离，I/O 留在后台。不要删除所有缓存/账号来掩盖时序问题。

## 四、补齐验证缺口

已有 26 项状态、81 项消息策略和 50 项来电策略宿主测试没有捕获上述问题。不要只重跑常量断言后宣称修复完成。

- 状态测试使用生产状态实现，覆盖失败回滚、成功清理、重叠操作、重启恢复、旧查询/旧磁盘/旧认证回调。明确断言拒绝磁盘快照后所有状态均未改变。
- 来电测试覆盖普通/特别关心/群、语音/视频、FSI 允许/禁止、首次/重复/服务接管、前后台、真实前台服务与普通 notify 的组合。验证实际创建的 notification style、channel、fullScreenIntent 及接听/拒接 PendingIntent，而非只测试 importance 常量。
- 对照 Android 的 CallStyle 发布约束检查所有 notify/startForeground 调用点。宿主替身不能伪装成系统已接受通知；不能用假的 FGS flag 让测试通过。
- 核对异步异常处理和权限拒绝后的降级/清理，保留未接通知、普通消息及特别关心声音/震动行为。
- 修改控制器后编译当前交付变体；按范围执行原 TL/Protobuf 映射、认证隔离、来电和未接通知相关回归。后端未修改则无需无关的全量后端回归。
- 本任务默认不安装、不拨号。若没有另行授权和可用设备，明确记录真机 NOT RUN；不能声称已采集此次崩溃日志或验证 ROM 全屏行为。若用户另外提供日志，核对实际堆栈是否命中本条路径；不同则继续定位，不能强套现有假设。

## 五、文档、构建与交付

- 修正文档/报告中的入口描述：当前特别关心入口是资料页及管理列表，聊天页展开菜单无此入口。历史记录保留时间与范围，避免将旧结论写成当前状态。
- 在所有源码修改结束后构建 `:TMessagesProj_AppStandalone:assembleAfatDebug`，将最终产物复制到 `artifacts/android/Nyarugram-debug.apk`。
- 用 D 盘 Android 工具核验签名、包名、实际版本、架构、必要资源和交付哈希；更新 `artifacts/build-info.json` 及本轮交付报告，只记录实际执行结果。不更换包名/签名；本任务未要求升版，沿用现有版本策略。
- 若新增用户文案，同步英文与中文导入字典后生成中文，不只改生成文件。
- 报告分别说明三个问题的根因、修改如何解决、实际验证证据及未验证项；区分“源码可确认的缺陷”“宿主复现”和“真机崩溃堆栈确认”。
- 不重新部署后端、不改测试端口18524、不重置数据库、不关闭系统通知限制；交付新版 APK 供后续真机验收。
