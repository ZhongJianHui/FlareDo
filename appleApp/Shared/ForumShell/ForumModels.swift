import Foundation
import SwiftUI

/// Stable root destinations shared with the Kotlin presenter.
///
/// SwiftUI owns only navigation presentation. Selecting a destination is always forwarded to the
/// shared presenter so paging, session replacement, and realtime refresh keep one source of truth.
enum ForumDestination: String, CaseIterable, Identifiable, Sendable {
    case latest
    case hot
    case search
    case notifications
    case profile

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .latest: "forum.latest"
        case .hot: "forum.hot"
        case .search: "forum.search"
        case .notifications: "forum.notifications"
        case .profile: "forum.profile"
        }
    }

    var systemImage: String {
        switch self {
        case .latest: "clock.arrow.circlepath"
        case .hot: "flame.fill"
        case .search: "magnifyingglass"
        case .notifications: "bell.fill"
        case .profile: "person.crop.circle"
        }
    }

    var accent: Color {
        switch self {
        case .latest: ForumPalette.teal
        case .hot: ForumPalette.coral
        case .search: ForumPalette.indigo
        case .notifications: ForumPalette.gold
        case .profile: ForumPalette.teal
        }
    }
}

enum ForumContentSource: Sendable {
    case network
    case staleCache
}

enum ForumFailure: String, Sendable {
    case network
    case authentication
    case permission
    case rateLimited
    case challengeRequired
    case server
    case invalidResponse
    case http

    var message: LocalizedStringKey {
        switch self {
        case .network: "forum.failure.network"
        case .authentication: "forum.failure.authentication"
        case .permission: "forum.failure.permission"
        case .rateLimited: "forum.failure.rate_limited"
        case .challengeRequired: "forum.failure.challenge"
        case .server: "forum.failure.server"
        case .invalidResponse: "forum.failure.invalid_response"
        case .http: "forum.failure.http"
        }
    }
}

struct ForumTopicRowModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let title: String
    let excerpt: String
    let author: String
    let replyCount: Int
    let viewCount: Int
    let category: String?
    let categoryColorHex: String?
    let tags: [String]
    let unread: Bool
}

struct ForumCategoryModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let name: String
    let topicCount: Int
    let colorHex: String?
}

struct ForumTagModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let name: String
    let count: Int
}

enum ForumPostBlockKind: String, Sendable {
    case paragraph
    case quote
    case code
    case image
    case list
    case listItem
    case table
    case tableRow
    case tableCell
    case spoiler
}

enum ForumPostInlineKind: String, Sendable {
    case text
    case link
    case code
    case image
    case spoiler
}

/// A safe inline fragment exported by the Kotlin cooked-HTML parser.
///
/// URLs are still checked at the point of use in SwiftUI. This second check keeps a future mapper
/// regression from turning an arbitrary server string into an executable or local-file link.
struct ForumPostInlineModel: Hashable, Sendable {
    let kind: ForumPostInlineKind
    let text: String
    let url: URL?
    let auxiliaryText: String?
    let children: [ForumPostInlineModel]

    init(
        kind: ForumPostInlineKind,
        text: String,
        url: URL? = nil,
        auxiliaryText: String? = nil,
        children: [ForumPostInlineModel] = []
    ) {
        self.kind = kind
        self.text = text
        self.url = url
        self.auxiliaryText = auxiliaryText
        self.children = children
    }
}

/// A concrete rendering model produced from Kotlin's sanitized `UiArticleBlock` tree.
/// Raw `cooked` HTML never reaches Swift, and links remain separate so URL policy can be enforced.
struct ForumPostBlockModel: Identifiable, Hashable, Sendable {
    let id: String
    let kind: ForumPostBlockKind
    let text: String
    let secondaryText: String?
    let url: URL?
    let linkURL: URL?
    let inlines: [ForumPostInlineModel]
    let children: [ForumPostBlockModel]
    let ordered: Bool
    let startIndex: Int
    let itemIndex: Int?
    let isHeader: Bool
    let columnSpan: Int
    let rowSpan: Int

    init(
        id: String,
        kind: ForumPostBlockKind,
        text: String,
        secondaryText: String? = nil,
        url: URL? = nil,
        linkURL: URL? = nil,
        inlines: [ForumPostInlineModel] = [],
        children: [ForumPostBlockModel] = [],
        ordered: Bool = false,
        startIndex: Int = 1,
        itemIndex: Int? = nil,
        isHeader: Bool = false,
        columnSpan: Int = 1,
        rowSpan: Int = 1
    ) {
        self.id = id
        self.kind = kind
        self.text = text
        self.secondaryText = secondaryText
        self.url = url
        self.linkURL = linkURL
        self.inlines = inlines
        self.children = children
        self.ordered = ordered
        self.startIndex = startIndex
        self.itemIndex = itemIndex
        self.isHeader = isHeader
        self.columnSpan = columnSpan
        self.rowSpan = rowSpan
    }
}

struct ForumPostModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let postNumber: Int
    let author: String
    let blocks: [ForumPostBlockModel]
    let canReply: Bool
    let canEdit: Bool
    let canLike: Bool
    let liked: Bool
    let likeCount: Int
    let canBookmark: Bool
    let bookmarked: Bool
}

struct ForumTopicDocumentModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let title: String
    let tags: [String]
    let posts: [ForumPostModel]
    let canReply: Bool
    let canBookmark: Bool
    let bookmarked: Bool
}

struct ForumSearchHitModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let topicID: Int64
    let postNumber: Int
    let title: String
    let excerpt: String
    let author: String
}

struct ForumNotificationModel: Identifiable, Hashable, Sendable {
    let id: Int64
    let topicID: Int64?
    let postNumber: Int?
    let title: String
    let detail: String
    let unread: Bool
}

struct ForumProfileModel: Hashable, Sendable {
    let username: String
    let displayName: String
    let title: String?
    let location: String?
    let bio: String
    let trustLevel: Int
    let postCount: Int
    let topicCount: Int
    let daysVisited: Int
}

struct ForumActivityModel: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let excerpt: String
    let actor: String?
    let topicID: Int64?
    let postNumber: Int?
}

enum ForumComposerMode: Sendable {
    case closed
    case newTopic
    case reply
    case edit
}

enum ForumComposerSubmitState: Sendable {
    case idle
    case submitting
    case published
    case pendingModeration
    case failed
}

struct ForumComposerModel: Sendable {
    var mode: ForumComposerMode = .closed
    var title = ""
    var body = ""
    var tags = ""
    var canEdit = false
    var canSubmit = false
    var isUploading = false
    var uploadProgress: Double?
    var canCancelUpload = false
    var canRetryUpload = false
    var submitState: ForumComposerSubmitState = .idle
    var failure: ForumFailure?

    var isPresented: Bool { mode != .closed }
}

/// Complete immutable view state consumed by iOS and macOS.
///
/// The production store replaces this value atomically for every Kotlin snapshot. This prevents a
/// session-generation transition from combining an old account's notifications with a new feed.
struct ForumViewState: Sendable {
    var destination: ForumDestination = .latest
    var topics: [ForumTopicRowModel] = []
    var categories: [ForumCategoryModel] = []
    var tags: [ForumTagModel] = []
    var selectedCategoryID: Int64?
    var selectedTagID: Int64?
    var selectedTopicID: Int64?
    var selectedTopic: ForumTopicDocumentModel?
    var searchQuery = ""
    var searchResults: [ForumSearchHitModel] = []
    var profile: ForumProfileModel?
    var profileActivity: [ForumActivityModel] = []
    var notifications: [ForumNotificationModel] = []
    var unreadNotificationCount = 0
    var isAuthenticated = false
    var accountUsername: String?
    var canCreateTopic = false
    var isFeedLoading = true
    var isTopicLoading = false
    var isSearchLoading = false
    var isProfileLoading = false
    var isNotificationsLoading = false
    var isAppending = false
    var isSearchAppending = false
    var isNotificationsAppending = false
    var isProfileActivityAppending = false
    var hasMore = false
    var searchHasMore = false
    var notificationsHasMore = false
    var profileActivityHasMore = false
    var contentSource: ForumContentSource?
    var failure: ForumFailure?
    var topicFailure: ForumFailure?
    var searchFailure: ForumFailure?
    var notificationsFailure: ForumFailure?
    var profileFailure: ForumFailure?
    var composer = ForumComposerModel()
    var authenticationMessage: String?
}

extension ForumViewState {
    /// Self-authored, network-free data used only by previews and platform layout tests.
    static let preview: ForumViewState = {
        let topics = [
            ForumTopicRowModel(
                id: 4_102,
                title: "Kotlin Multiplatform desktop packaging notes",
                excerpt: "A concise checklist for reproducible Linux and Windows artifacts.",
                author: "Alex Chen",
                replyCount: 28,
                viewCount: 1_284,
                category: "Development",
                categoryColorHex: "087F73",
                tags: ["kotlin", "desktop"],
                unread: true
            ),
            ForumTopicRowModel(
                id: 4_101,
                title: "Home network observability without cloud telemetry",
                excerpt: "Comparing small, local-first dashboards and bounded log retention.",
                author: "Mina Zhou",
                replyCount: 16,
                viewCount: 742,
                category: "Resources",
                categoryColorHex: "E05B43",
                tags: ["network", "privacy"],
                unread: false
            ),
            ForumTopicRowModel(
                id: 4_098,
                title: "Share your practical terminal workflow",
                excerpt: "Aliases, multiplexers, and scripts that remain understandable later.",
                author: "Robin Lin",
                replyCount: 63,
                viewCount: 2_906,
                category: "General",
                categoryColorHex: "526A9E",
                tags: ["linux", "workflow"],
                unread: false
            )
        ]
        let topic = ForumTopicDocumentModel(
            id: 4_102,
            title: topics[0].title,
            tags: topics[0].tags,
            posts: [
                ForumPostModel(
                    id: 8_801,
                    postNumber: 1,
                    author: "Alex Chen",
                    blocks: [
                        ForumPostBlockModel(
                            id: "p1",
                            kind: .paragraph,
                            text: "A reliable package starts with a deliberately small runtime and a repeatable build.",
                            secondaryText: nil,
                            url: nil,
                            inlines: [
                                ForumPostInlineModel(
                                    kind: .text,
                                    text: "A reliable package starts with a deliberately small runtime. See the "
                                ),
                                ForumPostInlineModel(
                                    kind: .link,
                                    text: "packaging guide",
                                    url: URL(string: "https://linux.do/t/4102")
                                ),
                                ForumPostInlineModel(kind: .text, text: " for the repeatable build steps.")
                            ]
                        ),
                        ForumPostBlockModel(
                            id: "q1",
                            kind: .quote,
                            text: "Build outputs are evidence, not the source of truth.",
                            secondaryText: "Packaging checklist",
                            url: nil
                        ),
                        ForumPostBlockModel(
                            id: "c1",
                            kind: .code,
                            text: "./gradlew :desktopApp:packageReleaseDistributionForCurrentOS",
                            secondaryText: "shell",
                            url: nil
                        ),
                        ForumPostBlockModel(
                            id: "l1",
                            kind: .list,
                            text: "",
                            children: [
                                ForumPostBlockModel(
                                    id: "l1-i1",
                                    kind: .listItem,
                                    text: "",
                                    children: [
                                        ForumPostBlockModel(
                                            id: "l1-i1-p1",
                                            kind: .paragraph,
                                            text: "Build from a clean checkout."
                                        )
                                    ]
                                ),
                                ForumPostBlockModel(
                                    id: "l1-i2",
                                    kind: .listItem,
                                    text: "",
                                    children: [
                                        ForumPostBlockModel(
                                            id: "l1-i2-p1",
                                            kind: .paragraph,
                                            text: "Verify the unsigned artifact locally."
                                        )
                                    ]
                                )
                            ],
                            ordered: true
                        )
                    ],
                    canReply: true,
                    canEdit: true,
                    canLike: true,
                    liked: false,
                    likeCount: 12,
                    canBookmark: true,
                    bookmarked: false
                )
            ],
            canReply: true,
            canBookmark: true,
            bookmarked: false
        )
        return ForumViewState(
            destination: .latest,
            topics: topics,
            categories: [
                ForumCategoryModel(id: 7, name: "Development", topicCount: 318, colorHex: "087F73"),
                ForumCategoryModel(id: 9, name: "Resources", topicCount: 204, colorHex: "E05B43")
            ],
            tags: [
                ForumTagModel(id: 1, name: "linux", count: 486),
                ForumTagModel(id: 2, name: "kotlin", count: 172)
            ],
            selectedTopicID: topic.id,
            selectedTopic: topic,
            profile: ForumProfileModel(
                username: "preview_member",
                displayName: "Preview Member",
                title: "Member",
                location: nil,
                bio: "Open source, privacy, and practical engineering.",
                trustLevel: 2,
                postCount: 144,
                topicCount: 12,
                daysVisited: 209
            ),
            profileActivity: [
                ForumActivityModel(
                    id: "activity-1",
                    title: "Replied to Kotlin Multiplatform desktop packaging notes",
                    excerpt: "Added a note about verifying the packaged runtime.",
                    actor: "Preview Member",
                    topicID: 4_102,
                    postNumber: 2
                )
            ],
            notifications: [
                ForumNotificationModel(
                    id: 51,
                    topicID: 4_102,
                    postNumber: 2,
                    title: "New reply",
                    detail: "Mina replied to your topic",
                    unread: true
                )
            ],
            unreadNotificationCount: 1,
            isAuthenticated: true,
            accountUsername: "preview_member",
            canCreateTopic: true,
            isFeedLoading: false,
            contentSource: .network
        )
    }()
}

enum ForumPalette {
    static let teal = Color(red: 0x08 / 255, green: 0x7F / 255, blue: 0x73 / 255)
    static let coral = Color(red: 0xE0 / 255, green: 0x5B / 255, blue: 0x43 / 255)
    static let indigo = Color(red: 0x52 / 255, green: 0x6A / 255, blue: 0x9E / 255)
    static let gold = Color(red: 0xC1 / 255, green: 0x86 / 255, blue: 0x24 / 255)
}

private extension Color {
    init?(forumHex: String?) {
        guard let forumHex, forumHex.count == 6, let value = Int(forumHex, radix: 16) else {
            return nil
        }
        self.init(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}

extension ForumCategoryModel {
    var color: Color { Color(forumHex: colorHex) ?? ForumPalette.indigo }
}
