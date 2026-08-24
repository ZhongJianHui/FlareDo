# Contributing to FlareDo

Thank you for helping improve FlareDo. This guide covers issue reports, proposed changes, local verification, and licensing. A Simplified Chinese version is available in [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md).

FlareDo is an unofficial Linux.do client and is not affiliated with Linux.do or DimensionDev. Please use the project issue tracker for FlareDo defects only; forum account, moderation, and service-availability questions belong to Linux.do.

## Before opening an issue

1. Search open and closed issues for an existing report.
2. Do not publish credentials, cookies, CSRF tokens, user API keys, private posts, personal data, or unredacted logs.
3. For a vulnerability or a report containing sensitive reproduction details, follow [SECURITY.md](SECURITY.md) and use GitHub Private Vulnerability Reporting instead of a public issue.
4. Use the issue form and include the FlareDo revision, platform/OS version, expected behavior, actual behavior, and minimal reproduction steps.

Feature proposals should explain the user problem and how the proposal fits FlareDo's single-site, privacy-first scope. Discuss large architectural or product changes before implementation.

## Development setup

The build currently requires JDK 25. Apple development also requires a compatible macOS/Xcode toolchain and [XcodeGen](https://github.com/yonaskolb/XcodeGen). The CI workflow in `.github/workflows/bootstrap.yml` is the source of truth for exact supported commands.

Common commands:

```shell
# Kotlin formatting and static style checks
./gradlew ktlintCheck

# Android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Shared API, data, and desktop tests
./gradlew :social:discourse:jvmTest :shared:jvmTest
./gradlew :compose-ui:jvmTest :desktopApp:test

# Run Compose Desktop locally
./gradlew :desktopApp:run

# Generate the Apple Xcode project
xcodegen generate --spec appleApp/project.yml
```

Open the generated `appleApp/FlareDo.xcodeproj` to build the iOS and macOS applications. Run relevant XCTest suites and Kotlin/Native framework link tasks for Apple changes.

## Project structure

- `social/discourse`: Linux.do/Discourse DTOs, API contracts, session behavior, paging, mapping, and message bus.
- `shared`: shared database, cache, presenters, and application contracts.
- `compose-ui`: adaptive Compose UI for Android, Linux, and Windows.
- `app`: Android host, secure storage, and hardened authorization callback.
- `desktopApp`: Linux/Windows host, vault integrations, callback broker, and packaging.
- `apple-shared`: Kotlin framework exported to the Apple applications.
- `appleApp`: SwiftUI applications and XCTest suites for iOS and macOS.

Business rules belong in shared presenters and services; Compose and SwiftUI should render state and forward user intent. Preserve structured concurrency, monotonic session generations, fail-closed storage, strict same-origin checks, safe HTML parsing, and documented pagination contracts.

## Code, copy, and test data

- Write comments and KDoc in English. Comment non-obvious protocol, security, pagination, and concurrency invariants rather than restating code.
- Keep user-facing UI and project documentation synchronized in English and Simplified Chinese.
- Use synthetic, minimized, redacted fixtures that we have the right to distribute. Do not copy fluxdo Dart code, fixtures, strings, renderers, artwork, or assets; it may be used only to compare publicly observable API behavior.
- Automated tests must use fake services and must never perform production write operations against Linux.do. Real-account posting, uploading, reactions, or other writes are manual smoke tests and their credentials/results must not be committed.
- Do not add telemetry, analytics, or remote crash reporting.

## Pull requests

Keep each change focused and include:

- the problem and intended behavior;
- affected platforms and security/privacy implications;
- tests run and any intentional screenshot-golden changes;
- linked issue or design discussion when applicable;
- both English and Simplified Chinese copy/documentation updates.

Run `git diff --check`, relevant tests, and `./gradlew ktlintCheck` before requesting review. Do not mix generated artifacts, signing files, credentials, or unrelated formatting into a pull request.

Use clear Conventional Commit-style subjects. Once a commit is published to `main`, never amend, squash, rebase, force-push, or otherwise rewrite it. Submit review and CI corrections as additional `fix(...)` commits.

## License and provenance

Contributions are accepted under the repository's [GNU Affero General Public License v3.0](LICENSE). By submitting a contribution, you confirm that you have the right to license it under AGPL-3.0 and that its provenance is accurately described.

FlareDo retains Flare's complete history through baseline `44f9fd5e1`, copyright notices, and attribution. See [NOTICE](NOTICE) for provenance and third-party clarification.
