# 隐私说明

最后一次按源码核对：2026-08-24

本文说明 FlareDo 源码及其未经修改的构建版本如何处理数据。分支项目、修改版安装包、
应用商店、系统浏览器或操作系统服务可能有各自的行为和政策。FlareDo 是非官方
Linux.do 客户端；本项目维护者不运营 Linux.do 及其网络基础设施。

## 摘要

- FlareDo 不包含广告、分析、追踪、崩溃上报或遥测 SDK，也没有由维护者运营的运行时
  后端，不会自动上传诊断信息。
- 论坛 API 流量直接发送到 `https://linux.do`。前台实时轮询也可能使用 Linux.do
  自身声明的 HTTPS MessageBus 地址。
- 帖子可以引用任意 HTTPS 站点上的图片。显示图片会连接该主机，并暴露常规网络元数据，
  包括请求 IP、时间和图片 URL。
- 会话 Cookie 通过各平台的安全凭据库保存。Room 只保存不透明的 vault 引用和公开账号
  元数据；CSRF 令牌只驻留在进程内存中。
- 公开论坛缓存和按账号隔离的草稿是本地 Room 数据，FlareDo 不对该数据库实施应用层
  加密。草稿会有意跨越退出登录和认证过期继续保留。
- 退出登录会在能够安全清除本地安全存储时删除当前这一个应用会话；它不会删除草稿、
  公开缓存、服务器上的帖子或 Linux.do 账号。

## 通过网络发送的数据

### Linux.do 论坛流量

FlareDo 通过 HTTPS 调用 Linux.do 的 Discourse 接口。Linux.do 及为其提供服务的基础
设施可以获得每次请求固有的信息，例如 IP 地址、请求时间、TLS/HTTP 元数据和所选页面。
根据用户操作，请求还可能包含：

- 主题、分类、标签、个人资料、通知或搜索标识；
- 搜索词；
- 已登录账号的会话 Cookie；
- 新主题、回复、编辑、书签、reaction、点赞或已读状态操作；
- 用户明确选择上传的文件，包括文件内容、文件名和媒体类型。

FlareDo 不会通过项目自有服务器转发这些流量。在 Linux.do 发布或保存的数据由
Linux.do 控制，并适用 Linux.do 自身的条款和隐私实践。

### 登录认证

主登录路径会在系统浏览器中打开固定的 Linux.do User API Key 页面。该 URL 包含一次性
公钥以及随机 client/nonce 值，不包含 RSA 私钥。浏览器历史、同步和诊断行为由所选
浏览器及其提供方控制。

备用登录和需要用户处理的 Cloudflare challenge 使用受限的内嵌浏览器。当前实现只允许
无显式端口的 `https://linux.do` 作为顶层源，不提供原生 JavaScript bridge，并在平台
能力允许时阻止混合内容及不安全跳转；其中 Cookie 仅作为一次性交接数据。用户在网页中
输入的凭据由 Linux.do 页面处理。Linux.do 和 Cloudflare 仍会收到加载该网页所需的常规
Web 流量。

Android 还提供原生账号、密码和 TOTP 输入框。短生命周期的受限 WebView 会渲染 hCaptcha，
并提交 Linux.do 同源会话请求；密码不会进入日志或 Room。记住密码必须由用户明确选择：账号与
密码 envelope 存入选定的平台 vault，Room 只保存公开账号和不透明 vault 引用。清除已保存登录
会移除该引用，并尝试删除 vault 值。平台 vault 不可用时，已保存登录 store 绝不会回退到明文。

跨设备二维码登录会编码一个有效期十分钟的 bearer capability，其中包含临时 User API Key、
一次性密码、账号名称和过期时间。任何能读取尚未过期且未消费二维码的人，都可能登录该账号。
FlareDo 不会持久化或记录二维码值。关闭或重新生成展示中的二维码会尝试立即远程撤销；扫描端会
交换 OTP，并在身份查询前撤销临时 key。撤销需要网络连接，因此用户应对二维码保密，并在离开前
等待操作确认。

Android 把实时扫描委托给 Google Code Scanner，应用本身不申请 `CAMERA` 权限。iOS 与 macOS
在常规系统相机权限提示后使用 AVFoundation。Apple 与桌面端的图片导入由用户主动选择，二维码
在应用本地解码；FlareDo 不会上传所选图片。

### 前台实时轮询

实时更新仅在应用处于前台时运行。默认使用 Linux.do 同源 MessageBus。如果 Linux.do
自己的启动 HTML 声明了独立 `long_polling_base_url`，FlareDo 可能连接该经过校验的
HTTPS DNS 源：

- 访客轮询不发送 Cookie 或认证请求头；
- 已登录轮询通过独立的无 Cookie 客户端，发送由 Linux.do 提供的临时
  `X-Shared-Session-Key`；
- 该 key 不写入 Room，并在当前 session generation 的轮询结束时清除其字符缓冲区。

轮询主机会获得常规网络元数据以及订阅频道和游标。MessageBus payload 只被当作刷新
信号；FlareDo 会重新从 Linux.do 拉取权威数据，而不会把这些 payload 直接作为论坛
内容持久化。

### 远程图片和链接

经过安全解析的论坛内容可以包含绝对或相对 HTTPS 图片 URL。相关内容渲染时会加载图片。
如果作者嵌入第三方主机的图片，该主机至少可以看到请求 IP、时间和 URL；平台图片加载器
也可能附加常规请求头，并使用自己的内存或磁盘缓存。FlareDo 不会有意把 Linux.do API
Cookie jar 交给富文本图片加载器，但第三方主机仍可能通过唯一 URL 等 Web 技术进行关联。

富文本仅接受 HTTPS URL；`http:`、`javascript:`、`data:`、协议相对 URL、嵌入式
用户名密码、控制字符和危险活动标记都会被拒绝。这能阻止活动内容执行，但不能使第三方
图片主机变得私密或可信。普通链接只在用户操作后打开，之后适用接收应用或浏览器的政策。

## 设备上的本地数据

### Room 数据库

各正式平台把 Room 数据库放在应用自有目录或容器中。FlareDo 不对该数据库实施应用层
加密，因此平台沙箱以及设备/全盘加密仍属于保护边界。

数据库可能包含：

| 数据 | 内容及保留方式 |
| --- | --- |
| 公开论坛缓存 | 已经安全解析的最新/热门列表页、分类、标签和公开主题，使用 `anonymous` 分区，默认最多 32 条。已登录响应不会写入该公开缓存。数据跨进程重启和退出登录保留，直到按容量淘汰、代码显式清理或应用数据被移除。 |
| 编辑器草稿 | 账号 ID、目标 ID、标题、正文原文、标签、revision 和更新时间；默认每个账号最多 32 条。草稿跨退出登录和认证过期保留，仅在用户显式丢弃、服务器确认发布后按 revision 删除、容量淘汰或应用数据被移除时删除。它不是离线发送队列，也不保存上传文件内容。 |
| MessageBus 游标 | 持久化内容仅限某账号的通知频道、账号 ID 和最后一个数字 message ID；列表、主题和 reaction 游标只在进程内。事件 payload 和 shared-session key 不会持久化。最小通知游标可能在退出后继续保留。 |
| Vault 引用元数据 | 不透明的凭据引用、slot 名称、公开账号 ID/用户名和时间戳。Room 禁止保存 Cookie、CSRF、RSA 私钥内容、User API Key、OTP 或加密 vault envelope 本身。 |

### 认证材料

- 完整且经过校验的 Linux.do Cookie snapshot 会被序列化成平台 vault 中的一个值；
  Room 只保留不透明定位符和公开展示元数据。
- CSRF 令牌有大小上限并且只在内存中保存；登录替换或退出会清除它。
- 待完成认证的 nonce/client 元数据和 RSA 私钥分别存入 vault，以便应用经历生命周期变化
  后仍能处理回调。回调有效期最长十分钟，并在 RSA 认证的 nonce 匹配后单次消费。取消、
  替换或处理时会删除相应数据。若用户放弃流程，已经过期的加密值可能要到后续取消、替换
  或过期处理时才被清理。
- 临时 API Key 和 OTP 的字节数组会在完成或失败时尽力覆写；不可变字符串以及加密提供方
  或运行时产生的副本无法保证立即从进程内存擦除。
- Android 记住的登录信息是独立、有界的 vault envelope，包含账号与密码；它不属于活动会话的
  Cookie envelope，并可单独清除。
- 展示中的二维码值会作为可读取的 bearer capability 保留在 UI 内存，直到关闭、重新生成、
  消费、过期或其 Presenter teardown。

### 各平台 vault

| 平台 | 持久会话保护方式 |
| --- | --- |
| Android | 使用不可导出的 Android Keystore AES-GCM 密钥保护应用私有 no-backup 目录内的有界密文文件，并把不透明记录引用作为关联数据参与认证。 |
| iOS 和 macOS | 使用 Generic Password Keychain，访问级别为 `WhenUnlockedThisDeviceOnly`，且未标记 synchronizable；FlareDo 不请求通过 iCloud Keychain 迁移这些会话材料。 |
| Windows | CurrentUser DPAPI 保护用户 FlareDo 应用数据目录中的有界密文文件，并把不透明引用作为 optional entropy。 |
| Linux | 通过 `secret-tool` 使用 Secret Service，secret 从标准输入传递而不是进入命令参数。如果没有 `secret-tool` 或无法访问已解锁的 Secret Service，则只允许 session-only 登录：凭据只存在于有界进程内存，绝不回退到明文文件。进程重启后，Room 中旧的进程专属引用无法解析。 |

如果桌面端无法初始化所选安全 vault，host 会使用同样的进程内 fail-closed store，而不是
把凭据明文持久化。

### 日志和诊断

FlareDo 的应用诊断设施是有界的进程内存 ring。信息在保存前会脱敏 Cookie/Set-Cookie、
Authorization 请求头、已知 token/nonce/OTP 字段、URL userinfo、Bearer 值和邮件地址。
Ktor logging plugin 被明确禁用。类型化网络错误只保留固定类别和少量标量，不保留响应
正文或任意服务器消息。

Android 回调 Activity 可以把封闭枚举形式的事件及冷/热启动枚举写入本地 Android 系统
日志，但绝不会拼接 Intent、回调 URI、query、密文、异常或 Cookie。诊断信息不会自动
上传。如果用户主动把截图、日志或数据库附到 issue，所共享的数据将适用报告所用服务的
政策。

## 退出、删除和保留

退出登录会先尝试调用 Linux.do 远程退出；由于设备可能离线，远程失效只能尽力完成。
随后 FlareDo 在不可取消的本地清理段中尝试删除当前 vault 值及其 Room 引用、内存中的
Cookie/CSRF，以及应用自有受限浏览器的 Linux.do 状态。每个破坏性步骤都由捕获的
session generation 和 account owner 保护，延迟到达的退出不能删除替换后的新登录。

如果本地 vault/引用删除失败，FlareDo 不会发布一个重启后又可能恢复登录的虚假 Guest
状态；它会保留可见 owner，并报告可恢复错误，让用户重试退出。

退出登录有意不删除：

- 按账号隔离的草稿；
- 匿名公开论坛缓存；
- 最小化的持久通知游标；
- Room 缓存之外、由图片加载器或系统浏览器控制的缓存；
- Linux.do 已经持有的帖子、上传、书签、reaction、通知或账号数据。

未完成正文应通过“丢弃草稿”显式删除。清除应用数据或卸载应用是广义的本地数据移除方式，
但仍受平台自身 Keychain、备份和卸载行为约束。不要把卸载当作服务器会话撤销机制；设备
丢失或退出无法完成时，请使用 Linux.do 的账号安全功能撤销会话。

## 用户可做的选择

- 访客浏览不会保存已登录 Web 会话，但 Linux.do 和远程图片主机仍会收到常规网络请求。
- 如果不能接受连接作者选择的图片主机，请不要打开含远程图片的主题。
- 上传前检查文件；只有明确执行上传操作后，FlareDo 才把所选文件内容发送给 Linux.do。
- 退出登录以清除应用自有会话；设备不再可信时，还应在 Linux.do 侧检查并撤销服务器会话。
- FlareDo 采用 AGPL-3.0，任何人都可以用公开源码核验实现和本文声明。

## 变更和问题

改变隐私相关行为的代码变更必须同步更新本文。安全漏洞请按
[SECURITY.zh-CN.md](../../SECURITY.zh-CN.md) 私下报告，不要在公开 issue 中发布敏感细节。
一般隐私问题可以在项目 GitHub issue tracker 中提出，但请勿附带凭据、Cookie、私密帖子
或个人日志。
