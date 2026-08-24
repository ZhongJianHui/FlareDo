# 为 FlareDo 做贡献

感谢你帮助改进 FlareDo。本文介绍问题报告、改动提案、本地验证和许可要求。英文版见 [CONTRIBUTING.md](CONTRIBUTING.md)。

FlareDo 是非官方 Linux.do 客户端，与 Linux.do 或 DimensionDev 均无隶属、授权或维护关系。请只在本项目报告 FlareDo 缺陷；论坛账号、内容审核和服务可用性问题应由 Linux.do 处理。

## 提交 Issue 前

1. 先搜索已有的开放和已关闭 Issue。
2. 不要公开凭据、Cookie、CSRF 令牌、User API Key、私密帖子、个人数据或未脱敏日志。
3. 如涉及漏洞或敏感复现细节，请按照 [SECURITY.zh-CN.md](SECURITY.zh-CN.md) 使用 GitHub 私密漏洞报告，不要创建公开 Issue。
4. 使用 Issue 表单，注明 FlareDo 版本或提交、平台/系统版本、预期行为、实际行为和最小复现步骤。

功能建议应说明用户问题，以及方案如何符合 FlareDo 单站点、隐私优先的范围。较大的架构或产品调整应先讨论再实现。

## 开发环境

当前构建需要 JDK 25。Apple 开发还需要兼容的 macOS/Xcode 工具链和 [XcodeGen](https://github.com/yonaskolb/XcodeGen)。准确的支持命令以 `.github/workflows/bootstrap.yml` 为准。

常用命令：

```shell
# Kotlin 格式与静态样式检查
./gradlew ktlintCheck

# Android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 共享 API、数据和桌面端测试
./gradlew :social:discourse:jvmTest :shared:jvmTest
./gradlew :compose-ui:jvmTest :desktopApp:test

# 本地运行 Compose Desktop
./gradlew :desktopApp:run

# 生成 Apple Xcode 工程
xcodegen generate --spec appleApp/project.yml
```

使用生成的 `appleApp/FlareDo.xcodeproj` 构建 iOS 和 macOS 应用。Apple 相关改动还应运行相应 XCTest 与 Kotlin/Native framework 链接任务。

## 项目结构

- `social/discourse`：Linux.do/Discourse DTO、API 契约、会话、分页、映射和 MessageBus。
- `shared`：共享数据库、缓存、Presenter 和应用契约。
- `compose-ui`：Android、Linux 和 Windows 的自适应 Compose UI。
- `app`：Android 宿主、安全存储和加固后的授权回调。
- `desktopApp`：Linux/Windows 宿主、vault 集成、回调 broker 和打包。
- `apple-shared`：导出给 Apple 应用的 Kotlin framework。
- `appleApp`：iOS/macOS SwiftUI 应用及 XCTest。

业务规则应位于共享 Presenter 和服务中；Compose 与 SwiftUI 只负责渲染状态并转发用户意图。请保留结构化并发、单调会话 generation、fail-closed 存储、严格同源校验、安全 HTML 解析和已记录的分页契约。

## 代码、文案与测试数据

- 代码注释和 KDoc 使用英文；重点解释不直观的协议、安全、分页和并发不变量，不要仅复述代码。
- 面向用户的 UI 和项目文档必须同步提供英文与简体中文。
- fixture 必须为有权分发的合成、最小化、脱敏数据。fluxdo 只能用于核对公开可观察的 API 行为；不得复制其 Dart 代码、fixture、文案、渲染器、插图或其他资产。
- 自动化测试必须使用假服务，绝不能向生产 Linux.do 执行写操作。真实账号发帖、上传、reaction 等写操作仅限人工 smoke test，相关凭据与结果不得提交。
- 不要加入遥测、分析或远程崩溃上报。

## Pull Request

每个改动应保持聚焦，并说明：

- 问题与预期行为；
- 受影响平台及安全/隐私影响；
- 已运行的测试，以及任何有意更新的截图 golden；
- 相关 Issue 或设计讨论（如适用）；
- 英文和简体中文文案/文档更新。

请求评审前，请运行 `git diff --check`、相关测试和 `./gradlew ktlintCheck`。不要混入生成物、签名文件、凭据或无关格式化。

提交标题采用清晰的 Conventional Commit 风格。提交一旦发布到 `main`，不得 amend、squash、rebase、force-push 或以其他方式改写；评审与 CI 修复应追加 `fix(...)` 提交。

## 许可与来源

贡献按仓库的 [GNU Affero General Public License v3.0](LICENSE) 接受。提交贡献即表示你有权以 AGPL-3.0 授权该内容，并准确说明其来源。

FlareDo 保留 Flare 截至基线提交 `44f9fd5e1` 的完整历史、版权声明和署名。来源及第三方说明见 [NOTICE](NOTICE)。
