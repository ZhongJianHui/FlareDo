# Linux.do API 契约

FlareDo 是专为 Linux.do 构建的客户端，而不是通用 Discourse SDK。本文档记录
`:social:discourse` 已实现的传输层行为和不变量，使 API 变更可以依据稳定的客户端契约进行评审。

## 源站与传输策略

`DiscoursePlatformSpec` 将唯一源站定义为 `https://linux.do`。生成的 Ktorfit 路由、认证请求、富内容
基准 URL 和同源 MessageBus 传输均派生自此常量。常规 HTTP 客户端会拒绝其他 scheme、host、非默认
port、user info，以及可能让 Cookie 跨源的重定向；显式默认 HTTPS 端口（`:443`）仍属于同源。

受保护的 Ktor 客户端提供：

- 感知会话代际/revision 的 Cookie 存储；
- 严格的 JSON 内容协商；
- 禁止自动重定向；
- 有界的连接、请求和 socket 超时；
- 类型明确且经过净化的 HTTP 错误映射；
- 在请求协程中报告上传进度；
- 不安装 Ktor logging plugin，防止 Cookie、CSRF token、草稿或上传字节进入日志。

Discourse 经常增加字段，因此 `discourseJson` 会忽略未知 key。已知身份字段和类型仍保持严格：它既不
使用宽松解析，也不执行标量强制转换；缺少必填字段会导致响应解码失败。

## 公开 Kotlin 层

- `DiscoursePlatformSpec` 在共享平台注册表中标识 Linux.do。
- `DiscourseApi` 是 Repository 使用的、感知会话的类型化契约。
- `DiscourseDataSource` 执行跨越多个响应的不变量，尤其是 topic stream 的身份与顺序。
- `DiscourseWireApi` 是内部接口；只有 `DefaultDiscourseApi` 可以直接使用生成的 Ktorfit 路由。

调用方应依赖 Repository 或 `DiscourseDataSource`，不应自行构造 URL 或重试非安全请求。

## REST 端点

### 发现与公开读取

| 方法与路径 | `DiscourseApi` 操作 | 说明 |
| --- | --- | --- |
| `GET /site.json` | `site()` | 分类、action 类型、认证提供方和站点能力 |
| `GET /categories.json` | `categories()` | 分类体系 |
| `GET /tags.json` | `tags()` | 标签体系 |
| `GET /{feed}.json` | `topics(request)` | 根 feed：`latest`、`hot`、`top`、`new`、`unread` 或 `unseen` |
| `GET /c/{parent?}/{slug}/{id}/l/{feed}.json` | `topics(request)` | 分类 feed；可选父级 slug 位于分类 slug 之前 |
| `GET /tag/{slug}/l/{feed}.json` | `topics(request)` | 标签 feed；附加标签使用重复的 `tags[]` 和 `match_all_tags=true` |
| `GET /t/{topicId}.json` | `topic(topicId, trackVisit)` | 主题 envelope、初始帖子窗口和权威 `post_stream.stream` ID |
| `GET /t/{topicId}/posts.json` | `topicPosts(topicId, postIds)` | 从权威 stream 原样复制的、重复的 `post_ids[]` 值 |
| `GET /search.json` | `search(query, page, type)` | 主题、帖子、用户、分类或标签结果类别 |
| `GET /u/{username}.json` | `user(username)` | 公开资料，以及认证后账号可见的字段 |
| `GET /u/{username}/summary.json` | `userSummary(username)` | 摘要计数和热门实体 |
| `GET /user_actions.json` | `userActions(username, offset, filter)` | 活动使用行 offset，而不是类型化的搜索/列表 page 游标 |
| `GET /session/current.json` | `currentSession()` | 当前 Web 会话身份探测；访客响应也是合法结果 |

主题列表 URL 使用编码后的路径组件和结构化查询参数构造。分类/标签值绝不会以原始 URL 文本插值。
单次请求最多接受 20 个筛选标签；路由/查询 token 会拒绝空白值、控制字符和超长值。

### 认证后读取

| 方法与路径 | `DiscourseApi` 操作 | 契约 |
| --- | --- | --- |
| `GET /notifications?limit={1..60}` | `notifications(offset, limit)` | 发起任何网络请求前必须处于已认证代际 |
| `GET /u/{username}/bookmarks.json` | `userBookmarks(username, page, limit)` | 列表 page 起点为 0；limit 为 1 至 20 |
| `GET /bookmarks.json` | `bookmarkedTopics(page)` | 已添加书签的主题列表 |
| `GET /posts/{postId}.json` | `editablePost(postId)` | 用于编辑的私有权威 Markdown；不会从公开 cooked 缓存读取 |

### 变更操作

| 方法与路径 | 操作 | 重要字段或响应规则 |
| --- | --- | --- |
| `POST /posts.json` | 创建主题或回复 | 必须且只能二选一：title/新主题，或 `topic_id`/回复；回复可使用 `reply_to_post_number` |
| `PUT /posts/{postId}.json` | 编辑帖子 | 发送 `post[raw]` 和可选的 `post[edit_reason]`；响应 post ID 必须与路由一致 |
| `PUT /notifications/mark-read` | 将一条或全部标为已读 | 可选通知 `id`；省略表示全部 |
| `POST /post_actions` | 添加点赞/action | 响应 action 摘要必须标识所请求的帖子与 action 类型 |
| `DELETE /post_actions/{postId}` | 移除点赞/action | 接受权威完整帖子状态或 HTTP 204 |
| `POST /bookmarks.json` | 创建主题/帖子书签 | 响应必须包含正数 bookmark ID |
| `DELETE /bookmarks/{bookmarkId}.json` | 删除书签 | 必须使用服务端提供的 bookmark ID，而不是 topic/post ID |
| `POST /uploads.json` | 编辑器上传 | Multipart `upload_type=composer`、`synchronous=true`；可选 MessageBus `client_id` |
| `DELETE /session/{username}` | 退出 | 先执行远程退出，再进行失败关闭的本地清理 |

每次变更操作都必须在获取 CSRF 或访问目标端点前确认处于已认证会话。`DefaultDiscourseApi` 从
`GET /session/csrf` 获取 token，并且只保存在内存中；只有当响应被明确识别为 CSRF 无效失败时才会
重放一次。第二次 CSRF 拒绝、普通 403、限流或其他错误会直接返回调用方，不再尝试变更操作。

只有返回的帖子包含正数、内部一致的 ID，并与客户端已知的每个身份一致时，创建/编辑才算成功。
审核队列响应会转换为只包含安全数字引用的 `DiscoursePostEnqueuedException`，不会显示为已发布帖子。
上传内容会复制到由客户端拥有且不超过 16 MiB 的快照中；返回的 URL、文件名、尺寸、大小和扩展名
也会在使用前经过边界检查。

## 分页契约

三种游标类别被有意设计成不同类型：

| 资源 | 逻辑首游标 | 首个传输值 | 推进规则 |
| --- | ---: | --- | --- |
| 主题、分类、标签和书签列表 | `DiscourseListPage(0)` | 省略 `page` | 加一 |
| 搜索 | `DiscourseSearchPage(1)` | 省略 `page` | 加一；论坛 repository 每一页都发送 `type_filter=post` |
| 通知 | `DiscourseNotificationOffset(0)` | 省略 `offset` | 增加按 ID 去重后实际接受的行数，而不是请求的 limit |

这种区分可防止共用分页 helper 跳过主题 page 0 或搜索 page 1。协议边界允许相邻页面重叠；
Repository/Presenter 按稳定 ID 去重，并根据已接受结果推进游标。因此较短的通知页面不会使游标
跨过仍未读取的行。

用户活动同样使用从 0 开始的整数 offset。它会单独验证，因为其响应和续页规则不属于通知契约。

## 权威主题流

主题详情响应可能只包含一批帖子对象，而 `post_stream.stream` 包含整个主题按顺序排列的数据库 ID。
FlareDo 绝不会根据帖子编号推导后续请求，也不会假设初始对象窗口已经完整。

加载算法如下：

1. 验证主题 ID、为正且唯一的 stream ID、初始帖子唯一性和主题归属。
2. 在不改变首次出现顺序的前提下规范化 stream。
3. 将 stream 拆分成每批最多 20 个 ID。
4. 只有当某批全部帖子都已存在于详情响应中时，才跳过该网络批次。
5. 否则通过重复的 `post_ids[]` 值准确请求该批次。
6. 拒绝缺失、重复、意外或跨主题帖子，然后恢复服务端 stream 顺序。
7. 仅在完整 stream 通过验证后缓存/映射主题。

这样会以 post ID 作为分页和去重边界，同时仅将帖子编号用于可见导航与回复关系。

## 认证端点

主要的系统浏览器流程采用固定契约：

1. 构造 `GET /user-api-key/new`，参数包括应用名称 `FlareDo`、scope `one_time_password`、生成的
   client ID 和 nonce、RSA SPKI 公钥，以及回调 `discourse://auth_redirect`。
2. 验证回调 envelope、有效期、nonce、一次性 attempt 所有权和加密 payload。
3. 使用 RSA/PKCS#1 解密回调字段，并携带临时 User API Key 向 `POST /session/otp/{otp}` 发起请求，
   从而建立 `_t`。
4. 在 `_t` 出现后，确保下一次请求是 `POST /user-api-key/revoke`。
5. 探测 `GET /session/current.json`，将 Cookie jar 持久化到平台保险库，并激活新代际。

回调中的 User API Key 永远不会成为长期应用凭据。RSA 私钥材料和待处理 attempt 均有有界生命周期，
并采用单次消费语义。

备用的受限浏览器只允许 `linux.do` 上精确、无端口的 HTTPS 导航，并桥接经过严格验证的 Cookie
快照。Challenge 模式需要 Cloudflare clearance 证据，并拒绝嵌入式账号会话。明确的 Cloudflare
challenge 可以打开可见的用户处理流程，并重放失败阶段一次；普通 403 或 429 不会自动视为
challenge。

## 错误与权限模型

任何异常都不会包含请求 URL、Cookie、CSRF token、响应 body、草稿文本、上传字节或任意服务端消息。
公开异常层次如下：

| 类型 | 含义 |
| --- | --- |
| `DiscourseAuthenticationException` | HTTP 401，或访客调用了仅限认证的操作 |
| `DiscoursePermissionException` | 普通 HTTP 403 |
| `DiscourseCsrfException` | 有明确证据表明 CSRF 无效 |
| `DiscourseRateLimitException` | HTTP 429，可附带解析后的 `Retry-After` 秒数 |
| `DiscourseCloudflareChallengeException` | 403/429 上有明确 challenge 证据 |
| `DiscourseValidationException` | HTTP 422，仅保留 allowlist 中的 field/reason enum 和有界标量 |
| `DiscourseServerException` | HTTP 5xx，只保留状态码 |
| `DiscourseHttpException` | 其他非成功状态，只保留状态码 |
| `DiscourseNetworkException` | 净化后的超时、连接或其他粗粒度传输失败；活动协程的取消会保持原样传播 |
| `DiscourseSerializationException` | 请求编码或响应解码失败 |
| `DiscoursePostEnqueuedException` | 有效的审核队列确认，而不是发布成功 |

未知的自由格式 422 消息不会保留。`Retry-After` 在有界解析范围内接受 delta-seconds 和 IMF-fixdate。
Cloudflare 分类要求存在 `cf-mitigated: challenge` 或已知 challenge 平台标记；只有
`Server: cloudflare` header 并不足够。

权限是服务端生成的快照。Repository 将 `can_create_post`、`can_edit`、action 摘要和 bookmark 身份
映射到共享 UI 元数据。UI 要求这些已同步值与相符的已认证所有者同时存在；乐观状态不能凭空赋予权限。

## Cooked HTML 契约

`cooked` 使用 KSoup 解析为不含原始 HTML 的 `UiArticleBlock`/`UiArticleInline` 模型。支持的输出包括
文本、安全链接、安全图片、列表、引用、代码、表格和 spoiler。script 元素、事件属性、
`javascript:`、不安全 data URI、不受信任的 frame 和不支持的属性都不会到达任一 UI 工具包。
相对资源以固定 Linux.do 源站解析。

## MessageBus 契约

进入前台时，首次轮询前必须完成 REST 补拉。FlareDo 只订阅本地构造的 `/latest`、`/new`、
`/notification/{accountId}`、`/topic/{topicId}` 和 `/topic/{topicId}/reactions` channel。轮询请求为
`POST /message-bus/{undashedUuidV4}/poll`；其 JSON body 包含 channel 到 cursor 的条目和单调递增的
`__seq`。

传输层接受单个 JSON array，或 Discourse 的 `text/plain` 应用层 chunk frame。累计字节数、frame 大小、
事件数量、channel 数量、标识符和 cursor 值都有边界；格式错误或被截断的 frame 会失败关闭。
`/__status` 只能推进所请求 allowlist 中的 channel。

同源轮询使用受保护的 Cookie 客户端。可选的已发现 `long_polling_base_url` 必须是不同源、默认端口的
HTTPS DNS origin，并使用独立且不携带 Cookie 的客户端。已认证的跨源轮询只通过
`X-Shared-Session-Key` 发送临时的 32 字符共享 key；代际结束后既不会持久化，也不会复用该 key。

游标推进采用单调 compare-and-set 操作。只有胜出者会触发幂等 REST 刷新，因此 MessageBus payload
数据永远不会成为权威本地状态。成功的空轮询采用 15 秒的 start-to-start 目标；包含事件的轮询至少
等待 100 ms。可重试的网络、限流、服务端、序列化和特定瞬态 HTTP 失败会使用带抖动的指数退避，
遵守有界的 `Retry-After`，且休眠绝不超过 180 秒。HTTP 401/403、CSRF 拒绝或明确 challenge 会终止
该代际的轮询并进入会话恢复。

## 修改契约

API 变更应在同一个 pull request 中包含以下全部内容：

- 严格的 DTO 或请求类型，并记录 optional/default 行为；
- 使用自行编写、经过脱敏的数据实现 Ktor `MockEngine` 契约测试；
- 涉及分页时覆盖游标起点和重叠情形；
- 针对缺失/错误类型身份字段和不可能成功 envelope 的负向测试；
- 展示状态变化时更新映射/缓存测试；
- 不会保留任意服务端内容的净化错误处理；
- 路由、重试、权限或生命周期规则发生变化时更新本文档。

CI 绝不能通过向生产环境发送变更操作来验证新的写入端点。真实账号写入检查只能作为自动化工作流
之外的显式人工 smoke test，且不能把凭据或捕获的响应提交到仓库。
