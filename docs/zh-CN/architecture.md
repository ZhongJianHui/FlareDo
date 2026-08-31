# 架构

本文档说明当前源码树中已经实现的架构，而不是未来路线图。FlareDo 是专用于
[Linux.do](https://linux.do/) 的单站点客户端，支持 Android、iOS、macOS、Windows 和 Linux。

## 架构约束

- 论坛唯一源站是编译期常量 `https://linux.do`，用户无法修改。
- 论坛协议、映射、会话、持久化、Presenter 和实时行为均由 Kotlin Multiplatform 负责。
- Android、Windows 和 Linux 使用 Compose 渲染共享展示状态；iOS 和 macOS 通过一层精简的
  Kotlin 桥接使用原生 SwiftUI 视图。
- 同一时间只能有一个 Linux.do 账号处于活动状态。持久化 schema 仍按账号 ID 隔离账号专属
  数据，因此未来设计多账号能力时无需重新解释已有数据。
- 认证材料只应存放在各平台的凭据保险库中。Room 只保存不透明的保险库引用和公开账号元数据，
  绝不保存原始 Cookie、CSRF token、OTP 或私钥。
- 每个网络操作都绑定到不可变的会话代际（session generation）。登录和退出会推进代际，并取消
  捕获自已被替换会话的任务。
- 实时任务只在宿主处于前台时运行。每次新建 MessageBus 订阅前，都会先通过 REST 完成状态对账。

## 平台外壳

| 产品目标 | 最低支持版本 | UI | 共享代码入口 | 平台负责的功能 |
| --- | --- | --- | --- | --- |
| Android | API 26 | Jetpack Compose | `:compose-ui` | Activity/进程生命周期、受保护的重定向 Activity、Android Keystore、WebView Cookie 桥接、Room 数据库路径、附件选择器 |
| iOS | iOS 17 | SwiftUI | 来自 `:apple-shared` 的 `KotlinSharedUI` | Scene 生命周期、系统 URL 打开、受限 `WKWebView`、Keychain、应用容器数据库路径、原生附件选择器 |
| macOS | macOS 14，arm64 | 使用 `NavigationSplitView` 的 SwiftUI | 来自 `:apple-shared` 的 `KotlinSharedUI` | 窗口生命周期和命令、自定义 URL 投递、受限 `WKWebView`、Keychain、沙盒容器路径 |
| Windows | 10.0.17763.0 | JVM 上的 Compose Desktop | 经 `:desktopApp` 接入 `:compose-ui` | Nucleus/Tao 窗口、单实例回调代理、CurrentUser DPAPI 保险库、桌面文件选择器、AppX 打包 |
| Linux | 依发行版而定的桌面运行时 | JVM 上的 Compose Desktop | 经 `:desktopApp` 接入 `:compose-ui` | Nucleus/Tao 窗口、单实例回调代理、Secret Service 保险库或仅会话回退、桌面文件选择器、AppImage 打包 |

Android application ID 和两个 Apple bundle identifier 均为
`io.github.zhongjianhui.flaredo`。目标格式支持时，桌面软件包也使用同一标识。内部 Kotlin 命名空间
`dev.dimension.flare` 被保留下来，以避免对继承自 Flare 的历史进行大规模、无功能变化的改写。

## 模块边界

Gradle 项目有意保持精简的模块图：

| 模块 | 目标 | 职责 |
| --- | --- | --- |
| `:shared` | Android、JVM、iOS、macOS | 平台无关的 UI 模型、Room schema/工厂、有界脱敏日志、`PlatformSpec` 注册表、Molecule Presenter 基类 |
| `:social:discourse` | Android、JVM、iOS、macOS | Linux.do DTO 与 Ktorfit 路由、固定源站 Ktor 客户端、会话/认证、Repository、分页、cooked HTML 解析、编辑器、MessageBus、Koin 定义 |
| `:compose-ui` | Android、JVM | 自适应 Compose 工作台、富文本渲染器、编辑器、认证浏览器 effect、语义和桌面渲染测试 |
| `:app` | Android | Android Application/Activity、宿主 Koin override、重定向收件箱和 Intent 验证、Android 截图及框架测试 |
| `:desktopApp` | JVM | Windows/Linux 进程和窗口生命周期、回调代理、保险库选择、数据库路径、桌面打包 |
| `:apple-shared` | iOS、macOS | 静态 `KotlinSharedUI` framework，以及便于 Swift 使用的快照和宿主 facade |
| `appleApp` | iOS、macOS Xcode target | 原生 SwiftUI 外壳、本地化、受限浏览器策略、Apple 生命周期和 XCTest |

`social/discourse` 依赖 `shared`；各平台 UI 同时依赖二者。平台入口模块通过 Koin override 安装
宿主专属服务，因此通用协议代码不会导入 Activity、Keychain API、DPAPI 调用或桌面窗口类型。

## 数据与展示流程

```text
Compose / SwiftUI host
        |
        v
Molecule presenter and bounded action actor
        |
        v
forum / account / composer repositories
        |                         \
        v                          v
DiscourseDataSource             Room cache, drafts,
        |                       cursor and vault refs
        v
DiscourseApi -> Ktorfit wire routes -> fixed-origin Ktor client
        |                                     |
        v                                     v
strict DTO validation                    https://linux.do
        |
        v
mapper + safe cooked-HTML parser
        |
        v
UiTimelineV2 / UiArticle / Swift snapshots
```

传输 DTO 不会越过 Repository 边界。`DiscourseForumMapper` 及其账号/搜索对应 Mapper 会生成稳定的
展示值，例如 `DiscourseTopicRef`、`DiscourseTopicMeta` 和 `DiscoursePostMeta`。这些类型会特意区分
数据库 post ID 与回复关系图和深链接使用的可见帖子编号。

`DiscourseForumPresenter`、`DiscourseComposerPresenter` 和
`DiscourseAuthenticationPresenter` 对外提供由 Molecule 产生的不可变状态。其传入 action 是有界且
由生命周期拥有的。Presenter 不拥有进程全局 scope：关闭 Presenter 会取消其 actor、进行中的子任务、
实时任务和待处理 UI 操作。Android 在 `ViewModel` 中跨配置变更保留 Presenter；Apple 和桌面宿主则
随屏幕或应用生命周期显式关闭 Presenter。

## 会话与请求生命周期

`DiscourseSessionManager` 发布 `Guest(generation)` 或
`Authenticated(generation, accountId, ...)`。`runForCurrentSession` 让请求保持为调用方结构化并发中的
子任务，同时从已捕获的代际安装取消桥接。因此两类所有者都拥有控制权：

1. 导航或 Presenter teardown 会取消请求；
2. 登录/退出会取消属于已被替换代际的所有请求；
3. 代码在取消后恢复执行时，会先检查当前代际，之后才能发布、缓存或变更状态。

对所有者敏感的延迟操作还会同时比对预期代际和账号 ID。凭据引用的检查点采用 compare-and-set
语义，防止较旧的回调把保险库值附加到替代账号。CSRF token 只存在于内存中，并在每次会话切换时
清除。必须在取消后继续完成的退出清理，会在严格限定范围的 `NonCancellable` 区段中运行。

主登录路径会创建一次性 RSA 密钥对，在系统浏览器中打开 Linux.do 的 User API Key 授权页面，验证
`discourse://auth_redirect` 回调和 nonce，解密回调，用 OTP 换取常规 `_t` Web 会话，并立即撤销临时
User API Key。受限、固定源站的 WebView Cookie 移交是备用路径。Cloudflare 处理需要用户参与，并且
最多只会重放失败阶段一次。

Android 还提供原生密码界面，并使用短生命周期的受限 WebView 完成 hCaptcha 与同源会话请求；它
支持 TOTP 重试，也可由用户明确选择通过平台 vault 记住密码。完整的受限浏览器仍用于 passkey、
OAuth、注册、找回账号和不支持的第二验证方式。其他宿主通过完整受限浏览器处理这些由网页管理的流程。

跨设备登录使用严格的 FlareDo 自有 `flaredo://qr-login` 格式。已登录设备经确认后可以创建一个有效期
十分钟的 User API Key/OTP bearer capability 并展示。Android 与 Apple 宿主可通过相机扫描，Apple
与桌面宿主可导入图片。接收端访客会校验完整 route 与大小边界、交换 OTP，在身份查询前撤销临时
key，并通过常规 vault 路径激活新会话。展示端在关闭、重新生成、Presenter teardown 或应用退出时
撤销当前 key。

## 持久化

Room schema 版本 5 包含五个实体，分为四组存储用途：

- 论坛缓存元数据和条目（两个实体）；
- 编辑器草稿；
- MessageBus 游标；
- 安全保险库引用。

公开论坛缓存只由访客读取写入，并仅在访客读取失败时作为离线回退。已认证响应会绕过此公开缓存，
避免账号专属的未读、权限、点赞或书签状态泄漏到匿名存储。只有在完整的权威 post stream 已通过
验证和映射后，主题响应才会写入缓存。

如果宿主提供持久化 Room，草稿会按账号隔离并在进程重启后保留。认证过期后草稿仍会保留，但
FlareDo 不会将离线提交或操作加入队列。

只有 `/notification/{accountId}` MessageBus 游标会持久化。`/latest`、`/new`、主题和 reaction 游标
保留在有界的进程内存中，因为前台 REST 补拉可以重建这些游标。主题/reaction 游标对共享一份 LRU
配额，防止浏览历史无限增长。

凭据实现由宿主选择：

- Android：以 Android Keystore 为后端的加密保险库；
- iOS/macOS：Apple Keychain；
- Windows：CurrentUser DPAPI；
- Linux：Secret Service。如果不可用，认证仅在当前会话有效，并且不会创建明文回退。

## Cooked 内容边界

Linux.do 返回以 `cooked` HTML 渲染的帖子内容。`DiscourseCookedHtmlParser` 使用 KSoup，只将支持的
结构映射到 `UiArticleBlock` 和 `UiArticleInline`：文本、安全链接和图片、列表、引用、代码、表格和
spoiler。UI 契约中不包含原始 HTML。

脚本、事件属性、不安全 scheme、不受信任的 data URI、不受支持的 frame、格式错误的表格 span 和
越界结构会被丢弃或降级为安全文本。因此 Compose 与 SwiftUI 会渲染同一份经过净化的文档，而不是
分别实现两套 HTML 信任策略。

## 实时生命周期

`DiscourseRealtimeCoordinator` 根据前台状态、当前主题和当前会话代际推导 allowlist 订阅：

- 每个会话均订阅 `/latest` 和 `/new`；
- 已认证会话订阅 `/notification/{numericAccountId}`；
- 已选中的主题订阅 `/topic/{topicId}` 和 `/topic/{topicId}/reactions`。

进入前台或订阅发生变化时，Presenter 首先刷新相关 REST 状态，随后解析同源轮询端点或可选的跨源
MessageBus 端点。同源传输使用受保护的 Cookie 客户端。跨源传输不携带 Cookie；已认证的跨源轮询
只使用临时 `X-Shared-Session-Key`，其自有缓冲区会在对应代际 lease 结束时清除。

MessageBus 事件是信号，不是权威缓存载荷。游标通过 compare-and-set 保持单调递增，只有胜出者会
执行类型明确的 REST 刷新。因此重复或延迟事件不会重复变更状态，也不会让游标倒退。HTTP 401、
403、明确的 CSRF 失败和明确的 Cloudflare challenge 会停止当前代际并进入会话恢复。可重试失败
使用带抖动且有上限的指数退避，最长不超过 180 秒。

## 自适应 UI

Compose 在手机、平板/折叠设备和桌面端使用同一组语义 pane：

- compact：底部导航，同时只显示一个内容 pane；
- medium：导航 rail，以及 list/detail 行为；
- expanded：在宽度预算允许时显示导航、列表、正文和辅助分类信息。

Android 使用自适应 Navigation 3 scene。桌面端没有 Android 的 scene 策略，因此采用明确的 pane
预算；辅助内容会先于正文折叠，避免正文变得难以阅读。SwiftUI 在紧凑型 iPhone 布局中使用
`TabView` 和 `NavigationStack`，在 regular iPad 和 macOS 布局中使用 `NavigationSplitView`。

所有布局都使用相同的 Presenter 权限。UI 控件绝不会自行推断操作是否允许：创建、回复、编辑、点赞
和书签入口必须同时满足服务端同步下发的状态与相符的会话所有者。

## 明确不支持的范围

当前架构不提供后台推送、Discourse Chat、任意 Discourse 服务器、多个同时活动的账号、离线写入队列、
应用商店发布、签名软件包或 GitHub Releases。这些能力需要明确设计新的安全和生命周期方案，而不是
暗藏在 v1 传输层中的扩展点。
