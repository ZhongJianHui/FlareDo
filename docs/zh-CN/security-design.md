# 安全设计

最后一次按源码核对：2026-08-27

本文记录 FlareDo 的安全边界和已实现控制。它是一份工程设计说明，不代表形式化验证或
独立安全审计。当前版本只支持一个活动中的 Linux.do 账号，每个已认证操作都绑定到该
账号及其 session generation。

## 安全目标

FlareDo 的设计目标包括：

- 不把 Linux.do 会话 Cookie、一次性认证私钥、OTP 和 CSRF 以明文写入应用持久化或
  日志；
- 防止过期请求、回调、浏览器交接、退出操作或实时事件作用于替换后的账号；
- 把已登录 REST Cookie 限制在固定 Linux.do HTTPS 源；
- 把 callback Intent、服务器 JSON/HTML、响应头、MessageBus frame、浏览器 Cookie、
  上传响应和本地 vault 引用都视作不可信输入；
- 在不执行活动内容、也不提供 raw HTML fallback 的前提下渲染论坛 `cooked` HTML；
- 平台 vault 或受限浏览器交接不可用时 fail closed；
- 保留调用方 cancellation 语义，同时完成安全关键的本地清理。

主要保护资产包括 bearer session Cookie、待处理 RSA 私钥、临时 User API Key/OTP、
临时 MessageBus shared-session key、用户草稿以及按账号操作的完整性。

## 信任边界

FlareDo 依赖：

- 已安装操作系统、应用沙箱、随机源、TLS stack 和平台凭据服务；
- Linux.do 及为其提供服务的 Discourse/Cloudflare 基础设施；
- 主授权页面所使用的系统浏览器；
- Ktor、Room、KSoup、Compose、SwiftUI/WebKit、Coil 和原生密码学提供方等依赖的正确
  行为。

来自这些边界的数据仍会在进入共享状态前校验。已 root/jailbreak 或以其他方式失陷的
设备、恶意修改的构建、失陷的可信服务器，超出应用层检查能够提供的保护范围。

## 固定源传输与 Cookie 隔离

普通 REST 流量编译时固定为 `https://linux.do`。最终 Ktor request plugin 会拒绝 scheme、
host、有效端口或 userinfo 逃逸该源的请求；显式写出的默认 HTTPS 端口仍属于同源。自动重定向
被关闭；Android 还禁用了应用明文流量。

Cookie jar 比 Ktor 通用 Cookie 存储更严格：

- 外部源读取不到 Cookie，外部源写入会被拒绝；
- Cookie 被规范化到 `linux.do`、HTTPS 和安全绝对路径；
- 数量、单字段和总大小都有上限（默认最多 64 个、合计 64 KiB）；
- 手工加入的 `Cookie` header 会被移除；
- 没有 session lease 的请求不发送应用管理的 Cookie，也不能提交 `Set-Cookie`；
- 每个请求携带随 session generation 一起捕获的 cookie revision。旧 revision 的延迟响应
  不能修改新账号 Cookie jar。

传输使用平台 trust store 和标准 HTTPS 证书校验。FlareDo 没有实现 certificate pinning；
这避免 pin 轮换导致不可用，但意味着操作系统 CA 信任边界仍在范围内。

### 可选 MessageBus 源

Linux.do 可以在自己的有界启动 HTML 中声明独立 HTTPS `long_polling_base_url`。该值必须
是默认 HTTPS 端口上的小写 DNS host，不能含 userinfo、path、query、fragment、IP literal，
也不能把 `linux.do` 伪装成交叉源。交叉源轮询使用无 Cookie、无重定向的独立客户端。
访客轮询无凭据；已登录轮询只增加经过校验的临时 `X-Shared-Session-Key`。该 key 不会
持久化、在诊断中始终脱敏，并在当前 generation 的轮询结束后清除其自有字符缓冲区。

## 认证设计

### 主 User API Key 流程

1. 应用通过平台生成至少 2048 bit 的 RSA key pair，并分别生成 32 byte 随机 attempt ID、
   client ID 和 nonce。
2. PKCS#8 私钥进入平台 vault；Room 只保存不透明引用和时间戳。授权 URL 只接收 SPKI
   公钥、client ID、nonce、固定 `one_time_password` scope 和
   `discourse://auth_redirect`。
3. 回调只在十分钟 attempt 窗口内接受。URI 必须满足精确 scheme/authority/query 语法，
   canonical Base64 字段有大小上限，且不能含 path、port、userinfo、fragment、重复或
   未知 query 名称。
4. payload 使用本 attempt 的 RSA/PKCS#1 私钥解密，nonce 以 constant-time 方式比较。
   随机或无法解密的回调不会消费合法 pending attempt。
5. RSA 认证的 nonce 匹配后，会在返回 API Key 和 OTP 前原子消费 attempt；重放因此得到
   stale。私钥 vault 值在不可取消的 best-effort 清理中删除。
6. OTP exchange 获取新的根 `_t` Web 会话 Cookie。临时 User API Key 不持久化，且在
   查询身份或激活本地 session 前，下一个网络请求就是 `/user-api-key/revoke`。
7. 只有 `/session/current.json` 返回的权威身份及有效根 `_t` snapshot 才能进入加密会话
   持久化。exchange 失败或取消会清除未完成的 guest jar 并推进 generation。

RSA PKCS#1 v1.5 是 Discourse User API Key 协议要求。shared layer 不自行实现 RSA；
Android、Apple 和 JVM 平台 provider 负责密钥生成及解密。

临时 secret 字节数组会防御性复制，并在 owner 完成后覆写。这是降低暴露面的措施，不是
绝对擦除保证：Kotlin String、GC heap 副本、原生 provider 和 crash dump 都可能保留应用
无法控制的副本。

### 受限浏览器备用流程

备用浏览器的信任面有意小于通用浏览器：

- 顶层导航只允许无显式端口的 `https://linux.do`；
- 不提供原生 JavaScript bridge、下载路径、任意 callback URL 或 file/content access；
- 在平台支持范围内禁用第三方 Cookie，或把它们限制在临时 profile；
- Android 关闭 WebView 调试、file/content access、mixed content、geolocation、自动窗口
  和 cache，并在 TLS 错误时取消；
- Apple 使用 non-persistent WKWebsiteDataStore，其中 Cookie、cache、local storage、
  service worker 和 IndexedDB 不会跨该 view 生命周期保留；
- Windows/Linux 使用新的 incognito native WebView profile，并关闭 devtools 和 clipboard。

应用会在浏览器之外再次过滤并限制 Linux.do Cookie。备用登录先用隔离客户端验证这些
Cookie，再通过一次 CAS 激活，随后清除平台浏览器状态。一次性 actor receipt 会区分
“命令进入队列”和“presenter 已接管 Cookie handoff”，避免 Activity/dialog dispose 或
prompt coroutine cancellation 在所有权转移后误删 Cookie。

受限浏览器仍必须执行 Linux.do/Cloudflare 所需 JavaScript 和 subresource。它不能作为
对抗已失陷 Linux.do 源或浏览器引擎的沙箱。

#### Android mini 密码登录

Android 备用密码登录把凭据保留在原生 Compose 输入框中，只使用一个短生命周期的受限
WebView 完成人机验证和同源请求。WebView 首先加载固定的
`https://linux.do/session/csrf` bootstrap URL。如果 Cloudflare 展示验证页，用户在同一个
WebView 内完成验证；只有确认验证页消失后，应用才通过 `loadDataWithBaseURL` 注入小型页面，
并保持 `https://linux.do` 文档源。

注入页面显式渲染 hCaptcha，并在 WebView 网络栈内完成完整顺序：`GET /session/csrf`、
`POST /captcha/hcaptcha/create.json`（失败时有界回退到 `/hcaptcha/create.json`），再
`POST /session.json`。第三方 Cookie 只在这个临时 profile 中启用，并随受限浏览器状态一同
清除。JavaScript bridge 使用每次请求随机 nonce、阶段 allowlist、有界响应正文、有界
captcha token，并拒绝控制字符；服务端原始响应不会进入共享状态或日志。

收到成功响应后，应用等待非空根 `_t` Cookie，再沿用现有隔离 session probe 和 actor 所有权
确认后的 `CompleteRestrictedBrowser` handoff。TOTP 重试复用同一个 WebView，不会再次发送
hCaptcha token；一次 CSRF challenge 重试只有在重新完成浏览器 bootstrap 后，才会复用尚未消费
的 token。

### Cloudflare challenge

仅凭 403 或 429 不会打开浏览器。FlareDo 要求官方 `cf-mitigated: challenge` 信号，或
有界响应片段中的明确 challenge-platform marker。UI 只接收固定 Linux.do origin 和不透明
request ID，不接收响应正文、path、Cookie 或 exception。

一个认证 exchange 只有一次 challenge/replay 预算。在 `_t` 尚未产生时，CSRF/OTP 部分
最多重放一次；`_t` 产生后只重试失败的 revoke 或 identity 请求，因此不会重放已消费的
OTP。第二次 challenge 会直接上报。challenge handoff 可以合并 `cf_clearance` 等代理状态，
但会明确排除浏览器 `_t`，避免其他浏览器账号替换 OTP 建立的 session。在 Android 上，
受限 WebView 与 Ktor client 使用相同的系统 WebView User-Agent，使 Cloudflare 绑定浏览器的
clearance 在一次允许的重放和隔离的备用 session probe 中保持有效。手工 challenge 默认最多
等待 180 秒，一次性浏览器状态在 `NonCancellable` 清理中删除。

Realtime catch-up 和 MessageBus 请求共用一次 replay challenge 预算。收到 challenge 后，前台
宿主会在原 generation-bound request lease 仍然有效时展示固定的 Linux.do 浏览器页面，只合并
有界的 challenge Cookie 快照，然后重新执行完整的 reconciliation pipeline。取消或第二次
challenge 会回到终止恢复 UI，不会静默切换账号。

## Android 回调和 Intent 加固

Safe Intent Redirection 被视为高优先级安全边界。Launcher Activity 为系统启动需要而
exported，但不消费外部 data；`DiscourseAuthRedirectActivity` 是唯一 exported data 入口。

Redirect Activity：

- 在 `onCreate` 和 `onNewIntent` 使用同一 validator，并在 warm path 处理前调用
  `setIntent`；
- 要求 `ACTION_VIEW`、精确显式 component；可选 package 字段存在时必须匹配应用 package；
  同时要求 BROWSABLE、仅 allowlist category 和精确 `discourse://auth_redirect` route；
- 拒绝 URI grant flag、不支持 flag、ClipData、selector、MIME type、identifier、
  source bounds、path、fragment 和过长 URI；
- 从不调用 `getExtras`，也不 unmarshal 或转发任何 extra（包括 nested Intent）；
- 只从 allowlist 标量路由字段构建全新 Intent，然后按精确 component、可选 package policy、
  action、category、flag 和 data allowlist 调用 `IntentSanitizer.sanitizeByThrowing`；
- 清除 Activity 持有的不可信 Intent，只把已校验 URI 放入单槽进程内 inbox，并通过固定
  显式 `MainActivity` Intent 返回，不复制任何 payload。

即使字段已加密，URI 仍被视作敏感信息，因此不会持久化或记录。快速 exported component
交接完成后，由 retained authentication presenter 执行 nonce、有效期和单次消费校验。

自定义 `discourse:` scheme 无法在所有平台独占。其他应用可能截获回调并造成拒绝服务。
由于回调 secret 使用 FlareDo 一次性公钥加密且必须匹配其 nonce，仅截获回调不应让该应用
获得 FlareDo 已登录会话。

## Session generation、CAS 与 cancellation

每次登录或退出都会推进单调递增 session generation。网络工作会在 transition mutex 下
捕获 generation、account owner、generation job 和 cookie revision。
`runForCurrentSession` 使用 structured concurrency：请求仍是调用方的 child，同时独立
bridge 会在所捕获 generation 被替换时取消它。调用方 cancellation 原样重新抛出；仅在
确认调用方仍 active 后，内部 generation 替换才转换成类型化 stale session failure。

破坏性或延迟操作使用 compare-and-set 所有权校验：

- expected generation 和 account ID 保护已认证 mutation 及 logout；
- expected old vault reference 保护 session checkpoint 替换；
- attempt ID 加 observed vault reference 保护 callback consume/delete；
- Cookie 和 CSRF store 使用 revision/CAS，过期响应或 invalidation 不能覆盖新值；
- 浏览器 terminal action 必须获得 actor 层所有权，不能只依赖 queue acceptance。

会 suspend 的 vault 删除、浏览器清理、rollback 和 logout 清理放在范围很窄的
`NonCancellable` 段中。`NonCancellable` 不会被用来让正常网络工作永久不可取消；它只在
所有权确定后确保关键本地清理到达终态。清理结束后仍检查并传播原始 caller cancellation，
次要清理错误只作为附加信息，不会取代原错误。

## 凭据持久化

Room 是引用和公开元数据索引，不是 secret store。完整 Cookie envelope 和待处理认证
envelope 都位于 `SecureCredentialRef` 背后：

- Android：不可导出的 Android Keystore AES-256-GCM key；随机 record ID、引用绑定 AAD、
  私有 no-backup 目录中的有界密文以及原子文件替换。
- Apple：固定 service 下的 Generic Password Keychain item、随机 account ID、
  `WhenUnlockedThisDeviceOnly` 且不设置 synchronizable。
- Windows：CurrentUser DPAPI 加引用绑定 optional entropy，密文有界并保存在用户应用数据
  目录。
- Linux：不经过 shell 直接调用 `secret-tool`，secret 通过 stdin 提供，输出有界，并在
  存储 envelope 内校验引用。

Linux Secret Service 缺失或锁定时，FlareDo 使用进程内 store。它没有 snapshot/file API，
在所有权边界复制 buffer，并尽力覆写已删除 buffer。每进程 reference namespace 使旧 Room
引用失败，而不会解析到其他进程的凭据；不存在明文 fallback。

Room 数据库本身没有 FlareDo 应用层加密。公开论坛缓存、草稿正文、公开账号元数据、不透明
引用和最小通知游标依赖平台沙箱及设备存储保护。

## CSRF 与已认证 mutation

CSRF 只驻留内存，有大小/控制字符校验，并在单一 refresh mutex 下获取。登录或退出会清空
store。状态修改请求必须先证明其 generation lease 已认证，再获取 token。只有明确分类的
CSRF 拒绝才会 invalidation 本次使用的精确 token，并允许重新获取及重放一次。延迟拒绝不能
删除较新 token；第二次失败、普通 403、rate limit 或 Cloudflare response 不按 CSRF 重放。

Composer 操作继续绑定 generation/account。草稿只包含可编辑文本，不包含 queued write 或
upload bytes。Upload attempt 是调用方的 structured child；已取消/重试 attempt 的迟到进度
不能更新替换后的 attempt。服务器权限、校验、审核队列和限流结果始终是权威结果。

## 不可信 HTML 与 URL

Linux.do `cooked` HTML 使用 KSoup 解析成跨平台 typed block tree。Compose/SwiftUI 永远
收不到 raw HTML，也没有 WebView fallback。支持的安全值包括文字、HTTPS 链接/图片、列表、
引用、代码、表格和 spoiler。

Parser 会：

- 丢弃 script、iframe、form、SVG、object、embed、audio/video、style、input 等活动
  element 的整个 subtree；
- 不复制任意 attribute，并记录 event attribute、`style` 和 `srcdoc` 的移除；
- 只接受 HTTPS absolute URL，或相对 `https://linux.do/` 解析的 URL；
- 拒绝协议相对 URL、`javascript:`、`data:`、HTTP、userinfo、反斜杠、控制/空白 octet、
  encoded dangerous octet 和 malformed authority；
- 规范化 path dot segment，并在构建 UI data 前/期间限制输入 byte、node、depth、text、
  URL、block、list、table 和 attribute；
- 解析器失败时返回安全空文档，绝不回退到 raw markup。

外部 HTTPS URL 仍可能恶意、跟踪图片加载或展示欺骗性内容。Sanitization 阻止脚本执行，
但不会认证链接或图片主机的真实性、声誉或隐私性。

## 实时失败恢复

MessageBus 只在 host-owned foreground lifecycle scope 中运行。回到前台会先进行权威 REST
catch-up，再订阅；进入后台或切换 topic/session 会取消 structured polling child。可重试
transport/server/serialization 错误和 429 会退避，并遵守有界 `Retry-After`。

认证失败、permission/CSRF 失败或 HTTP 401/403 对捕获的 generation 是 terminal。Coordinator
会先关闭 poll 并 gate 该 generation，再请求 recovery，所以导航或前后台切换不能形成重连
循环。正式 recovery 通过第二次 CAS，仅删除精确 generation 的平台 vault 引用和内存 session。
如果安全持久化无法清除，轮询保持停止，全局 recovery UI 只提供 owner-checked 退出/重试，
而不是静默丢弃状态。明确 Cloudflare challenge 需要独立的用户交互流程，不会自动当作退出。

Event 不直接修改缓存论坛 model。只有经过校验的单调 cursor CAS winner 会触发权威 REST
刷新。仅 notification cursor 持久化；任意 channel、payload、client ID、Cookie 和 shared key
都不会写入本地持久化。

## 日志与错误隔离

项目未安装 Ktor logging plugin。网络 exception 使用固定 message 和 allowlist 标量；有界
响应片段只用于分类 Cloudflare、CSRF、rate limit 或 validation，之后立即丢弃。认证对象的
`toString` 会脱敏。

Shared diagnostic ring 在截断前脱敏，并按 entry 数和总字符数限制大小。Android redirect
audit 只输出两个封闭 enum 名称。URI、Cookie、header、响应正文、草稿、上传 byte、vault
reference 或任意 exception 都不会被有意写入这些日志。

脱敏只是纵深防御，不能作为记录 secret 的许可。新增代码在信息到达 sanitizer 之前就必须
避免把 secret 放入 message。

## 剩余风险与非保证

- FlareDo 不是端到端加密。TLS 终止后 Linux.do 能读取内容，并控制服务器保留和授权。
- 没有 certificate pinning。失陷的平台 trust store、设备、浏览器引擎、依赖、Linux.do
  源或分发构建都可能破坏本文假设。
- Vault 保护降低静态暴露，无法保护已经进入失陷进程的 bearer Cookie，也无法保证擦除
  不可变对象或运行时副本。
- FlareDo 不加密本地 Room 数据库；草稿机密性依赖应用沙箱和设备存储控制。
- 远程 HTTPS 图片会向主机暴露网络元数据。安全 HTML 解析无法阻止 tracking pixel、钓鱼
  文案、恶意目标或用户授权的外部跳转。
- 自定义 callback scheme 不是独占 verified app link，可能被截获并造成拒绝服务。
- User API Key 立即撤销和远程退出都需要服务器连接。FlareDo 会在失败时清理本地材料，
  但离线时无法保证服务器接受了撤销请求。
- 安全存储和清理 API 可能失败。只要可恢复引用仍可能存在，应用会优先显示可重试的认证/
  recovery 状态，而不是声称已成功。
- 控制项有 unit、host、device 和 Apple test 覆盖，但测试不能证明不存在所有漏洞。

## 漏洞报告

不要在公开 issue 中包含凭据、Cookie、callback URI、私密帖子或原始数据库。请按
[SECURITY.zh-CN.md](../../SECURITY.zh-CN.md) 查看受支持版本和私下报告渠道。一份有帮助的
报告应包含平台、受影响 revision、安全边界、使用合成数据的复现步骤，以及预期和实际行为。
