# AGENTS.md

This file defines repository-wide expectations for people and automated agents working on FlareDo.

## Project boundaries

- FlareDo is an unofficial, cross-platform client for `https://linux.do`. It is not affiliated with, endorsed by, or maintained by Linux.do or DimensionDev.
- The project is based on the complete Flare history at baseline commit `44f9fd5e1`. Preserve that history, its copyright notices, `NOTICE`, and the GNU Affero General Public License v3.0 (`LICENSE`).
- fluxdo may be consulted only to understand publicly observable API behavior. Do not copy its Dart source, fixtures, strings, renderers, artwork, or other assets.
- The first release supports one active Linux.do account. Do not silently expand the product to other Discourse sites, background push, chat, store publishing, signed packages, or offline write queues.
- Keep the public application ID `io.github.zhongjianhui.flaredo`. The internal `dev.dimension.flare` source package is intentionally retained to avoid a non-functional mass rewrite.

## Architecture

- Keep shared forum, cache, session, and presentation behavior in Kotlin Multiplatform modules (`shared`, `social:discourse`, and `apple-shared`).
- Android, Linux, and Windows use the shared Compose UI in `compose-ui`; iOS and macOS use SwiftUI in `appleApp`.
- Preserve the existing Molecule presenter, Room/Paging, Koin, Ktor/Ktorfit, and structured-coroutine patterns unless a change is explicitly justified.
- Treat `https://linux.do` as the only fixed forum origin. Any optional cross-origin MessageBus endpoint must remain discovered, validated, cookie-free, and generation-bound. Preserve strict pagination, topic-stream ID ordering, cookie-domain checks, CSRF handling, session-generation cancellation, and fail-closed credential storage.

## Security and privacy

- Never commit credentials, cookies, CSRF tokens, user API keys, private keys, personal data, production response dumps, signing material, or environment-specific secrets.
- Never run automated tests that write to production Linux.do. Use deterministic fake services and synthetic, bounded, redacted fixtures. Real-account write operations are manual smoke tests only.
- Keep authentication secrets in platform vaults. Room stores only opaque vault references; CSRF values remain in memory. Linux must remain session-only when Secret Service is unavailable and must never fall back to plaintext.
- Preserve defensive Android intent validation, nonce and one-time callback consumption, URI-grant rejection, restricted same-origin WebViews, and safe cooked-HTML parsing.
- Logs must be local, bounded, and redacted. Do not add telemetry, crash-reporting uploads, or analytics without an explicit project decision and corresponding privacy documentation.
- Report vulnerabilities privately as described in `SECURITY.md`; do not place exploit details in public issues, discussions, logs, screenshots, or fixtures.

## Code and documentation

- Write code comments and KDoc in English. Explain protocol, lifecycle, concurrency, pagination, and security invariants where they are not obvious; avoid comments that merely restate code.
- User-facing UI and project documentation must be available in English and Simplified Chinese. Update both languages when changing behavior or terminology.
- Keep UI business logic in presenters and render immutable state in Compose/SwiftUI. Maintain compact, medium, and expanded layouts and keyboard, pointer, font-scale, dark-mode, and accessibility behavior.
- Keep changes focused. Do not reformat, rename, delete, or regenerate unrelated files, and preserve unrelated working-tree changes.

## Verification

Run the smallest relevant tests while iterating, then the platform checks proportional to the change. Typical commands include:

```shell
./gradlew ktlintCheck
./gradlew :social:discourse:jvmTest :shared:jvmTest
./gradlew :compose-ui:jvmTest :compose-ui:testAndroidHostTest
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :desktopApp:test :desktopApp:createDistributable
```

Apple changes also require the relevant Kotlin/Native framework link tasks and XCTest targets. Do not update screenshot goldens unless the visual change is intentional and reviewed. CI must not depend on production write endpoints or repository secrets.

## Git workflow

- Make a focused commit after each independently verified stage. Use Conventional Commit-style subjects such as `feat(...)`, `fix(...)`, `test(...)`, `docs:`, or `ci:`.
- Push completed stages to `origin main` and wait for the current GitHub Checks to pass before starting the next stage.
- Never amend, squash, rebase, force-push, or otherwise rewrite a commit once it has been published. Add a follow-up `fix(...)` commit for CI or review corrections.
- Do not mirror upstream release tags or branches. Keep `upstream` available for reviewing future Flare changes without rewriting FlareDo history.
