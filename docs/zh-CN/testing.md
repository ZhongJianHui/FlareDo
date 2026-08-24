# 测试

FlareDo 将协议正确性、取消语义、凭据边界和自适应布局视为发布要求。自动化测试必须是确定性的，
且绝不能变更生产环境中的 Linux.do 账号。

当前工作流为 `.github/workflows/bootstrap.yml`，会在向 `main` 推送、针对 `main` 的 pull request 以及
手动调度时运行。GitHub Actions 只获得 `contents: read` 权限，checkout 不持久化凭据，构建产物不签名。

## 测试层次

### 共享单元测试与契约测试

Kotlin common 测试覆盖：

- 向前兼容 JSON（允许 `unknown` 字段，必填身份字段/类型保持严格）；
- 主题 page 0、搜索 page 1、通知 offset、页面重叠、去重和溢出保护；
- 精确的 `post_stream.stream` 分批与排序，以及重复/缺失/意外帖子和跨主题拒绝；
- 错误分类、有界 `Retry-After`、Cloudflare 证据、CSRF 识别、HTTP 422 allowlist 和审核队列响应；
- cooked HTML 注入样本、安全 URL 处理、嵌套/大小边界、表格、代码、引用、列表、图片和 spoiler；
- 访客/认证权限边界、公开缓存回退、搜索/资料/活动、通知和标记已读行为；
- 编辑器草稿、创建/回复/编辑、上传进度/取消/重试、乐观回滚、点赞和 bookmark 身份；
- 会话代际取消、Cookie revision 隔离、CSRF 单次刷新、旧所有者 CAS 和失败关闭清理；
- MessageBus 普通/chunked frame、截断和大小限制、每 channel 单调游标、重复事件、订阅变更、
  同源/跨源传输、共享 key 擦除、429 退避、180 秒重试上限、前后台切换和 401/403 恢复。

当时间或调度属于契约的一部分时，协程使用注入的 dispatcher、clock、delay、randomness 和
`kotlinx-coroutines-test`。取消测试会断言原始 `CancellationException` 仍保持取消语义，且清理过程
不会启动游离任务。

### 进程内端到端旅程

`DiscourseFakeServiceJourneyTest` 包含五条不访问网络的旅程，贯穿生产 Presenter、Repository、
`DiscourseDataSource`、受保护的 Ktor 客户端和 `DefaultDiscourseApi`：

1. 从访客最新 feed 进入权威 topic stream；
2. 从搜索结果进入精确主题/帖子编号；
3. User API Key 回调、OTP 交换、临时 key 撤销和持久会话激活；
4. 回复上传与发布，随后点赞和添加书签；
5. MessageBus 通知、REST 刷新，然后退出并清理所有者数据。

该服务使用自行编写的 Ktor `MockEngine` fixture，会拒绝每一个未识别的方法/路径组合。合成凭据和
payload 已脱敏，在生产环境中没有价值。

### 平台安全测试

- Android host 测试覆盖 RSA、Keystore wrapper、WebView Cookie 策略、浏览器移交所有权、取消窗口
  和宿主 Koin graph。
- Android managed-device 测试使用已安装的 manifest 和真实 framework，验证唯一导出的认证 Activity、
  冷/热启动回调、`IntentSanitizer`、嵌套 Intent 不透明性、URI grant、`ClipData`、selector、不支持的
  flag、重放行为以及真实 Android Keystore。
- JVM 测试覆盖桌面回调代理、有界 ACK deadline、单实例所有权、桌面生命周期清理和保险库 adapter。
- Linux CI 在私有 D-Bus 会话中启动已解锁的临时 Secret Service，并要求执行真实保险库 round trip/
  removal；还会在 Xvfb 下启动真实 Tao WebKitGTK 后端，并拒绝空白或单色渲染结果。
- Windows CI 要求执行 CurrentUser DPAPI round trip/removal；为提供精确诊断，回调代理与其余桌面
  生命周期测试会分开运行。
- Kotlin/Native 测试覆盖 RSA/Keychain 边界和 Swift 桥接快照；macOS CI 还会执行真实 Keychain
  round trip。
- Apple XCTest 检查精确源站 `WKWebView` 导航/Cookie 策略和 Cookie 移交所有权。

这些测试只使用本地操作系统设施，不向 Linux.do 发送凭据或变更操作。

### UI 与视觉测试

Android 使用 Compose Preview Screenshot Testing `0.0.1-alpha15`。Golden 覆盖完整的尺寸九宫格：

- 宽度：400、610 和 900 dp；
- 高度：400、500 和 1000 dp。

其他 golden 覆盖 compact/medium/expanded 的浅色与深色布局、1.5 字体缩放、缓存和错误状态、主题详情、
搜索、通知和个人资料。截图 fixture 不包含实时网络或账号数据。

Compose common 测试验证 window class 和 pane budget 边界、导航、分页触发、权限策略、换行/滚动和
语义。桌面端将 Compose hierarchy 渲染到离屏 Skiko surface，并断言 pane 顺序、语义 action、认证
控件、实时恢复和大字体可达性。

Apple XCTest 使用 `ImageRenderer` 渲染紧凑型 iPhone 和 regular iPad SwiftUI，并用 `NSHostingView`
渲染 macOS。测试会检查尺寸、非透明像素变化、分栏区域、辅助功能文本尺寸、深色模式、中文本地化和
持久实时恢复提示带。图像会保留在 `.xcresult` bundle 中以供评审。

## 本地命令

使用 JDK 25。Android 构建要求本地 Gradle 环境已配置 SDK。所有命令都从仓库根目录运行。

### 快速共享/JVM 检查

```bash
./gradlew \
  :shared:ktlintCheck \
  :social:discourse:ktlintCheck \
  :compose-ui:ktlintCheck \
  :desktopApp:ktlintCheck \
  :shared:jvmTest \
  :social:discourse:jvmTest \
  :compose-ui:jvmTest \
  :desktopApp:test \
  --no-parallel \
  --stacktrace
```

在 Linux 上，请在已解锁的 Secret Service D-Bus 会话中设置 `FLAREDO_REQUIRE_DESKTOP_VAULT=1`，使
保险库不可用成为测试失败。在 Windows 上使用 `gradlew.bat`，并设置同一环境变量以要求
CurrentUser DPAPI 可用。

### Android 源码、截图与 host 测试

```bash
./gradlew \
  :app:ktlintCheck \
  :shared:ktlintCheck \
  :social:ktlintCheck \
  :social:discourse:ktlintCheck \
  :compose-ui:ktlintCheck \
  :app:assembleDebug \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:validateDebugScreenshotTest \
  :shared:testAndroidHostTest \
  :social:discourse:testAndroidHostTest \
  :compose-ui:testAndroidHostTest \
  --stacktrace
```

在配置好的 API 30 AOSP ATD 镜像上运行真实 framework/保险库边界测试：

```bash
./gradlew \
  :app:flaredoAuthApi30DebugAndroidTest \
  :social:discourse:flaredoApi30AndroidDeviceTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  --stacktrace
```

获得有意的 UI 变更批准后，用 `./gradlew :app:updateDebugScreenshotTest` 重新生成基准，逐一检查每张
变更的 PNG，然后重新运行验证。绝不能为了消除无法解释的差异而直接更新 golden。

### Apple Kotlin target

以下 task 要求 macOS 和匹配的 Apple toolchain：

```bash
./gradlew \
  :apple-shared:ktlintCheck \
  :shared:iosSimulatorArm64Test \
  :shared:macosArm64Test \
  :social:discourse:iosSimulatorArm64Test \
  :social:discourse:macosArm64Test \
  :apple-shared:iosSimulatorArm64Test \
  :apple-shared:macosArm64Test \
  :apple-shared:linkDebugFrameworkIosSimulatorArm64 \
  :apple-shared:linkDebugFrameworkMacosArm64 \
  --stacktrace
```

使用 XcodeGen 根据 `appleApp/project.yml` 生成已纳入版本控制的 Xcode project，并要求没有 diff：

```bash
cd appleApp
xcodegen generate --spec project.yml
git diff --exit-code -- FlareDo.xcodeproj
```

在可用的 iPhone simulator 上运行 `iOS` scheme，并在本机 arm64 host 上关闭 code signing 后运行
`macOS` scheme。CI 命令行是规范实现，固定使用 Xcode 26.3 和经过 checksum 验证的 XcodeGen 2.46.0。

### 桌面端打包

软件包有意不签名：

```bash
# Linux host
./gradlew :desktopApp:taoWebViewSmoke :desktopApp:packageAppImage --stacktrace

# Windows host, from PowerShell
./gradlew.bat :desktopApp:packageAppX --stacktrace
```

`taoWebViewSmoke` 要求功能正常的 WebKitGTK/Tao display backend。项目所用软件包和 Xvfb 环境的
精确配置请参阅 Linux CI job。

## CI 矩阵

| Job | Runner | 必需验证 | 产物 |
| --- | --- | --- | --- |
| Android 源码与截图 | Ubuntu 24.04 | 格式、debug assembly、单元测试、lint、截图 golden、KMP Android host 测试、API 30 重定向与真实 Keystore 测试 | 截图和 instrumentation 报告 |
| Linux AppImage 与 Secret Service | Ubuntu 24.04 | JVM 测试套件、强制 Secret Service round trip、真实 Tao WebKitGTK smoke、未签名 AppImage | `linux-appimage` |
| Windows AppX 与 DPAPI | Windows 2025 | JVM 测试套件、强制 CurrentUser DPAPI、回调代理与生命周期诊断、未签名 AppX | `windows-appx` 和测试诊断 |
| Apple KMP、XCTest 与未签名 macOS | macOS 26 | iOS Simulator/macOS Kotlin 测试、framework link、生成 project 漂移、iOS/macOS XCTest、未签名 macOS Release | `.xcresult` bundle |

所有外部 action 都固定到 commit SHA。`main` 之外的 dependency cache 为只读，启用了 Gradle wrapper
验证，验证产物保留七天。

## 生产网络策略

CI 绝不能调用生产写入端点。API、认证、编辑器、上传、action、通知和 MessageBus 旅程使用
`MockEngine` 或本地假传输。原生测试可以使用 Android Keystore、Apple Keychain、Windows DPAPI、
Linux Secret Service、loopback socket、emulator、simulator 或本地 WebView，但不包含 Linux.do
凭据。

真实账号写入操作只允许作为人工 smoke test。请使用专用账号并将范围保持在最低限度；不得捕获秘密
或私有响应 body；在允许时清理测试创建的内容；绝不能将凭据、Cookie、OTP、API key 或保险库导出
添加到源码、fixture、日志或 CI 配置中。

## 新增或修改测试

- 优先使用自行编写的最小 fixture；不得复制生产响应或 fluxdo fixture。
- 拒绝未知的假服务路由，让端点漂移在传输边界触发失败。
- 覆盖成功、格式错误的成功响应、权限、取消和旧代际行为。
- 除像素外，还要用稳定语义 ID 和几何断言验证 UI 行为。
- 如果服务端文本可能包含草稿/账号数据，不要让它进入断言失败消息。
- 时间、随机数、dispatcher 和平台服务会影响结果时，应使其可注入。
- 若行为实现在 `actual`、宿主模块、导出 component、保险库、WebView 或打包层中，应添加平台测试；
  只用 common fake 不足以覆盖这些边界。
- 提交前重新运行相关本地检查；必须等到每个 GitHub check 均通过后，才开始下一实施阶段。
