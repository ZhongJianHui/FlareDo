# Privacy

Last reviewed against the source tree: 2026-08-24

This notice describes the data behavior of the FlareDo source code and an
unmodified build of that code. A fork, a modified binary, an app store, a system
browser, or an operating-system service can have separate behavior and policies.
FlareDo is an unofficial Linux.do client. The project maintainers do not operate
Linux.do or its network infrastructure.

## Short version

- FlareDo contains no advertising, analytics, tracking, crash-reporting, or
  telemetry SDK. It has no maintainer-operated runtime backend and does not
  automatically upload diagnostics.
- Forum API traffic goes directly to `https://linux.do`. Foreground realtime
  polling may also use an HTTPS MessageBus origin declared by Linux.do itself.
- A post can contain an image hosted by any HTTPS site. Displaying that image
  contacts its host and exposes ordinary network metadata, including the
  requesting IP address, time, and requested URL.
- Session cookies are stored through the platform credential vault. Room stores
  only opaque vault references and public account metadata. CSRF tokens remain
  in process memory.
- Public forum cache entries and account-scoped drafts are local Room data and
  are not encrypted by FlareDo at the application layer. Drafts intentionally
  survive logout and authentication expiry.
- Logout removes the exact app-owned session when local secure storage can be
  cleared safely. It does not delete drafts, public cache entries, server-side
  posts, or the Linux.do account.

## Data sent over the network

### Linux.do forum traffic

FlareDo uses Linux.do's Discourse endpoints over HTTPS. Linux.do and the
infrastructure serving it can receive the information inherent in each request,
such as the IP address, request time, TLS/HTTP metadata, and selected page.
Depending on the action, the request can also contain:

- topic, category, tag, profile, notification, or search identifiers;
- search terms;
- the authenticated account's session cookie;
- a new topic, reply, edit, bookmark, reaction, like, or read-state action;
- a file selected for upload, including its bytes, file name, and media type.

FlareDo does not proxy this traffic through a project-owned service. Data stored
or published on Linux.do is controlled by Linux.do and is subject to Linux.do's
own terms and privacy practices.

### Authentication

The primary login route opens the fixed Linux.do User API Key page in the system
browser. The URL contains a one-use public key plus random client and nonce
values; it does not contain the RSA private key. Browser history, synchronization,
and diagnostics are controlled by the chosen browser and its provider.

The fallback login and a user-mediated Cloudflare challenge use a restricted
embedded browser. The current implementation allows only a portless
`https://linux.do` top-level origin, provides no native JavaScript bridge, rejects
mixed-content or unsafe navigation where the platform permits, and treats its
cookies as a one-use handoff. Credentials typed into that page are processed by
the Linux.do web page. Linux.do and Cloudflare can still receive the normal web
traffic required to serve that page.

Android additionally offers native identifier, password, and TOTP fields. A
short-lived restricted WebView renders hCaptcha and submits the same-origin
Linux.do session requests; the password is not added to logs or Room. Remembering
it is opt-in: the identifier and password envelope is stored in the selected
platform vault, while Room contains only the public identifier and opaque vault
reference. Clearing the saved login removes that reference and attempts to delete
the vault value. The saved-login store never falls back to plaintext if its
platform vault is unavailable.

Cross-device QR sign-in encodes a ten-minute bearer capability containing a
temporary User API Key, one-time password, account name, and expiry. Anyone who
can read an unexpired, unconsumed code may sign in as that account. FlareDo does
not persist or log the QR value. Closing or regenerating a displayed code attempts
immediate remote revocation; scanning exchanges the OTP and revokes the temporary
key before identity lookup. Revocation needs network access, so users should keep
the code private and wait for confirmation before leaving it unattended.

Android delegates live scanning to Google Code Scanner without requesting the
app's `CAMERA` permission. iOS and macOS use AVFoundation after the normal system
camera permission prompt. Apple and desktop image import is user-selected and QR
decoding is local to the app; the selected image is not uploaded by FlareDo.

### Foreground realtime polling

Realtime updates run only while the app is in the foreground. They normally use
Linux.do's same-origin MessageBus. If Linux.do's own bootstrap HTML declares a
separate `long_polling_base_url`, FlareDo may connect to that validated HTTPS DNS
origin:

- anonymous polling sends no cookie or authentication header;
- authenticated polling uses an ephemeral `X-Shared-Session-Key` supplied by
  Linux.do, through a separate cookie-less client;
- the key is not written to Room and its retained character buffer is cleared
  when the generation-bound polling run ends.

The polling host receives normal network metadata and the subscribed channel
names/cursors. MessageBus payloads are treated only as refresh signals; FlareDo
re-fetches authoritative state from Linux.do rather than persisting those
payloads as forum content.

### Remote images and links

Sanitized forum content may include an absolute or relative HTTPS image URL.
Images are loaded when the relevant content is rendered. If an author embeds an
image from another host, that host can observe at least the requesting IP
address, time, and URL; platform image loaders may add ordinary request headers
and may keep their own memory or disk cache. FlareDo does not intentionally give
the rich-text image loader its Linux.do API cookie jar, but a remote host can
still use unique URLs or other web techniques for correlation.

Only HTTPS rich-text URLs are accepted. `http:`, `javascript:`, `data:`,
protocol-relative URLs, embedded credentials, control characters, and dangerous
active markup are rejected. This prevents active-content execution; it does not
make an external image host private or trustworthy. Ordinary links are opened
only after user interaction and then follow the policy of the receiving app or
browser.

## Data kept on the device

### Room database

Production hosts place a Room database in an app-owned directory or container.
FlareDo does not apply application-level encryption to this database. Platform
sandboxing and any device/full-disk encryption therefore remain part of the
protection boundary.

The database can contain:

| Data | Contents and retention |
| --- | --- |
| Public forum cache | Already-sanitized public latest/popular feed pages, categories, tags, and topics. It uses the `anonymous` partition and is bounded to 32 rows by default. Authenticated responses are not written to this public cache. Rows survive restart and logout until pruned, explicitly cleared by code, or app data is removed. |
| Composer drafts | Account ID, target IDs, title, raw body, tags, revision, and update time. Drafts are bounded to 32 per account by default. They survive logout and expired authentication and are removed only by explicit discard, compare-and-delete after confirmed publication, pruning, or app-data removal. They are not an offline send queue and contain no upload bytes. |
| MessageBus cursor | The durable form is limited to an account's notification channel, account ID, and last numeric message ID. Feed/topic/reaction cursors are process-only. Event payloads and shared-session keys are not stored. The notification cursor may remain after logout. |
| Vault reference metadata | Opaque credential references, slot names, public account ID/username, and timestamps. Cookies, CSRF tokens, RSA key bytes, User API Keys, OTP values, and encrypted vault envelopes are forbidden from Room. |

### Authentication material

- The complete validated Linux.do cookie snapshot is serialized as one value in
  the selected platform vault. Room retains only its opaque locator and public
  display metadata.
- CSRF tokens are bounded and memory-only. Login/logout replacement clears them.
- A pending authorization's nonce/client metadata and RSA private key are stored
  as separate vault values so a callback can survive an app lifecycle event.
  The callback is accepted for no more than ten minutes and is consumed once
  after its RSA-authenticated nonce matches. Cancellation, replacement, or
  processing removes the corresponding values. An abandoned expired value may
  remain encrypted until a later cancellation/replacement/expiry cleanup.
- Temporary API key and OTP byte arrays are cleared on completion or failure on
  a best-effort basis. Immutable strings and provider/runtime copies cannot be
  guaranteed to be erased from process memory immediately.
- A remembered Android login is a separate bounded vault envelope containing the
  identifier and password. It is never part of the active session Cookie envelope
  and can be cleared independently.
- A displayed QR value remains a readable bearer capability in UI memory until
  it is closed, regenerated, consumed, expired, or its presenter is torn down.

### Platform vault behavior

| Platform | Persistent session protection |
| --- | --- |
| Android | A non-exportable Android Keystore AES-GCM key protects bounded ciphertext blobs in the app-private no-backup directory. The opaque record reference is authenticated as associated data. |
| iOS and macOS | Generic-password Keychain items use `WhenUnlockedThisDeviceOnly` and are not marked synchronizable, so FlareDo does not request iCloud Keychain migration for them. |
| Windows | CurrentUser DPAPI protects bounded blobs in the user's FlareDo application-data directory, with the opaque reference included as optional entropy. |
| Linux | FlareDo uses Secret Service through `secret-tool`, passing the secret over standard input rather than command arguments. If `secret-tool` or an unlocked Secret Service is unavailable, login is session-only: credentials stay in bounded process memory and FlareDo does not fall back to a plaintext file. The stale process-specific Room reference cannot resolve after restart. |

If the selected desktop vault cannot be initialized, the desktop host uses the
same process-only fail-closed store rather than plaintext persistence.

### Logs and diagnostics

FlareDo's application diagnostic facility is a bounded in-memory ring. It
redacts Cookie/Set-Cookie, authorization headers, known token/nonce/OTP fields,
URL user information, bearer values, and email addresses before retaining an
entry. The Ktor logging plugin is intentionally not installed. Typed network
errors retain fixed categories and small scalar values, not response bodies or
server messages.

The Android redirect Activity can write a closed enum event and a cold/warm
entry-point enum to the local Android system log. It never appends the Intent,
callback URI, query, ciphertext, exception, or Cookie. No diagnostic is uploaded
automatically. If a user voluntarily attaches a screenshot, log, or database to
an issue, the shared material is governed by the service used for that report.

## Logout, deletion, and retention

Logout first attempts Linux.do's remote logout, but remote invalidation is best
effort because the device may be offline. In a non-cancellable local cleanup,
FlareDo then attempts to remove the current vault value, its Room reference, the
in-memory Cookie/CSRF state, and the app-owned restricted-browser Linux.do state.
Every destructive step is guarded by the captured session generation and account
owner so a delayed logout cannot delete a replacement login.

If local vault/reference deletion fails, FlareDo deliberately does not publish a
false guest state that could become logged in again after restart. It keeps the
owner visible and reports a recoverable failure so the user can retry sign-out.

Logout intentionally does not remove:

- account-scoped drafts;
- the public anonymous forum cache;
- the minimal durable notification cursor;
- image-loader or system-browser caches controlled outside the Room cache;
- any post, upload, bookmark, reaction, notification, or account data already
  held by Linux.do.

Use explicit draft discard for unfinished text. Clearing the app's data or
removing the app is the broad local-data removal mechanism, subject to the
platform's own Keychain/backup/uninstall behavior. Do not rely on uninstall as a
server-session revocation mechanism; if a device is lost or logout cannot
complete, use Linux.do's account security controls to revoke sessions.

## User choices

- Guest browsing avoids storing an authenticated web session, but Linux.do and
  remote image hosts still receive ordinary network requests.
- Do not open a topic containing remote images if contacting author-selected
  image hosts is unacceptable.
- Review a file before upload; FlareDo sends the selected bytes to Linux.do only
  after an explicit upload action.
- Sign out to clear the app-owned session, and verify/revoke the server session
  through Linux.do when the device is no longer trusted.
- Because FlareDo is AGPL-3.0 software, the implementation and these claims can
  be audited against the published source.

## Changes and questions

Privacy-relevant behavior changes must update this document in the same change.
For a security weakness, follow [SECURITY.md](../../SECURITY.md) instead of
publishing sensitive details in a public issue. General privacy questions can be
raised in the project's GitHub issue tracker without including credentials,
cookies, private posts, or personal logs.
