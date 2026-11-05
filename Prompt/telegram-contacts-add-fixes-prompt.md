# 修复 Nyarugram 联系人无法正常添加

## 目标与工作边界

用户实测：当前 Nyarugram 联系人仍无法正常添加。请定位并修复真实页面到 Protobuf 后端再到本地联系人列表的完整流程，不能仅证明手工构造的 addContact 请求返回成功。

客户端：`W:\Work\Botgram`。后端：`W:\Work\Botgram.Backend`。开始前阅读各自 `AGENTS.md`，以及客户端 `Botgram.Android.md`、`Botgram.GID.md`、`Botgram.Chinese.md`。以当前源码、部署元数据和实际手机日志为准。

产品使用 Protobuf-only。`LegacyOnly` 是 JSON 文件存储模式，不是 TL JSON 传输。必须验证 `LegacyOnly + Protobuf HTTP`，并保持 PostgreSQL typed RPC 路径一致；不要把任务变成补做 TL JSON 兼容。

保留现有工作区改动，包括局域网 HTTP+WS、后台来电、高优先级来电频道和未接来电通知。不要重置工作区、删除账号或聊天数据，不做无关重构。新增工具、依赖及缓存全部放在 D 盘。该任务不授权切换正式服务、迁移正式库或重启其他验证环境。

## 当前源码中已确认的事实

以下是排查起点，不是已经证实的完整根因；先检查当前代码是否仍然如此。

1. `ContactAddActivity.createView()` 的完成按钮会先直接修改共享 `User.first_name/last_name`、设置 `user.contact=true`、更新用户缓存，然后调用 `ContactsController.addContact(...)`；还会修改页面相关偏好与发出界面通知。需要核查后续关闭页面、备注状态和所有乐观修改的时序。
2. `ContactsController.addContact(user, note, exception)` 构造 `TL_contacts_addContact`；若 `note != null`，即设置 note 和 flags 的备注位。请求回调当前遇到 `error != null` 直接返回，没有向添加页反馈失败。
3. `BotgramProtobufRequestMapper` 的 addContact 分支拒绝 `note != null`、备注 flags，以及 `add_phone_privacy_exception=true`。ImportContacts 分支也拒绝备注相关字段。必须验证实际控件清空备注后返回的是 null 还是空对象，不能假设。
4. 添加页会传入 `noteField.getTextWithEntities()` 和 `needAddException && checkShare`。是否正是它们触发本机映射拒绝，需要真实日志/控件请求证明。
5. 后端 `ContactService.Add`、`ProtobufRpcMapper` 与 `PostgresBackend.Rpc` 已有 AddContact 实现，并在 Updates 中返回目标用户。现有 presence/contacts HTTP 测试覆盖直接构造 typed 请求的成功、重试、自加及参数校验，但不能代表真实页面可用。
6. `ContactsController.addContactToPhoneBook` 已对 Botgram 账号阻止写入系统通讯录，必须保留。

## 一、先复现并找到具体失败点

检查所有应用内添加入口，而不只检查一个按钮：

- 用户名或 Gram ID 搜索/解析后的资料页添加。
- 聊天页顶部“添加联系人”。
- 原联系人页中新建联系人/输入 Gram ID 的入口，追踪其实际使用 addContact 还是 importContacts。
- 编辑已有联系人时保存名称的行为，确保不会因修复新增联系人而损坏。

记录实际调用的方法、备注是否为空及 flags、共享例外布尔值、请求映射是否成功、HTTP 操作及错误码、响应和本地状态更新阶段。不要记录令牌、完整登录请求或其他真实联系人隐私；GID 等定位数据在交付报告中脱敏。

若 USB 手机仍已授权，可用只读 ADB 和真实操作定位；不要假定设备序列号固定。先检查当前是否正在通话，再考虑覆盖安装。测试使用隔离账号和数据，不在正式库创建测试联系人。后端没有收到 AddContact 时，优先定位客户端映射/调度，不能直接认定后端接口不存在。

交付时明确说明：复现入口、实际错误、根因和修复为何能够解决它。不要把排查假设写成已验证事实。

## 二、修复请求与页面能力的一致性

- 普通“只填写姓名，未填写备注”的添加应正常工作。空备注对象与完全未填写备注的语义要一致，只有确实没有内容/实体时才可归一化为无备注；不要把用户实际输入的备注静默丢弃。
- 对当前尚不支持的备注，选定并实现明确的一致行为：原 UI 中隐藏/禁用不可用输入，或在提交前明确提示。不要让页面允许输入、保存时却无声返回 501。该任务无需顺带实现完整联系人备注服务。
- 查清“共享号码/添加号码隐私例外”在 Botgram 中的语义。Gram ID 不是电话号码，不能机械沿用真实号码共享逻辑，也不能在 mapper 中悄悄丢弃用户明确勾选的选项。优先让绑定自建后端的原页面只提供已支持的选项，并确保实际请求一致；不擅自放宽后端隐私规则。
- 用真实 `TL_contacts_addContact` / `TL_contacts_importContacts` 对象和 Android 控件行为验证 flags，不通过放开所有未知 flags 掩盖问题。
- 输入 Gram ID 时保留三位区域前导零，正确处理 `+030 12345678` 与兼容 wire 的十一位数字。不要套用真实手机号国家码规则误改 Gram ID，不引入 SIM、通讯录或拨号器依赖。
- 用户 UUID、TL ID、已有聊天关联保持不变。按 GID 发现遵守已有 AddedByPhone 等隐私规则；按已知用户添加与按 GID 查找的权限不可混淆。

## 三、修复假成功、失败回滚与成功落库

- 给原页面提供明确的异步成功/失败结果，保留兼容的控制器调用入口，核对所有调用方。
- Botgram 路径收到后端确认前，不得把添加完成作为事实写入共享缓存、联系人列表或持久化状态。可延迟提交乐观变化；若确需乐观更新，必须完整回滚 User、联系人、名称、备注与相关偏好。不要只回滚一个 contact 标志。
- 请求进行中防重复提交；失败时恢复可操作状态、保留用户输入并用原 Telegram 弹窗/提示展示可理解错误。不得只打印日志或直接关闭页面。
- 成功后以响应用户及联系人状态为准，更新 MessagesController、ContactsController、MessagesStorage、分组/排序、资料页和聊天添加栏；同一账号的相关页面应及时刷新。
- 添加结果必须经 `getContacts`、应用冷启动/重新登录后继续存在，而不是只在当前内存显示。
- 重试/重复添加不得生成重复记录；名称别名只对添加者生效，不能修改对方账号资料。确认失败、非法参数和并发请求不产生部分写入。
- 页面销毁、退出、账号切换和迟到回调沿用已有 session scope 隔离。修复不得影响未绑定 Botgram 的原 Telegram 账号。
- 系统联系人读写继续禁用，不以申请 Android 通讯录权限解决应用内添加失败。

## 四、验证要求

补足当前测试未覆盖的真实故障，而不是只重复已有成功断言：

1. 实际 Android TL 请求映射：无备注、控件产生的空备注、非空备注、实体及相关 flags、共享例外；分别验证支持行为和明确拒绝行为。
2. 页面/控制器结果：后端错误、客户端映射错误、重复点击、关闭页面、退出/切号。确认没有假 contact=true、错误名称持久化或旧账号回调污染。
3. Protobuf-only HTTP：LegacyOnly 存储的添加、列表、名称别名、重复请求、重新启动后的持久化；PostgreSQL 路径做相应针对性回归。
4. 用户名资料页、聊天添加栏及 Gram ID 手动添加，分别验证；Gram ID 不存在、非法格式、自加、被隐私规则限制时反馈正确且不落库。
5. 原 Android TL 编解码及相关 Protobuf 映射回归，避免响应 users/contact/mutual 标志和身份映射丢失。
6. 真机至少验证一次：从原页面添加 -> 收到明确成功 -> 联系人列表和资料页刷新 -> 重启仍存在。再验证一次失败时无假成功。若无法执行，明确列出未验证项，不能用 HTTP 测试替代。

按改动范围选用现有 `Tools/Verify-BotgramProtobufMapping.ps1`、`Tools/Verify-BotgramTL.ps1`、后端 `Protocol.HttpTests/PresenceContacts.cs`、`tests/contacts.mjs` 等。不要把 TL JSON 测试通过作为 Protobuf-only 页面已修复的证据。

## 五、构建与交付

完成全部源码修改后构建 standalone Debug APK。新增文案维护英文资源和 `Tools/Import-BotgramChinese.ps1` 字典，再生成中文；复用 Telegram 原组件，不新建独立联系人 Activity。

交付复制到 `artifacts/android/Nyarugram-debug.apk`，核验签名、包名、版本、架构及新增资源，核对构建产物与交付副本 SHA-256，并在 `artifacts/build-info.json` 写入实际验证结果。不因普通修复自行改版本号；相同版本号不代表相同 APK，真机验证需明确安装的具体产物。

若后端源码确有必要修改，按后端指引构建、执行针对性回归并同步交付物及真实哈希；不要自行切换运行中的正式服务。若修复纯客户端，则不要无故重建或改写后端发布包。

最终报告只列实际根因、修改、执行过的验证、交付路径/哈希，以及尚未验证的边界。目标是用户可以在原联系人页面可靠添加并持久保存联系人，而不是接口表里多一项 PASS。
