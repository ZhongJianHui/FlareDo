<p align="center">
  <img src="branding/flaredo-mark.svg" width="112" alt="FlareDo 图标">
</p>

# FlareDo

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Source CI](https://github.com/ZhongJianHui/FlareDo/actions/workflows/bootstrap.yml/badge.svg)](https://github.com/ZhongJianHui/FlareDo/actions/workflows/bootstrap.yml)

[English](README.md) | 简体中文

FlareDo 是面向 Android、iOS、macOS、Windows 和 Linux 的非官方、开源
Linux.do 客户端。论坛、会话、缓存和展示逻辑由 Kotlin Multiplatform 共享；
Android、Windows、Linux 使用 Compose，iOS 和 macOS 使用 SwiftUI。

首个公开源码里程碑已经实现，并通过五端验证。项目暂不发布应用商店版本、
签名安装包或 GitHub Release；CI 产物仅用于源码验证，未使用发行签名，也不是正式发布包。

## 已实现功能

- 访客浏览最新、热门、分类、标签，以及严格按 Discourse 主题流加载的完整主题。
- 搜索、个人资料与活动、通知、未读状态和标记已读。
- 一个活动中的 Linux.do 账号，支持 Android 密码/hCaptcha/TOTP 登录、系统浏览器授权、
  受限的完整浏览器兜底，以及五端跨设备二维码登录。
- 发帖、回复、编辑、本地草稿、带进度/取消/重试的上传、点赞和书签。
- 前台 MessageBus 实时更新主题列表、用户通知、主题与 reactions；恢复前台时先用
  REST 补拉。
- 安全渲染 Linux.do `cooked` HTML、有界脱敏本地日志和只读离线缓存。
- 手机、平板、折叠屏及桌面自适应布局，支持键鼠、深色模式和放大字体。

本里程碑固定支持 Linux.do 和单一活动账号，不包含后台推送、Chat、通用多站点
Discourse、离线写入队列、应用商店发布或签名分发。

## 支持平台

| 目标 | UI | 最低版本 | CI 验证 |
| --- | --- | --- | --- |
| Android | Compose | API 26 | Debug APK 构建、测试、lint 与截图校验 |
| iOS | SwiftUI | iOS 17 | Simulator 构建与 XCTest |
| macOS | SwiftUI | macOS 14、Apple 芯片 | XCTest 与无签名 Release 构建 |
| Windows | Compose Desktop | Windows 10 `10.0.17763.0` | 测试与无签名 AppX 产物 |
| Linux | Compose Desktop | 支持 WebKitGTK 4.1 的发行版；持久登录可选用 Secret Service | 测试与无签名 AppImage 产物 |

## 从源码构建

项目使用 JDK 25。Android 构建需要 Android SDK（compile SDK 37）；Apple 构建需要
macOS、Xcode 26.3 和 XcodeGen 2.46.0，以匹配 CI 环境。

```bash
# Android Debug APK
./gradlew :app:assembleDebug

# 在 Linux 或 Windows 运行 Compose Desktop 开发版本
./gradlew :desktopApp:run

# 在对应宿主系统生成桌面安装包
./gradlew :desktopApp:packageAppImage
./gradlew.bat :desktopApp:packageAppX
```

Apple 端：

```bash
cd appleApp
xcodegen generate --spec project.yml
open FlareDo.xcodeproj
```

源码树不会包含签名或分发凭据。完整验证矩阵见[测试文档](docs/zh-CN/testing.md)，
提交 Pull Request 前请阅读[贡献指南](CONTRIBUTING.zh-CN.md)。

## 架构与文档

- [文档索引](docs/README.md)
- [架构](docs/zh-CN/architecture.md)
- [Linux.do API 契约](docs/zh-CN/api.md)
- [隐私](docs/zh-CN/privacy.md)
- [安全设计](docs/zh-CN/security-design.md)
- [测试](docs/zh-CN/testing.md)
- [安全问题报告策略](SECURITY.zh-CN.md)

## 隐私与安全摘要

FlareDo 不含遥测、广告、崩溃上报 SDK 或第三方分析。会话资料分别通过 Android
Keystore、Apple Keychain、Windows CurrentUser DPAPI 或 Linux Secret Service 保存。
Linux 缺少 Secret Service 时不会回退到明文持久化，只允许会话内登录。CSRF token
只驻留内存，Room 中的凭据资料仅以不透明引用保存。

退出登录后，论坛缓存、本地草稿和最小通知游标仍可能保留。成功退出会清除应用自有
凭据及内存中的 Cookie/CSRF 状态；清理失败会保留可重试提示。加载远程图片会向图片
主机暴露正常的网络元数据。具体边界见[隐私文档](docs/zh-CN/privacy.md)与
[安全设计](docs/zh-CN/security-design.md)。

请勿在公开 Issue 披露漏洞；请按照 [SECURITY.zh-CN.md](SECURITY.zh-CN.md) 使用
GitHub 私密安全公告报告。

## 来源、独立性与许可

FlareDo 基于 [DimensionDev/Flare](https://github.com/DimensionDev/Flare) 的基线提交
[`44f9fd5e17639e3d24d74826035d9b329461aa0c`](https://github.com/DimensionDev/Flare/commit/44f9fd5e17639e3d24d74826035d9b329461aa0c)
开发，并保留完整上游 Git 历史、原始版权声明与贡献者历史。

[fluxdo](https://github.com/Lingyan000/fluxdo) 仅用于比对可观察的公开
Linux.do/Discourse API 行为；FlareDo 未复制 fluxdo 的源代码、fixture、文案、
渲染器或资产。完整来源说明见 [NOTICE](NOTICE)。

FlareDo 是独立社区项目，与 Linux.do、Discourse、DimensionDev/Flare、fluxdo
及其维护者不存在隶属、赞助或认可关系；相关名称与商标归各自权利人所有。

本仓库使用 [GNU Affero General Public License v3.0](LICENSE)，贡献内容按相同许可
提供。
