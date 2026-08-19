# FlareDo

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Bootstrap CI](https://github.com/ZhongJianHui/FlareDo/actions/workflows/bootstrap.yml/badge.svg)](https://github.com/ZhongJianHui/FlareDo/actions/workflows/bootstrap.yml)

[English](#english) | [简体中文](#简体中文)

## 简体中文

FlareDo 是面向 [Linux.do](https://linux.do/) 的非官方、开源、多平台论坛客户端，计划支持 Android、iOS、macOS、Windows 和 Linux。项目使用 Kotlin Multiplatform 共享业务逻辑，在 Android、Windows 和 Linux 上使用 Compose，在 iOS 和 macOS 上使用 SwiftUI。

项目目前处于早期开发阶段，尚未发布安装包。首个可用版本将聚焦主题浏览、分类与标签、搜索、登录、发帖与回复、上传、点赞、书签、通知和个人资料。

### 来源与独立性

- FlareDo 基于 [Flare](https://github.com/DimensionDev/Flare) 开发，保留其 Git 历史、版权声明以及 AGPL-3.0 许可要求。
- [fluxdo](https://github.com/Lingyan000/fluxdo) 仅用于参考公开的 Linux.do/Discourse API 行为；FlareDo 不复制其源代码、文案、测试数据、渲染器或素材。
- FlareDo 是社区独立项目，与 Linux.do、Discourse、Flare、fluxdo 及其维护者不存在隶属、合作或认可关系。

FlareDo 的发布版本不会包含遥测、广告或第三方分析。登录凭据将存储在各平台的安全凭据设施中；详细隐私和安全设计会随实现一并公开。

问题与贡献请使用本仓库的 [Issues](https://github.com/ZhongJianHui/FlareDo/issues) 和 [Pull requests](https://github.com/ZhongJianHui/FlareDo/pulls)。

## English

FlareDo is an unofficial, open-source, cross-platform forum client for [Linux.do](https://linux.do/), targeting Android, iOS, macOS, Windows, and Linux. It shares business logic with Kotlin Multiplatform, uses Compose on Android, Windows, and Linux, and uses SwiftUI on iOS and macOS.

The project is in early development and does not publish installable builds yet. The first usable release will focus on topic browsing, categories and tags, search, sign-in, topics and replies, uploads, likes, bookmarks, notifications, and profiles.

### Origins and independence

- FlareDo is based on [Flare](https://github.com/DimensionDev/Flare) and preserves its Git history, copyright notices, and AGPL-3.0 obligations.
- [fluxdo](https://github.com/Lingyan000/fluxdo) is consulted only for observable Linux.do/Discourse API behavior. No fluxdo source code, copy, fixtures, renderers, or assets are copied into FlareDo.
- FlareDo is an independent community project. It is not affiliated with, sponsored by, or endorsed by Linux.do, Discourse, Flare, fluxdo, or their maintainers.

FlareDo releases will contain no telemetry, advertising, or third-party analytics. Authentication material will be kept in each platform's secure credential facility; the detailed privacy and security design will be documented alongside the implementation.

Use this repository's [Issues](https://github.com/ZhongJianHui/FlareDo/issues) and [Pull requests](https://github.com/ZhongJianHui/FlareDo/pulls) to report problems or contribute.

## License

FlareDo is licensed under the [GNU Affero General Public License v3.0](LICENSE). The original Flare notices and contributor history remain part of this repository.
