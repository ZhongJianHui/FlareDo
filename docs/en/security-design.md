# Security design

Last reviewed against the source tree: 2026-08-27

This document records the security boundaries and implemented controls of
FlareDo. It is an engineering design description, not a claim of formal
verification or an independent security audit. The current release supports one
active Linux.do account, and every authenticated operation is scoped to that
account and its session generation.

## Security goals

FlareDo is designed to:

- keep Linux.do session cookies, one-use authentication keys, OTP values, and
  CSRF material out of plaintext application persistence and logs;
- prevent a stale request, callback, browser handoff, logout, or realtime event
  from operating on a replacement account;
- keep authenticated REST cookies on the fixed Linux.do HTTPS origin;
- treat callback Intents, server JSON/HTML, response headers, MessageBus frames,
  browser cookies, upload responses, and local vault references as untrusted;
- render forum `cooked` HTML without executing active content or retaining a
  raw-HTML fallback;
- fail closed when a platform vault or restricted browser handoff is unavailable;
- preserve caller cancellation while still completing security-critical local
  cleanup.

The main assets are bearer session cookies, pending RSA private keys, the
temporary User API Key/OTP, the ephemeral MessageBus shared-session key, user
drafts, and the integrity of account-scoped actions.

## Trust boundaries

FlareDo relies on:

- the installed operating system, app sandbox, random source, TLS stack, and
  platform credential service;
- Linux.do and the Discourse/Cloudflare infrastructure serving it;
- the platform system browser for the primary authorization page;
- audited behavior of bundled dependencies such as Ktor, Room, KSoup, Compose,
  SwiftUI/WebKit, Coil, and the native crypto providers.

Data from those boundaries is still validated before entering shared state. A
rooted/jailbroken or otherwise compromised device, a maliciously modified build,
or a compromised trusted server is outside the protection that application-level
checks can provide.

## Fixed-origin transport and Cookie isolation

Normal REST traffic is compiled to `https://linux.do`. A final Ktor request
plugin rejects any request whose scheme, host, effective port, or userinfo escapes
that origin; an explicitly written default HTTPS port remains same-origin.
Automatic redirects are disabled, and Android also disables cleartext
application traffic.

The Cookie jar is intentionally narrower than Ktor's general-purpose cookie
storage:

- foreign-origin reads return no Cookie and foreign writes are rejected;
- stored cookies are normalized to `linux.do`, HTTPS, and a safe absolute path;
- count, per-field, and aggregate sizes are bounded (64 cookies and 64 KiB total
  by default);
- manually supplied `Cookie` headers are removed;
- requests without a session lease send no app-managed Cookie and cannot commit
  `Set-Cookie` values;
- every request carries the cookie revision captured with its session
  generation. A delayed response from an older revision cannot update the new
  account's jar.

The transport uses the platform trust store and standard HTTPS certificate
validation. FlareDo does not implement certificate pinning. This avoids pin
rotation outages but means the operating-system CA trust boundary remains in
scope.

### Optional MessageBus origin

Linux.do can declare a separate HTTPS `long_polling_base_url` in its own bounded
bootstrap HTML. The value must be a lowercase DNS host on the default HTTPS port,
without userinfo, path, query, fragment, IP literal, or `linux.do` masquerading
as a cross-origin endpoint. Cross-origin polling uses a separate client with no
Cookie storage and no redirects. Anonymous polling is credential-free;
authenticated polling adds only a validated ephemeral `X-Shared-Session-Key`.
The key is never persisted, is redacted from diagnostics, and its owned character
buffer is cleared after the generation-bound polling run.

## Authentication design

### Primary User API Key flow

1. The app creates a platform-generated RSA key pair of at least 2048 bits and
   three independent 32-byte random values for the attempt ID, client ID, and
   nonce.
2. The PKCS#8 private key enters the platform vault. Room stores only opaque
   references and timestamps; the authorization URL receives only the SPKI
   public key, client ID, nonce, the fixed `one_time_password` scope, and
   `discourse://auth_redirect`.
3. A callback is accepted only in the ten-minute attempt window. Its URI has an
   exact scheme/authority/query grammar, bounded canonical Base64 fields, no
   path, port, userinfo, fragment, duplicates, or unknown query names.
4. The payload is decrypted with the attempt's RSA/PKCS#1 key and its nonce is
   compared in constant time. Random or undecryptable callbacks do not consume a
   legitimate pending attempt.
5. After the authenticated nonce matches, the attempt is atomically consumed
   before the API Key and OTP can be returned. Replays therefore resolve as
   stale. The private-key vault value is deleted in non-cancellable best-effort
   cleanup.
6. The OTP exchange obtains a fresh root `_t` web-session Cookie. The temporary
   User API Key is not persisted, and `/user-api-key/revoke` is the next network
   request before identity lookup or local session activation.
7. Only an authoritative `/session/current.json` identity and a valid root `_t`
   snapshot can enter encrypted session persistence. Any failed or cancelled
   exchange clears the incomplete guest jar and advances the generation.

RSA PKCS#1 v1.5 is used because it is the Discourse User API Key protocol. The
shared layer does not implement RSA itself; Android, Apple, and JVM platform
providers perform key generation and decryption.

Temporary secret byte arrays are defensively copied and overwritten when their
owner finishes. This is containment, not guaranteed erasure: Kotlin strings,
garbage-collected heap copies, native providers, and crash dumps may retain
copies outside the app's control.

### Restricted-browser fallback

The fallback browser is a deliberately smaller trust surface than a general web
browser:

- top-level navigation is limited to portless `https://linux.do`;
- there is no native JavaScript bridge, download path, arbitrary callback URL,
  or file/content access;
- third-party cookies are disabled or confined to a temporary profile where the
  platform supports it;
- Android disables WebView debugging, file/content access, mixed content,
  geolocation, automatic windows, and cache use, and cancels TLS errors;
- Apple uses a non-persistent WKWebsiteDataStore, so its cookies, cache, local
  storage, service workers, and IndexedDB do not survive that view;
- Windows/Linux use a fresh incognito native WebView profile with devtools and
  clipboard disabled.

The app filters and bounds Linux.do cookies again outside the browser. Fallback
login verifies them with an isolated client before one compare-and-set
activation. Platform browser state is then cleared. A one-use actor receipt
distinguishes “command entered the queue” from “the presenter owns the Cookie
handoff”, preventing Activity/dialog disposal or prompt coroutine cancellation
from erasing cookies after ownership has moved.

The restricted browser can still run JavaScript and load subresources required
by Linux.do and Cloudflare. It is not a sandbox against a compromised Linux.do
origin or browser engine.

#### Android mini password login

Android's fallback password surface keeps the credentials in native Compose
fields and uses one short-lived restricted WebView only for verification and
same-origin requests. The WebView first loads the fixed `https://linux.do/session/csrf`
bootstrap URL. If Cloudflare presents a challenge, the user completes it in that
same WebView; only after the challenge page is gone does the app inject a small
`loadDataWithBaseURL` document whose origin remains `https://linux.do`.

The injected document explicitly renders hCaptcha and performs the complete
sequence in the WebView network stack: `GET /session/csrf`, `POST
/captcha/hcaptcha/create.json` (with the bounded `/hcaptcha/create.json`
fallback), then `POST /session.json`. Third-party cookies are enabled only for
this temporary profile and are cleared with the rest of the restricted browser
state. The JavaScript bridge uses a random per-request nonce, allowlisted phases,
bounded response bodies, bounded captcha tokens, and rejects control characters;
server response text is never exposed to shared state or logs.

After a successful response, the app waits for a non-empty root `_t` Cookie and
then uses the existing isolated session probe and actor-owned
`CompleteRestrictedBrowser` handoff. TOTP retries reuse the same WebView without
re-sending the hCaptcha token, while a single CSRF challenge retry may reuse an
unconsumed token only after the browser bootstrap completes again.

The remember-password option uses the same fail-closed platform vault boundary
as session credentials. Room stores a public identifier and opaque reference;
the bounded identifier/password envelope remains in the vault. Replacement first
publishes the new reference and then removes the previous value. Missing, corrupt,
or mismatched vault material compare-deletes the stale reference. The UI clears
its short-lived password state when the login surface is disposed.

### Cross-device QR sign-in

The QR route is FlareDo-owned and intentionally does not accept another client's
private application URI. Parsing requires exactly `flaredo://qr-login`, version
1, the complete allowlisted query set, no user information, port, path, fragment,
duplicate values, unknown fields, control characters, or oversized secrets.

An authenticated owner creates a fresh RSA attempt and asks Linux.do for the
fixed `one_time_password` scope. The resulting temporary API Key and OTP form a
ten-minute bearer capability. The private key exists only in the platform vault
during creation and is removed before the QR reaches presentation state. The UI
uses a redacted state model, confirms before generation, renders the code locally,
shows its countdown, and retains only one active capability.

On scan, an active session is rejected. The receiver validates expiry before the
normal OTP session exchange, revokes the temporary API Key before identity lookup,
and activates only an authoritative root `_t` Cookie snapshot. Closing,
regenerating, presenter teardown, and desktop application shutdown cancel any
creation work and run revocation in bounded non-cancellable cleanup. Because
remote revocation can fail offline, the code remains security-sensitive until it
expires or Linux.do confirms consumption/revocation.

### Cloudflare challenge handling

A 403 or 429 alone never opens a browser. FlareDo requires the official
`cf-mitigated: challenge` signal or an explicit bounded challenge-platform marker.
The UI receives only the fixed Linux.do origin and an opaque request ID, not a
response body, path, Cookie, or exception.

One authentication exchange has one challenge/replay budget. Before `_t` exists,
the CSRF/OTP portion may be replayed once. After `_t` exists, only the failed
revoke or identity request is retried, so a consumed OTP is not replayed. A second
challenge is surfaced. Challenge handoff may merge proxy state such as
`cf_clearance`, but it explicitly excludes the browser's `_t`, preventing a
different browser account from replacing the OTP-created session. On Android,
the restricted WebView and the Ktor client use the same system WebView User-Agent
so Cloudflare's browser-bound clearance remains valid during the one permitted
replay and the isolated fallback-session probe. The manual challenge wait is
bounded to 180 seconds by default, and one-use browser state is cleared in
`NonCancellable` cleanup.

Realtime catch-up and MessageBus requests use the same one-replay challenge
budget. When a challenge is received, the foreground host presents the fixed
Linux.do browser surface while the original generation-bound request lease is
still active, merges only the bounded challenge-cookie snapshot, and reruns the
complete reconciliation pipeline. A cancelled or second challenge falls back
to the terminal recovery UI without silently switching accounts.

## Android callback and Intent hardening

Safe Intent Redirection is treated as a high-priority security boundary. The
launcher Activity remains exported for launching but consumes no external data;
`DiscourseAuthRedirectActivity` is the only exported data entry point.

The redirect Activity:

- applies the same validator from both `onCreate` and `onNewIntent`, and calls
  `setIntent` on the warm path before processing;
- requires `ACTION_VIEW`, the exact explicit component, a matching package field
  when that optional field is present, BROWSABLE plus only allowlisted categories,
  and the exact `discourse://auth_redirect` route;
- rejects URI grant flags, unsupported flags, ClipData, selector, MIME type,
  identifier, source bounds, path, fragment, and oversized URI input;
- never calls `getExtras` and never unparcels or forwards an extra, including a
  nested Intent;
- reconstructs a fresh Intent from allowlisted scalar routing fields and then
  runs `IntentSanitizer.sanitizeByThrowing` with the exact component, optional
  package policy, action, categories, flags, and data allowlist;
- clears the Activity's retained untrusted Intent, places only the validated URI
  in a one-slot process-memory inbox, and returns through a fixed explicit
  `MainActivity` Intent without copying any payload.

The URI remains sensitive even though its fields are encrypted, so it is never
persisted or logged. Cryptographic nonce/expiry/single-consumption checks run in
the retained authentication presenter after the fast exported-component handoff.

The custom `discourse:` scheme cannot claim exclusive ownership on every
platform. Another app may intercept a callback and cause denial of service.
Because the callback secrets are encrypted to FlareDo's one-use public key and
must match its nonce, interception alone should not grant that app an
authenticated FlareDo session.

## Session generation, CAS, and cancellation

Every login or logout advances a monotonically increasing session generation.
Network work captures the generation, account owner, generation job, and Cookie
revision under the transition mutex. `runForCurrentSession` uses structured
concurrency: the request remains a child of its caller, while a separate bridge
cancels it when its captured generation is replaced. Caller cancellation is
re-thrown unchanged; an internally replaced generation becomes a typed stale
session failure only after confirming the caller is still active.

Destructive or delayed operations use compare-and-set ownership checks:

- expected generation and account ID guard authenticated mutations and logout;
- expected old vault reference guards session checkpoint replacement;
- attempt ID plus observed vault reference guards callback consume/delete;
- Cookie and CSRF stores use revision/CAS updates, so stale response or
  invalidation work cannot overwrite a newer value;
- browser terminal actions require actor-level ownership rather than only queue
  acceptance.

Suspending vault deletion, browser cleanup, rollback, and logout cleanup execute
inside narrowly scoped `NonCancellable` sections. `NonCancellable` is not used to
make normal network work immortal; it is used only after ownership is known so
critical local cleanup reaches its final state. Original caller cancellation is
checked and propagated after cleanup, with secondary cleanup failures attached
without replacing it.

## Credential persistence

Room is a reference and public-metadata index, not a secret store. A complete
Cookie envelope and pending authorization envelopes live behind
`SecureCredentialRef`:

- Android: non-exportable Android Keystore AES-256-GCM key; random record IDs,
  reference-bound associated data, bounded ciphertext in the private no-backup
  directory, and atomic file replacement.
- Apple: Generic Password Keychain items in a fixed service, random account IDs,
  `WhenUnlockedThisDeviceOnly`, and no synchronizable flag.
- Windows: CurrentUser DPAPI with reference-bound optional entropy and bounded
  ciphertext in the user application-data directory.
- Linux: Secret Service via a shell-free `secret-tool` process invocation; the
  secret is supplied on stdin, output is bounded, and the reference is verified
  inside the stored envelope.

If Linux Secret Service is missing or locked, FlareDo uses a process-only store.
It has no snapshot/file API, copies buffers at ownership boundaries, and
best-effort overwrites removed buffers. Per-process reference namespaces make a
stale Room reference fail rather than resolve to another process's credential.
There is no plaintext fallback.

The Room database itself is not application-level encrypted. Public cached
forum data, draft text, public account metadata, opaque references, and the
minimal notification cursor therefore depend on the platform sandbox and device
storage protection.

## CSRF and authenticated mutations

CSRF values are memory-only, size/control-character checked, and fetched under a
single refresh mutex. A login or logout clears the store. A state-changing
request first proves that its generation lease is authenticated, then obtains a
token. Only an explicitly classified CSRF rejection invalidates the exact token
used and permits one refetch/replay. A delayed rejection cannot erase a newer
token; a second failure, ordinary 403, rate limit, or Cloudflare response is not
replayed as CSRF.

Composer actions remain generation/account bound. Drafts contain editable text,
not queued writes or upload bytes. Upload attempts are structured children of
their caller and late progress from a cancelled/retried attempt cannot update the
replacement attempt. Server permission, validation, moderation-queue, and rate
limit results remain authoritative.

## Untrusted HTML and URL handling

Linux.do `cooked` HTML is parsed with KSoup into a platform-neutral typed block
tree. Compose and SwiftUI never receive raw HTML and there is no WebView fallback.
Supported values include text, HTTPS links/images, lists, quotes, code, tables,
and spoilers.

The parser:

- drops complete subtrees for active elements including script, iframe, form,
  SVG, object, embed, audio/video, style, input, and related elements;
- never copies arbitrary attributes and records removal of event attributes,
  `style`, and `srcdoc`;
- accepts only HTTPS absolute URLs or relative URLs resolved against
  `https://linux.do/`;
- rejects protocol-relative URLs, `javascript:`, `data:`, HTTP, userinfo,
  backslashes, control/whitespace octets, encoded dangerous octets, and malformed
  authorities;
- normalizes path dot segments and enforces input-byte, node, depth, text, URL,
  block, list, table, and attribute limits before/while constructing UI data;
- returns an empty safe document on parser failure instead of raw markup.

An external HTTPS URL can still be malicious, track image loads, or present
deceptive content. Sanitization prevents script execution; it does not certify
the truth, reputation, or privacy of a link or image host.

## Realtime failure recovery

MessageBus runs only in a host-owned foreground lifecycle scope. Returning to the
foreground first performs authoritative REST catch-up, then subscribes. Leaving
the foreground or changing topic/session cancels the structured polling child.
Retryable transport/server/serialization errors and 429 responses back off and
honor bounded `Retry-After` information.

An authentication failure, permission/CSRF failure, or HTTP 401/403 is terminal
for the captured generation. The coordinator closes the poll and gates that
generation before requesting recovery, so navigation or foreground changes
cannot create a reconnect loop. Production recovery deletes only the exact
generation's platform-vault reference and in-memory session through a second CAS.
If secure persistence cannot be cleared, polling remains stopped and the global
recovery UI offers owner-checked sign-out/retry instead of silently discarding
state. An explicit Cloudflare challenge requires the separate user-mediated flow
and is not automatically treated as logout.

Events do not directly mutate cached forum models. A validated, monotonic cursor
winner triggers an authoritative REST refresh. Only the notification cursor is
durable; arbitrary channels, payloads, client IDs, cookies, and shared keys are
not persisted.

## Logging and error containment

The Ktor logging plugin is absent. Network exceptions use fixed messages and
allowlisted scalars; bounded response prefixes are inspected for Cloudflare,
CSRF, rate-limit, or validation classification and then discarded. Authentication
objects redact their `toString` output.

The shared diagnostic ring sanitizes before truncation and is bounded by entry
count and aggregate characters. Android redirect audit output contains only two
closed enum names. No URI, Cookie, header, response body, draft, upload byte,
vault reference, or arbitrary exception is deliberately written to those logs.

Redaction is defense in depth, not permission to log secrets. New code must avoid
placing secret material in any message before it reaches a sanitizer.

## Residual risks and non-guarantees

- FlareDo is not end-to-end encryption. Linux.do receives readable content after
  TLS termination and controls server-side retention and authorization.
- There is no certificate pinning. A compromised platform trust store, device,
  browser engine, dependency, Linux.do origin, or distribution build can defeat
  assumptions in this document.
- Vault protection reduces at-rest exposure; it cannot protect bearer cookies
  already in a compromised process or guarantee erasure of immutable/runtime
  copies.
- The local Room database is not encrypted by FlareDo. Draft confidentiality
  relies on the app sandbox and device storage controls.
- Remote HTTPS images reveal network metadata to their hosts. Safe HTML parsing
  cannot prevent tracking pixels, phishing text, malicious destinations, or
  user-authorized navigation.
- The custom callback scheme is not an exclusive verified app link and can be
  intercepted for denial of service.
- Immediate User API Key revocation and remote logout require a working server
  connection. FlareDo clears local material on failure, but cannot guarantee a
  remote server accepted a revocation while offline.
- Secure storage and cleanup APIs can fail. The application favors a visible,
  retryable authenticated/recovery state over claiming success while a restorable
  reference may remain.
- The controls are covered by unit, host, device, and Apple tests, but tests do
  not prove the absence of all vulnerabilities.

## Reporting a vulnerability

Do not include credentials, Cookie values, callback URIs, private posts, or raw
databases in a public issue. Follow [SECURITY.md](../../SECURITY.md) for supported
versions and the private reporting channel. A useful report identifies the
platform, affected revision, security boundary, reproduction with synthetic
data, and expected versus observed behavior.
