<p align="center">
  <img src="branding/flaredo-mark.svg" width="112" alt="FlareDo logo">
</p>

# FlareDo

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Source CI](https://github.com/ZhongJianHui/FlareDo/actions/workflows/bootstrap.yml/badge.svg)](https://github.com/ZhongJianHui/FlareDo/actions/workflows/bootstrap.yml)

[简体中文](README.zh-CN.md) | English

FlareDo is an unofficial, open-source Linux.do client for Android, iOS, macOS,
Windows, and Linux. Kotlin Multiplatform provides the shared forum, session,
cache, and presentation layers. Android, Windows, and Linux use Compose;
iOS and macOS use SwiftUI.

The first public source milestone is implemented and verified on all five
targets. The project does not yet publish store builds, signed installers, or
GitHub Releases. CI artifacts are verification outputs without release signing;
they are not releases.

## What works

- Guest browsing for latest and popular topics, categories, tags, and complete
  Discourse topic streams.
- Search, profiles and activity, notifications, unread state, and mark-as-read.
- One active Linux.do account with system-browser authorization and a restricted
  browser fallback.
- New topics, replies, edits, local drafts, uploads with progress/retry/cancel,
  likes, and bookmarks.
- Foreground MessageBus updates for topic lists, user notifications, topics,
  and reactions, with REST catch-up after resume.
- Safe rendering for Linux.do `cooked` HTML, bounded redacted local logs, and
  read-only offline cache.
- Adaptive phone, tablet, foldable, and desktop layouts, keyboard/mouse support,
  dark mode, and enlarged text coverage.

FlareDo is intentionally Linux.do-only and supports one active account in this
milestone. It does not implement background push, Chat, multi-site Discourse,
offline write queues, store publishing, or signed distribution.

## Platforms

| Target | UI | Minimum supported version | CI verification |
| --- | --- | --- | --- |
| Android | Compose | API 26 | Debug APK assembly, tests, lint, and screenshot validation |
| iOS | SwiftUI | iOS 17 | Simulator build and XCTest |
| macOS | SwiftUI | macOS 14, Apple silicon | XCTest and unsigned Release build |
| Windows | Compose Desktop | Windows 10 `10.0.17763.0` | Tests and unsigned AppX artifact |
| Linux | Compose Desktop | Distribution with WebKitGTK 4.1; Secret Service is optional for persistent sign-in | Tests and unsigned AppImage artifact |

## Build from source

The repository uses JDK 25. Android builds require the Android SDK (compile SDK
37). Apple builds require macOS, Xcode 26.3, and XcodeGen 2.46.0 to match CI.

```bash
# Android debug APK
./gradlew :app:assembleDebug

# Compose Desktop development run on Linux or Windows
./gradlew :desktopApp:run

# Native desktop packages (run on the matching host)
./gradlew :desktopApp:packageAppImage
./gradlew.bat :desktopApp:packageAppX
```

For Apple hosts:

```bash
cd appleApp
xcodegen generate --spec project.yml
open FlareDo.xcodeproj
```

Signing and distribution credentials are deliberately absent from the source
tree. See [Testing](docs/en/testing.md) for the complete verification matrix and
[Contributing](CONTRIBUTING.md) before opening a pull request.

## Architecture and documentation

- [Documentation index](docs/README.md)
- [Architecture](docs/en/architecture.md)
- [Linux.do API contract](docs/en/api.md)
- [Privacy](docs/en/privacy.md)
- [Security design](docs/en/security-design.md)
- [Testing](docs/en/testing.md)
- [Security reporting policy](SECURITY.md)

## Privacy and security at a glance

FlareDo has no telemetry, advertising, crash-reporting SDK, or third-party
analytics. Session material is stored through Android Keystore, Apple Keychain,
Windows CurrentUser DPAPI, or Linux Secret Service. On Linux, persistent sign-in
fails closed to a session-only login when Secret Service is unavailable. CSRF
tokens remain in memory, and credential material in Room is limited to opaque
references.

Cached forum content, local drafts, and the minimal notification cursor can
remain after sign-out. A successful sign-out clears the app-owned credential and
in-memory Cookie/CSRF state; cleanup failures stay visible for retry. Loading
remote images can disclose ordinary network metadata to the image host. Read the
[privacy document](docs/en/privacy.md) and [security design](docs/en/security-design.md)
for the exact boundaries.

Do not disclose vulnerabilities in a public issue. Follow [SECURITY.md](SECURITY.md)
and use a private GitHub security advisory.

## Origins, independence, and license

FlareDo is based on [DimensionDev/Flare](https://github.com/DimensionDev/Flare)
at baseline commit
[`44f9fd5e17639e3d24d74826035d9b329461aa0c`](https://github.com/DimensionDev/Flare/commit/44f9fd5e17639e3d24d74826035d9b329461aa0c).
The complete inherited Git history, original copyright notices, and contributor
history are preserved.

[fluxdo](https://github.com/Lingyan000/fluxdo) was consulted only to compare
observable public Linux.do/Discourse API behavior. FlareDo does not copy fluxdo
source code, fixtures, text, renderers, or assets. See [NOTICE](NOTICE) for the
full provenance statement.

FlareDo is an independent community project. It is not affiliated with,
sponsored by, or endorsed by Linux.do, Discourse, DimensionDev/Flare, fluxdo,
or their maintainers. Their names and trademarks belong to their respective
owners.

This repository is licensed under the [GNU Affero General Public License
v3.0](LICENSE). Contributions are accepted under the same license.
