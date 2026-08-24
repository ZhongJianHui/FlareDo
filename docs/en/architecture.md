# Architecture

This document describes the architecture that is implemented in the current source tree. It is
not a roadmap. FlareDo is a single-site client for [Linux.do](https://linux.do/) on Android, iOS,
macOS, Windows, and Linux.

## Architectural constraints

- The only forum origin is the compile-time constant `https://linux.do`. It is not a user setting.
- Kotlin Multiplatform owns forum protocol, mapping, session, persistence, presenter, and realtime
  behavior.
- Android, Windows, and Linux render the shared presentation state with Compose. iOS and macOS use
  native SwiftUI views over a small Kotlin bridge.
- One Linux.do account may be active at a time. The persisted schema still partitions account-owned
  rows by account ID so a later multi-account design does not have to reinterpret existing data.
- Authentication material belongs to platform credential vaults. Room stores opaque vault
  references and public account metadata, never raw cookies, CSRF tokens, OTPs, or private keys.
- Every network operation is bound to an immutable session generation. Login and logout advance the
  generation and cancel work captured from the replaced session.
- Realtime work exists only while a host is foregrounded. REST reconciliation runs before every new
  MessageBus subscription.

## Platform shells

| Product target | Supported floor | UI | Shared-code entry | Platform-owned responsibilities |
| --- | --- | --- | --- | --- |
| Android | API 26 | Jetpack Compose | `:compose-ui` | Activity/process lifecycle, protected redirect Activity, Android Keystore, WebView cookie bridge, Room database path, attachment picker |
| iOS | iOS 17 | SwiftUI | `KotlinSharedUI` from `:apple-shared` | Scene lifecycle, system URL opening, restricted `WKWebView`, Keychain, app-container database path, native attachment picker |
| macOS | macOS 14, arm64 | SwiftUI with `NavigationSplitView` | `KotlinSharedUI` from `:apple-shared` | Window lifecycle and commands, custom-URL delivery, restricted `WKWebView`, Keychain, sandbox container paths |
| Windows | 10.0.17763.0 | Compose Desktop on the JVM | `:compose-ui` through `:desktopApp` | Nucleus/Tao window, single-instance callback broker, CurrentUser DPAPI vault, desktop file picker, AppX packaging |
| Linux | Distribution-dependent desktop runtime | Compose Desktop on the JVM | `:compose-ui` through `:desktopApp` | Nucleus/Tao window, single-instance callback broker, Secret Service vault or session-only fallback, desktop file picker, AppImage packaging |

The Android application ID and both Apple bundle identifiers are
`io.github.zhongjianhui.flaredo`. Desktop packages use the same identity where the target format
supports it. The retained internal Kotlin namespace, `dev.dimension.flare`, avoids a large
non-functional rewrite of the inherited Flare history.

## Module boundaries

The Gradle project intentionally has a small module graph:

| Module | Targets | Responsibility |
| --- | --- | --- |
| `:shared` | Android, JVM, iOS, macOS | Platform-neutral UI models, Room schema/factories, bounded redacted logging, `PlatformSpec` registry, Molecule presenter base |
| `:social:discourse` | Android, JVM, iOS, macOS | Linux.do DTOs and Ktorfit routes, fixed-origin Ktor client, session/authentication, repositories, paging, cooked-HTML parsing, composer, MessageBus, Koin definitions |
| `:compose-ui` | Android, JVM | Adaptive Compose workspace, rich-text renderer, composer, authentication browser effects, semantics and desktop render tests |
| `:app` | Android | Android application/activities, host Koin overrides, redirect inbox and Intent validation, Android screenshot and framework tests |
| `:desktopApp` | JVM | Windows/Linux process and window lifecycle, callback broker, vault selection, database path, desktop packaging |
| `:apple-shared` | iOS, macOS | Static `KotlinSharedUI` frameworks and Swift-friendly snapshots/host facade |
| `appleApp` | iOS, macOS Xcode targets | Native SwiftUI shell, localization, restricted browser policy, Apple lifecycle and XCTest |

`social/discourse` depends on `shared`; platform UIs depend on both. Platform entry modules install
host-specific services through Koin overrides, so common protocol code never imports an Activity,
Keychain API, DPAPI call, or desktop window type.

## Data and presentation flow

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

Transport DTOs do not cross the repository boundary. `DiscourseForumMapper` and its account/search
counterparts produce stable presentation values such as `DiscourseTopicRef`, `DiscourseTopicMeta`,
and `DiscoursePostMeta`. These types deliberately keep a database post ID separate from the visible
post number used by reply graphs and deep links.

`DiscourseForumPresenter`, `DiscourseComposerPresenter`, and
`DiscourseAuthenticationPresenter` expose immutable state produced by Molecule. Their incoming
actions are bounded and lifecycle-owned. A presenter owns no process-global scope: closing it
cancels its actor, in-flight children, realtime work, and pending UI operations. Android retains
presenters across configuration changes in its `ViewModel`; Apple and desktop hosts explicitly
close them with their screen or application lifecycle.

## Session and request lifetime

`DiscourseSessionManager` publishes either `Guest(generation)` or
`Authenticated(generation, accountId, ...)`. `runForCurrentSession` keeps a request as a structured
child of its caller and also installs a cancellation bridge from the captured generation. This gives
both owners authority:

1. navigation or presenter teardown cancels its request;
2. login/logout cancels all requests belonging to the replaced generation;
3. code resumed after cancellation checks the current generation before it can publish, cache, or
   mutate state.

Owner-sensitive delayed operations additionally compare the expected generation and account ID.
Credential-reference checkpoints use compare-and-set semantics so an older callback cannot attach a
vault value to a replacement account. CSRF tokens exist only in memory and are cleared at every
session transition. Logout cleanup that must outlive cancellation runs in a narrowly scoped
`NonCancellable` section.

The primary login path creates a one-use RSA key pair, opens Linux.do's User API Key authorization
page in the system browser, validates the `discourse://auth_redirect` callback and nonce, decrypts
the callback, exchanges its OTP for a normal `_t` web session, and immediately revokes the temporary
User API Key. A restricted fixed-origin WebView cookie handoff is the fallback. Cloudflare handling
is user-mediated and may replay the failed phase at most once.

## Persistence

Room schema version 5 contains five entities arranged into four storage groups:

- forum cache metadata and entries (two entities);
- composer drafts;
- MessageBus cursors;
- secure vault references.

The public forum cache is written and used as an offline fallback only for guest reads. Authenticated
responses bypass that public cache so account-specific unread, permission, like, or bookmark state
cannot leak into anonymous storage. Topic responses are cached only after the complete authoritative
post stream has been validated and mapped.

Drafts are account-partitioned and survive process restart when a persistent Room host is present.
They remain after authentication expires, but FlareDo does not queue an offline submit or action.

Only `/notification/{accountId}` MessageBus cursors are durable. `/latest`, `/new`, topic, and
reaction cursors stay in bounded process memory because foreground REST catch-up reconstructs them.
Topic/reaction cursor pairs share an LRU budget to prevent unbounded browsing history.

The credential implementation is selected by the host:

- Android: encrypted vault backed by Android Keystore;
- iOS/macOS: Apple Keychain;
- Windows: CurrentUser DPAPI;
- Linux: Secret Service. If it is unavailable, authentication is session-only and no plaintext
  fallback is created.

## Cooked content boundary

Linux.do returns rendered post content as `cooked` HTML. `DiscourseCookedHtmlParser` uses KSoup and
maps only supported constructs to `UiArticleBlock` and `UiArticleInline`: text, safe links and
images, lists, quotes, code, tables, and spoilers. Raw HTML is absent from the UI contract.

Scripts, event attributes, unsafe schemes, untrusted data URIs, unsupported frames, malformed table
spans, and out-of-bound structures are discarded or reduced to safe text. Both Compose and SwiftUI
therefore render the same sanitized document rather than implementing separate HTML trust policies.

## Realtime lifecycle

`DiscourseRealtimeCoordinator` derives an allowlisted subscription from foreground state, the
active topic, and the current session generation:

- `/latest` and `/new` for every session;
- `/notification/{numericAccountId}` for an authenticated session;
- `/topic/{topicId}` and `/topic/{topicId}/reactions` for the selected topic.

On foreground entry or subscription change, the presenter first refreshes the relevant REST state.
It then resolves either same-origin polling or the optional cross-origin MessageBus endpoint. The
same-origin transport uses the protected cookie client. A cross-origin transport is cookie-free;
authenticated polling uses only an ephemeral `X-Shared-Session-Key`, whose owned buffer is cleared
when that generation lease ends.

MessageBus events are signals, not authoritative cache payloads. A monotonically advancing cursor's
compare-and-set winner performs a typed REST refresh. Duplicates and delayed events therefore do not
repeat state changes or move a cursor backwards. HTTP 401, 403, explicit CSRF failures, and explicit
Cloudflare challenges stop the generation and enter session recovery. Retryable failures use capped
exponential backoff with jitter and a 180-second maximum.

## Adaptive UI

Compose uses the same semantic panes on phones, tablets/foldables, and desktop:

- compact: bottom navigation and one visible content pane;
- medium: navigation rail with list/detail behavior;
- expanded: navigation, list, article, and supporting taxonomy when the width budget permits it.

Android uses adaptive Navigation 3 scenes. Desktop uses an explicit pane budget because it does not
have the Android scene strategy; supporting content collapses before the article becomes unreadably
narrow. SwiftUI uses `TabView` plus `NavigationStack` for compact iPhone layouts and
`NavigationSplitView` for regular iPad and macOS layouts.

All layouts consume the same presenter permissions. UI controls never infer that an operation is
allowed: create, reply, edit, like, and bookmark affordances require synchronized server-advertised
state and the matching session owner.

## Deliberate non-goals

The current architecture does not provide background push, Discourse Chat, arbitrary Discourse
servers, multiple simultaneously active accounts, offline write queues, app-store publication,
signed packages, or GitHub Releases. Those features require explicit new security and lifecycle
design rather than hidden extension points in the v1 transport.
