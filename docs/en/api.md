# Linux.do API contract

FlareDo is a purpose-built Linux.do client, not a generic Discourse SDK. This document records the
wire behavior and invariants implemented by `:social:discourse` so API changes can be reviewed
against a stable client contract.

## Origin and transport policy

`DiscoursePlatformSpec` defines the sole origin as `https://linux.do`. The generated Ktorfit routes,
authentication requests, rich-content base URL, and same-origin MessageBus transport all derive from
that constant. The ordinary HTTP client rejects another scheme, host, non-default port, user info,
or a redirect before cookies can cross origins. An explicit default HTTPS port (`:443`) remains the
same origin.

The protected Ktor client provides:

- a generation/revision-aware cookie store;
- strict JSON content negotiation;
- no automatic redirects;
- bounded connect, request, and socket timeouts;
- typed, sanitized HTTP error mapping;
- upload progress in the request coroutine;
- no Ktor logging plugin, preventing cookies, CSRF tokens, drafts, or upload bytes from entering
  logs.

Discourse adds fields frequently, so `discourseJson` ignores unknown keys. Known identities and
types remain strict: it does not use lenient parsing or scalar coercion, and a missing required field
fails response decoding.

## Public Kotlin layers

- `DiscoursePlatformSpec` identifies Linux.do to the shared platform registry.
- `DiscourseApi` is the session-aware typed contract used by repositories.
- `DiscourseDataSource` enforces invariants spanning more than one response, especially topic-stream
  identity and order.
- `DiscourseWireApi` is internal. Only `DefaultDiscourseApi` may use generated Ktorfit routes
  directly.

Callers should depend on a repository or `DiscourseDataSource`, not construct URLs or retry unsafe
requests themselves.

## REST endpoints

### Discovery and public reads

| Method and path | `DiscourseApi` operation | Notes |
| --- | --- | --- |
| `GET /site.json` | `site()` | Categories, action types, auth providers, and site capabilities |
| `GET /categories.json` | `categories()` | Category taxonomy |
| `GET /tags.json` | `tags()` | Tag taxonomy |
| `GET /{feed}.json` | `topics(request)` | Root `latest`, `hot`, `top`, `new`, `unread`, or `unseen` feed |
| `GET /c/{parent?}/{slug}/{id}/l/{feed}.json` | `topics(request)` | Category feed; an optional parent slug precedes the category slug |
| `GET /tag/{slug}/l/{feed}.json` | `topics(request)` | Tag feed; additional tags use repeated `tags[]` and `match_all_tags=true` |
| `GET /t/{topicId}.json` | `topic(topicId, trackVisit)` | Topic envelope, initial post window, and authoritative `post_stream.stream` IDs |
| `GET /t/{topicId}/posts.json` | `topicPosts(topicId, postIds)` | Exact repeated `post_ids[]` values copied from the authoritative stream |
| `GET /search.json` | `search(query, page, type)` | Topic, post, user, category, or tag result family |
| `GET /u/{username}.json` | `user(username)` | Public profile plus account-visible fields when authenticated |
| `GET /u/{username}/summary.json` | `userSummary(username)` | Summary counters and top entities |
| `GET /user_actions.json` | `userActions(username, offset, filter)` | Activity uses a row offset rather than the typed search/list page cursors |
| `GET /session/current.json` | `currentSession()` | Current web-session identity probe; a guest response is valid |

Topic-list URLs are constructed with encoded path components and structured query parameters.
Category/tag values are never interpolated as raw URL text. A request accepts at most 20 filter
tags, and route/query tokens reject blanks, controls, and overlong values.

### Authenticated reads

| Method and path | `DiscourseApi` operation | Contract |
| --- | --- | --- |
| `GET /notifications?limit={1..60}` | `notifications(offset, limit)` | Requires an authenticated generation before any network call |
| `GET /u/{username}/bookmarks.json` | `userBookmarks(username, page, limit)` | List page origin is zero; limit is 1 through 20 |
| `GET /bookmarks.json` | `bookmarkedTopics(page)` | Bookmarked topic list |
| `GET /posts/{postId}.json` | `editablePost(postId)` | Private authoritative Markdown used for editing; it is not read from the public cooked cache |

### Mutations

| Method and path | Operation | Important fields or response rule |
| --- | --- | --- |
| `POST /posts.json` | create topic or reply | Exactly one of title/new-topic or `topic_id`/reply; replies may use `reply_to_post_number` |
| `PUT /posts/{postId}.json` | edit post | Sends `post[raw]` and optional `post[edit_reason]`; response post ID must match the route |
| `PUT /notifications/mark-read` | mark one or all read | Optional notification `id`; omission means all |
| `POST /post_actions` | add like/action | Response action summary must identify the requested post and action type |
| `DELETE /post_actions/{postId}` | remove like/action | Accepts authoritative full-post state or HTTP 204 |
| `POST /bookmarks.json` | create topic/post bookmark | Response must contain a positive bookmark ID |
| `DELETE /bookmarks/{bookmarkId}.json` | delete bookmark | Requires the server-provided bookmark ID, not the topic/post ID |
| `POST /uploads.json` | composer upload | Multipart `upload_type=composer`, `synchronous=true`; optional MessageBus `client_id` |
| `DELETE /session/{username}` | logout | Remote logout precedes fail-closed local cleanup |

Every mutation requires an authenticated session before fetching CSRF or contacting its target
endpoint. `DefaultDiscourseApi` gets the token from `GET /session/csrf`, keeps it only in memory, and
replays once only when the response is positively classified as an invalid-CSRF failure. A second
CSRF rejection, ordinary 403, rate limit, or other error is returned to the caller without another
mutation attempt.

Create/edit success is accepted only when the returned post has positive, internally consistent IDs
matching every identity already known by the client. A moderation-queue response becomes
`DiscoursePostEnqueuedException` with only safe numeric references; it is not presented as a
published post. Uploads are copied into an owned, at-most-16-MiB snapshot and their returned URL,
filename, dimensions, size, and extension are bounded before use.

## Pagination contracts

The three cursor families are intentionally different types:

| Resource | Logical first cursor | First wire value | Advance rule |
| --- | ---: | --- | --- |
| Topic, category, tag, and bookmark lists | `DiscourseListPage(0)` | omit `page` | increment by one |
| Search | `DiscourseSearchPage(1)` | omit `page` | increment by one; the forum repository sends `type_filter=post` on every page |
| Notifications | `DiscourseNotificationOffset(0)` | omit `offset` | add rows accepted after ID de-duplication, not the requested limit |

The distinction prevents a shared paging helper from skipping topic page 0 or search page 1.
Overlapping pages are expected at the protocol boundary; repositories/presenters de-duplicate by
stable IDs and advance from accepted results. A short notification page therefore cannot cause a
cursor to jump past unseen rows.

User activity also uses an integer offset starting at 0. It is validated separately because its
response and continuation rules are not the notification contract.

## Authoritative topic streams

A topic detail response may contain only a window of post objects, while
`post_stream.stream` contains the ordered database IDs for the whole topic. FlareDo never derives
subsequent requests from post numbers or assumes that the initial object window is complete.

The loading algorithm is:

1. Validate the topic ID, positive unique stream IDs, initial post uniqueness, and topic ownership.
2. Normalize the stream without changing its first-occurrence order.
3. Split it into batches of at most 20 IDs.
4. Skip a network batch only when all of its posts are already present in the detail response.
5. Otherwise request exactly that batch through repeated `post_ids[]` values.
6. Reject missing, duplicate, unexpected, or cross-topic posts, then restore server stream order.
7. Cache/map the topic only after the complete stream has passed validation.

This makes post IDs the paging and de-duplication boundary while retaining post numbers solely for
visible navigation and reply relationships.

## Authentication endpoints

The primary system-browser flow uses a fixed contract:

1. Build `GET /user-api-key/new` with application name `FlareDo`, scope
   `one_time_password`, a generated client ID and nonce, an RSA SPKI public key, and callback
   `discourse://auth_redirect`.
2. Validate the callback envelope, expiry, nonce, one-time attempt ownership, and encrypted payload.
3. RSA/PKCS#1-decrypt the callback fields and `POST /session/otp/{otp}` with the temporary User API
   Key to establish `_t`.
4. Make `POST /user-api-key/revoke` the next request after `_t` appears.
5. Probe `GET /session/current.json`, persist the cookie jar in the platform vault, and activate the
   new generation.

The callback User API Key is never a long-lived application credential. RSA private material and
pending attempts have bounded lifetimes and one-time consumption semantics.

The fallback restricted browser permits only exact, portless HTTPS navigation on `linux.do` and
bridges a narrowly validated cookie snapshot. Challenge mode requires Cloudflare clearance evidence
and rejects an embedded account session. An explicit Cloudflare challenge can open visible user
handling and replay the failed phase once; a plain 403 or 429 is not automatically treated as a
challenge.

## Error and permission model

No exception includes a request URL, cookie, CSRF token, response body, draft text, upload bytes, or
arbitrary server message. The public hierarchy is:

| Type | Meaning |
| --- | --- |
| `DiscourseAuthenticationException` | HTTP 401 or an authenticated-only call made as guest |
| `DiscoursePermissionException` | ordinary HTTP 403 |
| `DiscourseCsrfException` | explicit invalid-CSRF evidence |
| `DiscourseRateLimitException` | HTTP 429 with optional parsed `Retry-After` seconds |
| `DiscourseCloudflareChallengeException` | explicit challenge evidence on 403/429 |
| `DiscourseValidationException` | HTTP 422 reduced to allowlisted field/reason enums and bounded scalars |
| `DiscourseServerException` | HTTP 5xx, retaining only the status code |
| `DiscourseHttpException` | another non-success status, retaining only the status code |
| `DiscourseNetworkException` | sanitized timeout, connection, or other coarse transport failure; active coroutine cancellation propagates unchanged |
| `DiscourseSerializationException` | request encoding or response decoding failure |
| `DiscoursePostEnqueuedException` | valid moderation-queue acknowledgement rather than publication |

Unknown free-form 422 messages are not retained. `Retry-After` accepts delta-seconds and IMF-fixdate
within bounded parsing. Cloudflare classification requires `cf-mitigated: challenge` or a known
challenge-platform marker; a `Server: cloudflare` header alone is insufficient.

Permissions are server-authored snapshots. Repositories map `can_create_post`, `can_edit`, action
summaries, and bookmark identities into shared UI metadata. The UI requires those synchronized
values and the matching authenticated owner; optimistic state cannot invent permission.

## Cooked HTML contract

`cooked` is parsed with KSoup into the raw-HTML-free `UiArticleBlock`/`UiArticleInline` model. The
supported output is text, safe links, safe images, lists, quotes, code, tables, and spoilers. Script
elements, event attributes, `javascript:`, unsafe data URIs, untrusted frames, and unsupported
attributes never reach either UI toolkit. Relative resources resolve against the fixed Linux.do
origin.

## MessageBus contract

On foreground entry, REST catch-up completes before the first poll. FlareDo subscribes only to
locally constructed `/latest`, `/new`, `/notification/{accountId}`, `/topic/{topicId}`, and
`/topic/{topicId}/reactions` channels. The poll request is
`POST /message-bus/{undashedUuidV4}/poll`; its JSON body contains channel-to-cursor entries and a
monotonic `__seq`.

The transport accepts either one JSON array or Discourse's `text/plain` application-level chunk
frames. It bounds cumulative bytes, frame size, event count, channel count, identifiers, and cursor
values; malformed or truncated frames fail closed. `/__status` may advance only channels in the
requested allowlist.

Same-origin polling uses the protected cookie client. An optional discovered
`long_polling_base_url` must be a distinct default-port HTTPS DNS origin and uses a separate
cookie-free client. Authenticated cross-origin polling sends its ephemeral 32-character shared key
only through `X-Shared-Session-Key`; it is neither persisted nor reused after the generation ends.

Cursor advances are monotonic compare-and-set operations. Only the winner triggers an idempotent
REST refresh, so MessageBus payload data never becomes authoritative local state. Empty successful
polls use a 15-second start-to-start target; event-bearing polls wait at least 100 ms. Retryable
network, rate-limit, server, serialization, and selected transient HTTP failures use exponential
backoff with jitter, honor a bounded `Retry-After`, and never sleep longer than 180 seconds. HTTP
401/403, CSRF rejection, or an explicit challenge terminates polling for that generation and enters
session recovery.

## Changing the contract

An API change should include all of the following in the same pull request:

- a strict DTO or request type with documented optional/default behavior;
- a Ktor `MockEngine` contract test using self-authored, redacted data;
- cursor-origin and overlap coverage when pagination is involved;
- negative tests for missing/wrong-type identity fields and impossible success envelopes;
- updated mapping/cache tests when presentation state changes;
- sanitized error handling that cannot retain arbitrary server content;
- this document updated when a route, retry, permission, or lifecycle rule changes.

CI must never validate a new write endpoint by sending a production mutation. Real-account write
checks remain an explicit manual smoke test outside automated workflows, with no credentials or
captured responses committed to the repository.
