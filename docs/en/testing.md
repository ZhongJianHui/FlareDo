# Testing

FlareDo treats protocol correctness, cancellation, credential boundaries, and adaptive layout as
release requirements. Automated tests are deterministic and must not mutate a production Linux.do
account.

The current workflow is `.github/workflows/bootstrap.yml`. It runs on pushes and pull requests to
`main` and by manual dispatch. GitHub Actions receives only `contents: read`; checkout persistence is
disabled and build artifacts are unsigned.

## Test layers

### Shared unit and contract tests

Kotlin common tests cover:

- forward-compatible JSON (`unknown` fields allowed, required identities/types strict);
- topic page 0, search page 1, notification offsets, overlaps, de-duplication, and overflow guards;
- exact `post_stream.stream` batching, ordering, duplicate/missing/unexpected posts, and cross-topic
  rejection;
- error classification, bounded `Retry-After`, Cloudflare evidence, CSRF recognition, HTTP 422
  allowlists, and moderation-queue responses;
- cooked-HTML injection samples, safe URL handling, nesting/size bounds, tables, code, quotes, lists,
  images, and spoilers;
- guest/authenticated permission boundaries, public cache fallback, search/profile/activity,
  notifications, and mark-read behavior;
- composer drafts, create/reply/edit, upload progress/cancel/retry, optimistic rollback, like, and
  bookmark identity;
- session-generation cancellation, cookie revision isolation, CSRF single refresh, stale-owner CAS,
  and fail-closed cleanup;
- strict QR route parsing, expiry and active-session rejection, OTP consumption, temporary-key
  revocation, presenter teardown cleanup, and vault-backed saved-login replacement/removal;
- MessageBus normal/chunked frames, truncation and size limits, per-channel monotonic cursors,
  duplicates, subscription changes, same/cross-origin transports, shared-key erasure, 429 backoff,
  the 180-second retry cap, foreground/background transitions, and 401/403 recovery.

Coroutines use injected dispatchers, clocks, delays, randomness, and `kotlinx-coroutines-test` where
time or scheduling is part of the contract. Cancellation tests assert that the original
`CancellationException` remains cancellation and that cleanup does not launch detached work.

### Process-local end-to-end journeys

`DiscourseFakeServiceJourneyTest` contains five network-free journeys through production presenters,
repositories, `DiscourseDataSource`, the protected Ktor client, and `DefaultDiscourseApi`:

1. guest latest feed to an authoritative topic stream;
2. search result to its exact topic/post number;
3. User API Key callback, OTP exchange, temporary-key revocation, and persisted session activation;
4. reply upload and publish followed by like and bookmark;
5. MessageBus notification, REST refresh, then logout and owner cleanup.

The service is a self-authored Ktor `MockEngine` fixture. It rejects every unrecognized method/path
pair. Synthetic credentials and payloads are redacted and have no production value.

### Platform security tests

- Android host tests exercise RSA, Keystore wrappers, WebView cookie policy, browser handoff
  ownership, password/hCaptcha/TOTP bridge boundaries, saved-login cleanup, cancellation windows,
  and the host Koin graph.
- Android managed-device tests use the installed manifest and real framework to verify the sole
  exported auth Activity, cold/warm callbacks, `IntentSanitizer`, nested Intent opacity, URI grants,
  `ClipData`, selectors, unsupported flags, replay behavior, and the real Android Keystore.
- JVM tests exercise the desktop callback broker, bounded ACK deadlines, single-instance ownership,
  desktop lifecycle cleanup, and vault adapters.
- Linux CI starts an unlocked temporary Secret Service inside a private D-Bus session and requires a
  real vault round trip/removal. It also starts a real Tao WebKitGTK backend under Xvfb and rejects a
  blank or single-color render.
- Windows CI requires a CurrentUser DPAPI round trip/removal and runs the callback broker separately
  from the remaining desktop lifecycle tests for precise diagnostics.
- Kotlin/Native tests cover RSA/Keychain boundaries and Swift bridge snapshots. macOS CI additionally
  performs a real Keychain round trip.
- Apple XCTest checks exact-origin `WKWebView` navigation/cookie policy, cookie-handoff ownership,
  and explicit QR scanner presentation/cancellation.

These tests use local OS facilities only. They do not send credentials or mutations to Linux.do.

### UI and visual tests

Android uses Compose Preview Screenshot Testing `0.0.1-alpha15`. Golden coverage includes the full
geometry grid:

- widths: 400, 610, and 900 dp;
- heights: 400, 500, and 1000 dp.

Additional goldens cover compact/medium/expanded light and dark layouts, 1.5 font scale, cached and
error states, topic detail, search, notifications, and the authenticated profile QR-share action.
Screenshot fixtures contain no live network or account data.

Compose common tests verify window-class and pane-budget boundaries, navigation, paging triggers,
permission policies, wrapping/scrolling, and semantics. Desktop renders the Compose hierarchy into
an offscreen Skiko surface and asserts ordered panes, semantic actions, authentication controls,
QR-share confirmation/display/revocation, realtime recovery, and large-font reachability.

Apple XCTest renders compact iPhone and regular iPad SwiftUI with `ImageRenderer` and macOS with
`NSHostingView`. It checks dimensions, nontransparent pixel variation, split regions, accessibility
text sizes, dark mode, Chinese localization, and the persistent realtime-recovery band. Images are
retained in the `.xcresult` bundle for review.

## Local commands

Use JDK 25. Android builds require the SDK configured by the local Gradle environment. Run all
commands from the repository root.

### Fast shared/JVM gate

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

On Linux, set `FLAREDO_REQUIRE_DESKTOP_VAULT=1` inside an unlocked Secret Service D-Bus session to
make vault unavailability a failure. On Windows, use `gradlew.bat` and the same environment variable
to require CurrentUser DPAPI.

### Android source, screenshots, and host tests

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

Run the real framework/vault boundary on the configured API 30 AOSP ATD image:

```bash
./gradlew \
  :app:flaredoAuthApi30DebugAndroidTest \
  :social:discourse:flaredoApi30AndroidDeviceTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  --stacktrace
```

When an intentional UI change is approved, regenerate references with
`./gradlew :app:updateDebugScreenshotTest`, inspect every changed PNG, and then rerun validation.
Never update goldens merely to silence an unexplained difference.

### Apple Kotlin targets

These tasks require macOS and the matching Apple toolchain:

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

Generate the checked-in Xcode project from `appleApp/project.yml` with XcodeGen and require no diff:

```bash
cd appleApp
xcodegen generate --spec project.yml
git diff --exit-code -- FlareDo.xcodeproj
```

Run the `iOS` scheme against an available iPhone simulator and the `macOS` scheme against the local
arm64 host with code signing disabled. CI is the canonical command line and pins Xcode 26.3 plus a
checksum-verified XcodeGen 2.46.0.

### Desktop packaging

The packages are deliberately unsigned:

```bash
# Linux host
./gradlew :desktopApp:taoWebViewSmoke :desktopApp:packageAppImage --stacktrace

# Windows host, from PowerShell
./gradlew.bat :desktopApp:packageAppX --stacktrace
```

`taoWebViewSmoke` requires a functional WebKitGTK/Tao display backend. See the Linux CI job for the
exact packages and Xvfb environment used by the project.

## CI matrix

| Job | Runner | Required verification | Artifact |
| --- | --- | --- | --- |
| Android source and screenshots | Ubuntu 24.04 | formatting, debug assembly, unit tests, lint, screenshot goldens, KMP Android host tests, API 30 redirect and real Keystore tests | screenshot and instrumentation reports |
| Linux AppImage and Secret Service | Ubuntu 24.04 | JVM suites, required Secret Service round trip, real Tao WebKitGTK smoke, unsigned AppImage | `linux-appimage` |
| Windows AppX and DPAPI | Windows 2025 | JVM suites, required CurrentUser DPAPI, callback broker and lifecycle diagnostics, unsigned AppX | `windows-appx` and test diagnostics |
| Apple KMP, XCTest, and unsigned macOS | macOS 26 | iOS Simulator/macOS Kotlin tests, framework links, generated-project drift, iOS/macOS XCTest, unsigned macOS Release | `.xcresult` bundles |

All external actions are pinned by commit SHA. Dependency caches are read-only outside `main`,
Gradle wrapper validation is enabled, and verification artifacts are retained for seven days.

## Production-network policy

CI must not call a production write endpoint. API, authentication, composer, upload, action,
notification, and MessageBus journeys use `MockEngine` or local fake transports. Native tests may
use Android Keystore, Apple Keychain, Windows DPAPI, Linux Secret Service, loopback sockets, an
emulator, a simulator, or a local WebView, but they do not contain a Linux.do credential.

Real-account write operations are manual smoke tests only. Use a dedicated account, keep the scope
minimal, do not capture secrets or private response bodies, clean up created content when permitted,
and never add credentials, cookies, OTPs, API keys, or vault exports to source, fixtures, logs, or CI
configuration.

## Adding or changing tests

- Prefer a self-authored minimal fixture; do not copy production responses or fluxdo fixtures.
- Reject unknown fake-service routes so endpoint drift fails at the wire boundary.
- Exercise success, malformed success, permissions, cancellation, and stale-generation behavior.
- Use stable semantic IDs and geometry assertions in addition to pixels for UI behavior.
- Keep server text out of assertion failures when it could contain draft/account data.
- Make time, randomness, dispatchers, and platform services injectable when they affect an outcome.
- Add platform tests for behavior implemented in an `actual`, host module, exported component, vault,
  WebView, or packaging layer; a common fake is not sufficient for those boundaries.
- Re-run the relevant local gate before committing, then require every GitHub check to pass before
  starting the next implementation stage.
