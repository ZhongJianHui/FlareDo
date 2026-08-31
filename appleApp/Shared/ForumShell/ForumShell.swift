import Foundation
import SwiftUI
import UniformTypeIdentifiers

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

private enum ForumPresentedSheet: Identifiable {
    case composer
    case restrictedBrowser(ForumRestrictedBrowserRequest)
    case qrScanner
    case qrShare(ForumQrShare)

    var id: String {
        switch self {
        case .composer: "composer"
        case .restrictedBrowser(let request): "browser-\(request.id)"
        case .qrScanner: "qr-scanner"
        case .qrShare(let share): "qr-share-\(share.id)"
        }
    }
}

/// Adaptive Linux.do workspace shared by the iOS and macOS application targets.
///
/// The shell renders only immutable presentation models. Kotlin remains authoritative for paging,
/// authentication, persistence, mutations, and realtime session generations.
struct ForumShell: View {
    @ObservedObject var store: ForumStore
    @Environment(\.scenePhase) private var scenePhase

    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    init(store: ForumStore = ForumStore()) {
        self.store = store
    }

    var body: some View {
        VStack(spacing: 0) {
            if let reason = store.state.realtimeRecoveryReason {
                ForumRealtimeRecoveryBanner(reason: reason, signOut: store.logout)
            }

            Group {
                #if os(iOS)
                if horizontalSizeClass == .regular {
                    ForumSplitWorkspace(store: store)
                } else {
                    ForumCompactWorkspace(store: store)
                }
                #else
                ForumSplitWorkspace(store: store)
                #endif
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .tint(ForumPalette.teal)
        .sheet(item: presentedSheet) { sheet in
            switch sheet {
            case .composer:
                ForumComposerSheet(store: store)
            case .restrictedBrowser(let request):
                RestrictedLinuxDoWebView(
                    title: request.title,
                    initialURL: request.initialURL,
                    mode: request.mode,
                    requestIdentifier: request.id,
                    continueTitle: request.continueTitle,
                    onCancel: { _ in store.cancelRestrictedBrowser(request) },
                    onContinue: { _, handoff in
                        store.completeRestrictedBrowser(request, handoff: handoff)
                    },
                    onFailure: { _, failure in
                        store.reportRestrictedBrowserFailure(request, failure: failure)
                    }
                )
                #if os(macOS)
                .frame(minWidth: 620, idealWidth: 760, minHeight: 560, idealHeight: 700)
                #endif
            case .qrScanner:
                ForumQrScanner(
                    onCode: store.completeQrLogin,
                    onCancel: store.cancelQrLogin
                )
                #if os(macOS)
                .frame(minWidth: 620, idealWidth: 760, minHeight: 560, idealHeight: 700)
                #endif
            case .qrShare(let share):
                ForumQrShareView(
                    share: share,
                    isBusy: store.isQrOperationInProgress,
                    onRegenerate: store.createQrShare,
                    onClose: store.revokeQrShare
                )
            }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            store.setForeground(phase == .active)
        }
        #if os(iOS)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
            store.close()
        }
        #endif
        .onOpenURL { url in
            // Kotlin performs nonce, RSA payload, expiry, and single-consumption checks. This
            // inexpensive host gate prevents unrelated custom URLs from entering that boundary.
            guard url.scheme?.lowercased() == "discourse",
                  url.host?.lowercased() == "auth_redirect" else { return }
            store.completeLogin(redirectURL: url)
        }
        .onChange(of: store.pendingAuthorizationURL) { _, url in
            guard let url else { return }
            openSystemURL(url)
            store.pendingAuthorizationURL = nil
        }
        .accessibilityIdentifier("forum_workspace")
    }

    private func openSystemURL(_ url: URL) {
        #if os(iOS)
        UIApplication.shared.open(url)
        #elseif os(macOS)
        NSWorkspace.shared.open(url)
        #endif
    }

    private var presentedSheet: Binding<ForumPresentedSheet?> {
        Binding(
            get: {
                if let request = store.restrictedBrowserRequest {
                    return .restrictedBrowser(request)
                }
                if store.isQrScannerPresented { return .qrScanner }
                if let share = store.qrShare { return .qrShare(share) }
                return store.state.composer.isPresented ? .composer : nil
            },
            set: { value in
                guard value == nil else { return }
                if let request = store.restrictedBrowserRequest {
                    store.cancelRestrictedBrowser(request)
                } else if store.isQrScannerPresented {
                    store.cancelQrLogin()
                } else if store.qrShare != nil {
                    store.revokeQrShare()
                } else if store.state.composer.isPresented {
                    store.closeComposer()
                }
            }
        )
    }
}

/// A persistent, non-card status band shared by compact, regular, and macOS split layouts.
///
/// Retry and dismissal are intentionally absent: the shared realtime coordinator permanently gates
/// the failed generation. The owner-checked logout operation is the only action that can advance to
/// a clean generation, and the banner remains visible when that asynchronous operation fails.
private struct ForumRealtimeRecoveryBanner: View {
    let reason: ForumRealtimeRecoveryReason
    let signOut: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                message
                Spacer(minLength: 8)
                signOutButton
            }
            VStack(alignment: .leading, spacing: 8) {
                message
                signOutButton
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(ForumPalette.gold.opacity(0.14))
        .overlay(alignment: .bottom) {
            Divider()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("forum_realtime_recovery")
    }

    private var message: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.shield.fill")
                .foregroundStyle(ForumPalette.coral)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("forum.realtime_recovery.title")
                    .font(.callout.weight(.semibold))
                Text(reason.message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var signOutButton: some View {
        Button(role: .destructive, action: signOut) {
            Label("forum.realtime_recovery.sign_out", systemImage: "rectangle.portrait.and.arrow.right")
        }
        .buttonStyle(.bordered)
        .tint(ForumPalette.coral)
        .controlSize(.small)
        .fixedSize(horizontal: true, vertical: false)
        .accessibilityIdentifier("forum_realtime_recovery_logout")
    }
}

#if os(iOS)
private struct ForumCompactWorkspace: View {
    @ObservedObject var store: ForumStore

    var body: some View {
        TabView(
            selection: Binding(
                get: { store.state.destination },
                set: { destination in store.selectDestination(destination) }
            )
        ) {
            ForEach(ForumDestination.allCases) { destination in
                NavigationStack {
                    ForumPrimaryView(destination: destination, store: store)
                        .navigationDestination(
                            isPresented: Binding(
                                get: { store.state.selectedTopicID != nil },
                                set: { if !$0 { store.closeTopic() } }
                            )
                        ) {
                            ForumTopicDetail(store: store, showsCloseButton: false)
                        }
                }
                .tabItem {
                    Label(destination.title, systemImage: destination.systemImage)
                }
                .badge(destination == .notifications ? store.state.unreadNotificationCount : 0)
                .tag(destination)
            }
        }
    }
}
#endif

/// Three-column on macOS and regular iPad: navigation/taxonomy, topic collection, document.
private struct ForumSplitWorkspace: View {
    @ObservedObject var store: ForumStore
    @State private var columnVisibility = NavigationSplitViewVisibility.all

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            ForumSidebar(store: store)
            #if os(macOS)
                .navigationSplitViewColumnWidth(min: 190, ideal: 224, max: 280)
            #endif
        } content: {
            ForumPrimaryView(destination: store.state.destination, store: store)
            #if os(macOS)
                .navigationSplitViewColumnWidth(min: 270, ideal: 330, max: 420)
            #endif
        } detail: {
            ForumTopicDetail(store: store, showsCloseButton: true)
        }
        .navigationSplitViewStyle(.balanced)
    }
}

private struct ForumSidebar: View {
    @ObservedObject var store: ForumStore

    var body: some View {
        List {
            Section {
                ForEach(ForumDestination.allCases) { destination in
                    Button {
                        store.selectDestination(destination)
                    } label: {
                        Label {
                            HStack {
                                Text(destination.title)
                                Spacer(minLength: 6)
                                if destination == .notifications,
                                   store.state.unreadNotificationCount > 0 {
                                    Text("\(min(store.state.unreadNotificationCount, 99))")
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(ForumPalette.coral, in: Capsule())
                                }
                            }
                        } icon: {
                            Image(systemName: destination.systemImage)
                                .foregroundStyle(
                                    store.state.destination == destination
                                        ? destination.accent
                                        : Color.secondary
                                )
                        }
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(
                        store.state.destination == destination
                            ? destination.accent.opacity(0.12)
                            : Color.clear
                    )
                    .accessibilityAddTraits(
                        store.state.destination == destination ? .isSelected : []
                    )
                }
            } header: {
                ForumBrandLabel(productName: store.productName)
                    .padding(.bottom, 8)
                    .textCase(nil)
            }

            if !store.state.categories.isEmpty {
                Section("forum.categories") {
                    ForEach(store.state.categories) { category in
                        Button {
                            store.selectCategory(category)
                        } label: {
                            HStack(spacing: 9) {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(category.color)
                                    .frame(width: 9, height: 18)
                                Text(category.name)
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Text("\(category.topicCount)")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(
                            store.state.selectedCategoryID == category.id ? .isSelected : []
                        )
                    }
                }
            }

            if !store.state.tags.isEmpty {
                Section("forum.tags") {
                    ForEach(store.state.tags.prefix(12)) { tag in
                        Button {
                            store.selectTag(tag)
                        } label: {
                            HStack {
                                Text("#\(tag.name)")
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Text("\(tag.count)")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(
                            store.state.selectedTagID == tag.id ? .isSelected : []
                        )
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle(store.productName)
        .accessibilityIdentifier("forum_supporting_pane")
    }
}

private struct ForumBrandLabel: View {
    let productName: String

    var body: some View {
        HStack(spacing: 10) {
            Image("BrandMark")
                .resizable()
                .interpolation(.high)
                .frame(width: 30, height: 30)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            Text(productName)
                .font(.headline.weight(.semibold))
                .foregroundStyle(.primary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ForumPrimaryView: View {
    let destination: ForumDestination
    @ObservedObject var store: ForumStore

    var body: some View {
        Group {
            switch destination {
            case .latest, .hot:
                ForumFeedView(store: store)
            case .search:
                ForumSearchView(store: store)
            case .notifications:
                ForumNotificationsView(store: store)
            case .profile:
                ForumProfileView(store: store)
            }
        }
        .navigationTitle(destination.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}

private struct ForumFeedView: View {
    @ObservedObject var store: ForumStore

    var body: some View {
        Group {
            if store.state.isFeedLoading && store.state.topics.isEmpty {
                ForumLoadingView()
            } else if let failure = store.state.failure, store.state.topics.isEmpty {
                ForumFailureView(failure: failure, retry: store.refresh)
            } else if store.state.topics.isEmpty {
                ForumEmptyView(title: "forum.empty.topics", systemImage: "text.bubble")
            } else {
                List {
                    if store.state.contentSource == .staleCache {
                        Label("forum.cached", systemImage: "clock.badge.exclamationmark")
                            .font(.caption)
                            .foregroundStyle(ForumPalette.gold)
                    }
                    if let failure = store.state.failure {
                        ForumInlineFailureView(failure: failure, retry: store.refresh)
                    }
                    Section {
                        ForEach(store.state.topics) { topic in
                            Button {
                                store.openTopic(topic.id)
                            } label: {
                                ForumTopicRow(topic: topic, isSelected: store.state.selectedTopicID == topic.id)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("forum_topic_\(topic.id)")
                        }
                    } header: {
                        ForumTaxonomyStrip(store: store)
                            .textCase(nil)
                    }
                    if store.state.hasMore {
                        Button(action: store.loadMore) {
                            HStack {
                                Spacer()
                                if store.state.isAppending {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Label("forum.load_more", systemImage: "chevron.down")
                                }
                                Spacer()
                            }
                        }
                        .disabled(store.state.isAppending)
                    }
                }
                .listStyle(.plain)
                .refreshable { store.refresh() }
                .accessibilityIdentifier("forum_topic_list")
            }
        }
        .toolbar {
            if store.state.canCreateTopic {
                Button(action: store.openNewTopic) {
                    Label("forum.new_topic", systemImage: "square.and.pencil")
                }
                .help(Text("forum.new_topic"))
            }
            Button(action: store.refresh) {
                Label("forum.refresh", systemImage: "arrow.clockwise")
            }
            .disabled(store.state.isFeedLoading)
            .help(Text("forum.refresh"))
        }
    }
}

private struct ForumTaxonomyStrip: View {
    @ObservedObject var store: ForumStore

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(store.state.categories.prefix(6)) { category in
                    Button {
                        store.selectCategory(category)
                    } label: {
                        HStack(spacing: 6) {
                            Circle().fill(category.color).frame(width: 8, height: 8)
                            Text(category.name).lineLimit(1)
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(
                        store.state.selectedCategoryID == category.id
                            ? category.color
                            : Color.secondary
                    )
                }
                ForEach(store.state.tags.prefix(6)) { tag in
                    Button("#\(tag.name)") { store.selectTag(tag) }
                        .buttonStyle(.bordered)
                        .tint(
                            store.state.selectedTagID == tag.id
                                ? ForumPalette.indigo
                                : Color.secondary
                        )
                }
            }
            .padding(.vertical, 4)
        }
        .scrollIndicators(.hidden)
    }
}

private struct ForumTopicRow: View {
    let topic: ForumTopicRowModel
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 0) {
            Rectangle()
                .fill(isSelected ? ForumPalette.teal : Color.clear)
                .frame(width: 3)
            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(topic.title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                    if topic.unread {
                        Circle()
                            .fill(ForumPalette.coral)
                            .frame(width: 8, height: 8)
                            .accessibilityLabel(Text("forum.unread"))
                    }
                }
                Text(topic.excerpt)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                HStack(spacing: 9) {
                    Text(topic.author)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Label("\(topic.replyCount)", systemImage: "bubble.left")
                    Label("\(topic.viewCount)", systemImage: "eye")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .labelStyle(.titleAndIcon)
            }
            .padding(.leading, 12)
            .padding(.vertical, 8)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct ForumTopicDetail: View {
    @ObservedObject var store: ForumStore
    let showsCloseButton: Bool

    var body: some View {
        Group {
            if store.state.isTopicLoading && store.state.selectedTopic == nil {
                ForumLoadingView()
            } else if let failure = store.state.topicFailure,
                      store.state.selectedTopic == nil {
                ForumFailureView(failure: failure, retry: store.retryTopic)
            } else if let topic = store.state.selectedTopic {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(topic.title)
                                .font(.title2.weight(.semibold))
                                .textSelection(.enabled)
                                .accessibilityAddTraits(.isHeader)
                            if !topic.tags.isEmpty {
                                ScrollView(.horizontal) {
                                    HStack(spacing: 6) {
                                        ForEach(topic.tags, id: \.self) { tag in
                                            Text("#\(tag)")
                                                .font(.caption)
                                                .padding(.horizontal, 7)
                                                .padding(.vertical, 4)
                                                .background(.secondary.opacity(0.12))
                                                .clipShape(RoundedRectangle(cornerRadius: 4))
                                        }
                                    }
                                }
                                .scrollIndicators(.hidden)
                            }
                            HStack(spacing: 6) {
                                if topic.canReply {
                                    Button(action: { store.openReply() }) {
                                        Label("forum.reply", systemImage: "arrowshape.turn.up.left")
                                    }
                                }
                                if topic.canBookmark {
                                    Button(action: store.toggleTopicBookmark) {
                                        Label(
                                            topic.bookmarked ? "forum.unbookmark" : "forum.bookmark",
                                            systemImage: topic.bookmarked ? "bookmark.fill" : "bookmark"
                                        )
                                    }
                                }
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 18)

                        Divider()

                        ForEach(topic.posts) { post in
                            ForumPostView(post: post, store: store)
                            Divider().padding(.leading, 20)
                        }
                    }
                    .frame(maxWidth: 840, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .center)
                }
                .background(Color.primary.opacity(0.018))
            } else {
                ForumEmptyView(title: "forum.select_topic", systemImage: "rectangle.split.3x1")
            }
        }
        .navigationTitle("forum.topic")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if showsCloseButton, store.state.selectedTopicID != nil {
                Button(action: store.closeTopic) {
                    Label("forum.close_topic", systemImage: "xmark")
                }
                .help(Text("forum.close_topic"))
            }
        }
        .accessibilityIdentifier("forum_topic_detail")
    }
}

private struct ForumPostView: View {
    let post: ForumPostModel
    @ObservedObject var store: ForumStore

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                ForumAvatar(name: post.author, size: 34)
                VStack(alignment: .leading, spacing: 1) {
                    Text(post.author).font(.headline)
                    Text(post.postNumber == 1 ? "forum.original_post" : "#\(post.postNumber)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            ForEach(post.blocks) { block in
                ForumRichBlock(block: block)
            }
            ForumPostActions(post: post, store: store)
        }
        .padding(20)
    }
}

/// Keeps every action reachable when Dynamic Type makes the compact row wider than its pane.
private struct ForumPostActions: View {
    let post: ForumPostModel
    @ObservedObject var store: ForumStore

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 5) { actionButtons }
                .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: 2) { actionButtons }
        }
        .buttonStyle(.borderless)
    }

    @ViewBuilder
    private var actionButtons: some View {
        if post.canReply {
            ForumPostAction(
                title: "forum.reply",
                systemImage: "arrowshape.turn.up.left",
                action: { store.openReply(postNumber: post.postNumber) }
            )
        }
        if post.canEdit {
            ForumPostAction(
                title: "forum.edit",
                systemImage: "pencil",
                action: { store.openEdit(post: post) }
            )
        }
        if post.canLike {
            ForumPostAction(
                title: post.liked ? "forum.unlike" : "forum.like",
                systemImage: post.liked ? "heart.fill" : "heart",
                suffix: post.likeCount > 0 ? "\(post.likeCount)" : nil,
                action: { store.toggleLike(postID: post.id) }
            )
        }
        if post.canBookmark {
            ForumPostAction(
                title: post.bookmarked ? "forum.unbookmark" : "forum.bookmark",
                systemImage: post.bookmarked ? "bookmark.fill" : "bookmark",
                action: { store.togglePostBookmark(postID: post.id) }
            )
        }
    }
}

private struct ForumPostAction: View {
    let title: LocalizedStringKey
    let systemImage: String
    var suffix: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                Text(title)
                if let suffix {
                    Text(suffix).monospacedDigit()
                }
            }
            .font(.callout)
            .padding(.horizontal, 7)
            .padding(.vertical, 5)
        }
        .help(Text(title))
    }
}

private struct ForumRichBlock: View {
    let block: ForumPostBlockModel

    @ViewBuilder
    var body: some View {
        switch block.kind {
        case .paragraph:
            Text(forumAttributedText(block.text, inlines: block.inlines))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .quote:
            HStack(alignment: .top, spacing: 11) {
                Rectangle().fill(ForumPalette.indigo).frame(width: 3)
                VStack(alignment: .leading, spacing: 5) {
                    if !block.text.isEmpty {
                        Text(block.text).textSelection(.enabled)
                    }
                    ForEach(block.children) { child in
                        ForumRichBlock(block: child)
                    }
                    if let attribution = block.secondaryText {
                        Text(attribution).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.vertical, 5)
        case .code:
            ScrollView(.horizontal) {
                Text(block.text)
                    .font(.system(.callout, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(12)
            }
            .background(.secondary.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        case .image:
            if let url = block.url?.forumSafeImageURL {
                ForumRemoteImage(
                    url: url,
                    linkURL: block.linkURL?.forumSafeWebURL,
                    accessibilityText: block.text
                )
            } else {
                Label("forum.image_unavailable", systemImage: "photo.badge.exclamationmark")
                    .foregroundStyle(.secondary)
            }
        case .list:
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(block.children.enumerated()), id: \.element.id) { index, item in
                    ForumListItem(
                        block: item,
                        ordinal: block.ordered ? block.startIndex + index : nil
                    )
                }
            }
        case .listItem:
            ForumListItem(block: block, ordinal: nil)
        case .table:
            VStack(alignment: .leading, spacing: 6) {
                if let caption = block.secondaryText, !caption.isEmpty {
                    Text(caption).font(.caption).foregroundStyle(.secondary)
                }
                ScrollView(.horizontal) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(block.children) { row in
                            ForumTableRow(block: row)
                        }
                    }
                }
                .scrollIndicators(.visible)
            }
        case .tableRow:
            ForumTableRow(block: block)
        case .tableCell:
            Text(forumAttributedText(block.text, inlines: block.inlines))
                .font(block.isHeader ? .callout.weight(.semibold) : .callout)
                .textSelection(.enabled)
        case .spoiler:
            DisclosureGroup {
                VStack(alignment: .leading, spacing: 8) {
                    if !block.text.isEmpty {
                        Text(block.text).textSelection(.enabled)
                    }
                    ForEach(block.children) { child in
                        ForumRichBlock(block: child)
                    }
                }
                .padding(.top, 6)
            } label: {
                if let summary = block.secondaryText, !summary.isEmpty {
                    Text(summary)
                } else {
                    Text("forum.spoiler")
                }
            }
        }
    }
}

private struct ForumRemoteImage: View {
    let url: URL
    let linkURL: URL?
    let accessibilityText: String

    var body: some View {
        Group {
            if let linkURL {
                Link(destination: linkURL) { image }
            } else {
                image
            }
        }
        .accessibilityLabel(
            Text(accessibilityText.isEmpty ? String(localized: "forum.image") : accessibilityText)
        )
    }

    private var image: some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFit()
            case .failure:
                Label("forum.image_unavailable", systemImage: "photo.badge.exclamationmark")
                    .foregroundStyle(.secondary)
            default:
                ProgressView().frame(minHeight: 90)
            }
        }
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

private struct ForumListItem: View {
    let block: ForumPostBlockModel
    let ordinal: Int?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 9) {
            Group {
                if let ordinal {
                    Text("\(ordinal).")
                        .monospacedDigit()
                } else {
                    Image(systemName: "circle.fill")
                        .font(.system(size: 5))
                }
            }
            .frame(minWidth: 18, alignment: .trailing)
            VStack(alignment: .leading, spacing: 6) {
                if !block.text.isEmpty || !block.inlines.isEmpty {
                    Text(forumAttributedText(block.text, inlines: block.inlines))
                        .textSelection(.enabled)
                }
                ForEach(block.children) { child in
                    ForumRichBlock(block: child)
                }
            }
        }
    }
}

private struct ForumTableRow: View {
    let block: ForumPostBlockModel

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(block.children) { cell in
                Text(forumAttributedText(cell.text, inlines: cell.inlines))
                    .font(cell.isHeader ? .callout.weight(.semibold) : .callout)
                    .textSelection(.enabled)
                    .padding(8)
                    .frame(
                        minWidth: CGFloat(min(max(cell.columnSpan, 1), 4)) * 112,
                        maxWidth: CGFloat(min(max(cell.columnSpan, 1), 4)) * 180,
                        alignment: .topLeading
                    )
                    .background(cell.isHeader ? Color.secondary.opacity(0.10) : Color.clear)
                    .overlay(Rectangle().stroke(Color.secondary.opacity(0.22), lineWidth: 0.5))
            }
        }
    }
}

private func forumAttributedText(
    _ fallback: String,
    inlines: [ForumPostInlineModel]
) -> AttributedString {
    guard !inlines.isEmpty else { return AttributedString(fallback) }
    return inlines.reduce(into: AttributedString()) { result, inline in
        result.append(inline.forumAttributedText)
    }
}

private extension ForumPostInlineModel {
    var forumAttributedText: AttributedString {
        var result: AttributedString
        if children.isEmpty {
            result = AttributedString(text)
        } else {
            result = children.reduce(into: AttributedString()) { value, child in
                value.append(child.forumAttributedText)
            }
        }

        switch kind {
        case .text:
            break
        case .link:
            result.link = url?.forumSafeWebURL
        case .code:
            result.inlinePresentationIntent = .code
        case .image:
            if result.characters.isEmpty {
                result = AttributedString(auxiliaryText ?? String(localized: "forum.image"))
            }
            result.link = url?.forumSafeImageURL
        case .spoiler:
            result.backgroundColor = Color.secondary.opacity(0.16)
        }
        return result
    }
}

private extension URL {
    var forumSafeWebURL: URL? {
        guard let scheme = scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              host != nil,
              user == nil,
              password == nil else { return nil }
        return self
    }

    var forumSafeImageURL: URL? {
        guard scheme?.lowercased() == "https",
              host != nil,
              user == nil,
              password == nil else { return nil }
        return self
    }
}

private struct ForumSearchView: View {
    @ObservedObject var store: ForumStore
    @FocusState private var searchFocused: Bool

    var body: some View {
        Group {
            if store.state.isSearchLoading && store.state.searchResults.isEmpty {
                ForumLoadingView()
            } else if let failure = store.state.searchFailure,
                      store.state.searchResults.isEmpty {
                ForumFailureView(failure: failure, retry: store.retrySearch)
            } else if store.state.searchResults.isEmpty {
                ForumEmptyView(title: "forum.search_empty", systemImage: "magnifyingglass")
            } else {
                List {
                    if let failure = store.state.searchFailure {
                        ForumInlineFailureView(failure: failure, retry: store.retrySearch)
                    }
                    ForEach(store.state.searchResults) { hit in
                        Button {
                            store.openTopic(hit.topicID, postNumber: hit.postNumber)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(hit.title).font(.headline).lineLimit(2)
                                Text(hit.excerpt).foregroundStyle(.secondary).lineLimit(3)
                                Text(hit.author).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    if store.state.searchHasMore {
                        ForumLoadMoreButton(
                            isLoading: store.state.isSearchAppending,
                            action: store.loadMoreSearch
                        )
                    }
                }
                .listStyle(.plain)
            }
        }
        .searchable(
            text: Binding(
                get: { store.state.searchQuery },
                set: { query in store.updateSearchQuery(query) }
            ),
            placement: .automatic,
            prompt: Text("forum.search_prompt")
        )
        .focused($searchFocused)
        .onSubmit(of: .search, store.submitSearch)
        .onAppear { searchFocused = true }
        .accessibilityIdentifier("forum_search_results")
    }
}

private struct ForumNotificationsView: View {
    @ObservedObject var store: ForumStore

    var body: some View {
        Group {
            if !store.state.isAuthenticated {
                ForumAuthenticationRequired(store: store)
            } else if store.state.isNotificationsLoading && store.state.notifications.isEmpty {
                ForumLoadingView()
            } else if let failure = store.state.notificationsFailure,
                      store.state.notifications.isEmpty {
                ForumFailureView(failure: failure, retry: store.retryNotifications)
            } else if store.state.notifications.isEmpty {
                ForumEmptyView(title: "forum.notifications_empty", systemImage: "bell.slash")
            } else {
                List {
                    if let failure = store.state.notificationsFailure {
                        ForumInlineFailureView(failure: failure, retry: store.retryNotifications)
                    }
                    ForEach(store.state.notifications) { notification in
                        Button {
                            store.markNotificationRead(notification.id)
                            if let topicID = notification.topicID {
                                store.openTopic(topicID, postNumber: notification.postNumber)
                            }
                        } label: {
                            HStack(alignment: .top, spacing: 10) {
                                Circle()
                                    .fill(notification.unread ? ForumPalette.coral : Color.clear)
                                    .frame(width: 8, height: 8)
                                    .padding(.top, 6)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(notification.title).font(.headline)
                                    Text(notification.detail).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    if store.state.notificationsHasMore {
                        ForumLoadMoreButton(
                            isLoading: store.state.isNotificationsAppending,
                            action: store.loadMoreNotifications
                        )
                    }
                }
                .listStyle(.plain)
            }
        }
        .toolbar {
            if store.state.isAuthenticated && store.state.unreadNotificationCount > 0 {
                Button(action: { store.markNotificationRead(nil) }) {
                    Label("forum.mark_all_read", systemImage: "checkmark.circle")
                }
            }
        }
        .accessibilityIdentifier("forum_notifications")
    }
}

private struct ForumProfileView: View {
    @ObservedObject var store: ForumStore
    @State private var isConfirmingQrShare = false

    var body: some View {
        Group {
            if !store.state.isAuthenticated {
                ForumAuthenticationRequired(store: store)
            } else if store.state.isProfileLoading && store.state.profile == nil {
                ForumLoadingView()
            } else if let failure = store.state.profileFailure,
                      store.state.profile == nil {
                ForumFailureView(failure: failure, retry: store.retryProfile)
            } else if let profile = store.state.profile {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        HStack(spacing: 14) {
                            ForumAvatar(name: profile.displayName, size: 54)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(profile.displayName).font(.title3.weight(.semibold))
                                Text("@\(profile.username)").foregroundStyle(.secondary)
                                if let title = profile.title { Text(title).font(.caption) }
                            }
                        }
                        if !profile.bio.isEmpty {
                            Text(profile.bio).textSelection(.enabled)
                        }
                        ForumProfileMetrics(profile: profile)
                        Divider()
                        HStack(spacing: 6) {
                            Image(systemName: "checkmark.shield")
                            Text("forum.trust_level")
                            Text("\(profile.trustLevel)").monospacedDigit()
                        }
                        .foregroundStyle(ForumPalette.indigo)
                        if let location = profile.location {
                            Label(location, systemImage: "location")
                        }
                        Divider()
                        Text("forum.activity")
                            .font(.headline)
                            .accessibilityAddTraits(.isHeader)
                        if let failure = store.state.profileFailure {
                            ForumInlineFailureView(failure: failure, retry: store.retryProfile)
                        }
                        if store.state.profileActivity.isEmpty {
                            Text("forum.activity_empty")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(store.state.profileActivity) { activity in
                                ForumActivityRow(activity: activity, store: store)
                                Divider()
                            }
                        }
                        if store.state.profileActivityHasMore {
                            ForumLoadMoreButton(
                                isLoading: store.state.isProfileActivityAppending,
                                action: store.loadMoreProfileActivity
                            )
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: 680, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .center)
                }
            } else {
                ForumEmptyView(title: "forum.profile_empty", systemImage: "person.crop.circle.badge.questionmark")
            }
        }
        .toolbar {
            if store.state.isAuthenticated {
                Button {
                    isConfirmingQrShare = true
                } label: {
                    Label("forum.qr.share_action", systemImage: "qrcode")
                }
                .disabled(store.isQrOperationInProgress)
                Button(role: .destructive, action: store.logout) {
                    Label("forum.logout", systemImage: "rectangle.portrait.and.arrow.right")
                }
            }
        }
        .confirmationDialog(
            "forum.qr.share_confirm_title",
            isPresented: $isConfirmingQrShare,
            titleVisibility: .visible
        ) {
            Button("forum.qr.share_confirm_action") { store.createQrShare() }
            Button("forum.cancel", role: .cancel) {}
        } message: {
            Text("forum.qr.share_warning")
        }
        .accessibilityIdentifier("forum_profile")
    }
}

private struct ForumProfileMetrics: View {
    let profile: ForumProfileModel

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 24) { metrics }
                .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: 12) { metrics }
        }
    }

    @ViewBuilder
    private var metrics: some View {
        ForumProfileMetric(value: profile.postCount, label: "forum.posts")
        ForumProfileMetric(value: profile.topicCount, label: "forum.topics")
        ForumProfileMetric(value: profile.daysVisited, label: "forum.days_visited")
    }
}

private struct ForumActivityRow: View {
    let activity: ForumActivityModel
    @ObservedObject var store: ForumStore

    var body: some View {
        Group {
            if let topicID = activity.topicID {
                Button {
                    store.openTopic(topicID, postNumber: activity.postNumber)
                } label: {
                    content
                }
                .buttonStyle(.plain)
            } else {
                content
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(activity.title).font(.callout.weight(.semibold))
            if !activity.excerpt.isEmpty {
                Text(activity.excerpt).foregroundStyle(.secondary).lineLimit(3)
            }
            if let actor = activity.actor {
                Text(actor).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ForumAuthenticationRequired: View {
    @ObservedObject var store: ForumStore

    var body: some View {
        ContentUnavailableView {
            Label("forum.login_required", systemImage: "person.badge.key")
        } description: {
            Text("forum.login_required_detail")
        } actions: {
            VStack(spacing: 8) {
                if store.state.realtimeRecoveryReason == nil {
                    Button(action: store.beginFallbackLogin) {
                        Label("forum.login_fallback", systemImage: "person.badge.key")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!store.canBeginAuthentication)
                    Button(action: store.beginLogin) {
                        Label("forum.login", systemImage: "safari")
                    }
                    .buttonStyle(.bordered)
                    .disabled(!store.canBeginAuthentication)
                    Button(action: store.beginQrLogin) {
                        Label("forum.qr.scan_action", systemImage: "qrcode.viewfinder")
                    }
                    .buttonStyle(.bordered)
                    .disabled(!store.canBeginAuthentication || store.isQrOperationInProgress)
                }
                if let message = store.state.authenticationMessage {
                    Text(message)
                        .font(.callout)
                        .foregroundStyle(ForumPalette.coral)
                }
            }
        }
    }
}

private struct ForumProfileMetric: View {
    let value: Int
    let label: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(.headline.monospacedDigit())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct ForumAvatar: View {
    let name: String
    let size: CGFloat

    var body: some View {
        Text(name.first.map { String($0).uppercased() } ?? "?")
            .font(.system(size: size * 0.36, weight: .bold))
            .foregroundStyle(ForumPalette.teal)
            .frame(width: size, height: size)
            .background(ForumPalette.teal.opacity(0.14), in: Circle())
            .accessibilityHidden(true)
    }
}

private struct ForumLoadingView: View {
    var body: some View {
        ProgressView("forum.loading")
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ForumEmptyView: View {
    let title: LocalizedStringKey
    let systemImage: String

    var body: some View {
        ContentUnavailableView(title, systemImage: systemImage)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ForumFailureView: View {
    let failure: ForumFailure
    let retry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("forum.load_failed", systemImage: "exclamationmark.triangle")
        } description: {
            Text(failure.message)
        } actions: {
            Button(action: retry) {
                Label("forum.retry", systemImage: "arrow.clockwise")
            }
            .buttonStyle(.borderedProminent)
        }
    }
}

private struct ForumInlineFailureView: View {
    let failure: ForumFailure
    let retry: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                Label(failure.message, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(ForumPalette.coral)
                Spacer(minLength: 8)
                Button("forum.retry", action: retry)
                    .buttonStyle(.bordered)
            }
            VStack(alignment: .leading, spacing: 8) {
                Label(failure.message, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(ForumPalette.coral)
                Button("forum.retry", action: retry)
                    .buttonStyle(.bordered)
            }
        }
        .font(.callout)
        .padding(.vertical, 6)
        .accessibilityIdentifier("forum_inline_failure")
    }
}

private struct ForumLoadMoreButton: View {
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Spacer()
                if isLoading {
                    ProgressView().controlSize(.small)
                } else {
                    Label("forum.load_more", systemImage: "chevron.down")
                }
                Spacer()
            }
            .frame(minHeight: 32)
        }
        .disabled(isLoading)
    }
}

private struct ForumComposerSheet: View {
    @ObservedObject var store: ForumStore
    @State private var title = ""
    @State private var bodyText = ""
    @State private var tags = ""
    @State private var showsFileImporter = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if store.state.composer.mode == .newTopic {
                        TextField("forum.composer.title", text: $title)
                            .textFieldStyle(.roundedBorder)
                            .accessibilityIdentifier("forum_composer_title")
                    }
                    TextEditor(text: $bodyText)
                        .font(.body)
                        .frame(minHeight: 180, idealHeight: 280)
                        .padding(6)
                        .background(.secondary.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .accessibilityIdentifier("forum_composer_body")
                    if store.state.composer.mode == .newTopic {
                        TextField("forum.composer.tags", text: $tags)
                            .textFieldStyle(.roundedBorder)
                            .accessibilityIdentifier("forum_composer_tags")
                    }
                    if store.state.composer.isUploading {
                        ProgressView(
                            value: store.state.composer.uploadProgress,
                            label: { Text("forum.uploading") }
                        )
                    }
                    if store.state.composer.canCancelUpload || store.state.composer.canRetryUpload {
                        HStack(spacing: 8) {
                            if store.state.composer.canCancelUpload {
                                Button(action: store.cancelUpload) {
                                    Label("forum.cancel_upload", systemImage: "xmark.circle")
                                }
                            }
                            if store.state.composer.canRetryUpload {
                                Button(action: store.retryUpload) {
                                    Label("forum.retry_upload", systemImage: "arrow.clockwise")
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                    if let failure = store.state.composer.failure {
                        Label(failure.message, systemImage: "exclamationmark.triangle")
                            .font(.callout)
                            .foregroundStyle(ForumPalette.coral)
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle(composerTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { store.closeComposer() }) {
                        Label("forum.cancel", systemImage: "xmark")
                    }
                    .disabled(store.state.composer.submitState == .submitting)
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button(action: { showsFileImporter = true }) {
                        Label("forum.attach", systemImage: "paperclip")
                    }
                    .disabled(!store.state.composer.canEdit || store.state.composer.isUploading)
                    Button(action: submit) {
                        if store.state.composer.submitState == .submitting {
                            ProgressView().controlSize(.small)
                        } else {
                            Label("forum.publish", systemImage: "paperplane.fill")
                        }
                    }
                    .disabled(!store.state.composer.canSubmit)
                    .keyboardShortcut(.return, modifiers: [.command])
                }
            }
            .onAppear {
                title = store.state.composer.title
                bodyText = store.state.composer.body
                tags = store.state.composer.tags
            }
            .onChange(of: title) { _, _ in persistEditor() }
            .onChange(of: bodyText) { _, _ in persistEditor() }
            .onChange(of: tags) { _, _ in persistEditor() }
            .fileImporter(
                isPresented: $showsFileImporter,
                allowedContentTypes: [.image, .pdf, .plainText],
                allowsMultipleSelection: false
            ) { result in
                guard case .success(let urls) = result, let url = urls.first else { return }
                store.uploadAttachment(url: url)
            }
        }
        .interactiveDismissDisabled(store.state.composer.submitState == .submitting)
        .accessibilityIdentifier("forum_composer")
        #if os(macOS)
        .frame(minWidth: 520, idealWidth: 680, minHeight: 420, idealHeight: 620)
        #endif
    }

    private var composerTitle: LocalizedStringKey {
        switch store.state.composer.mode {
        case .closed: "forum.composer"
        case .newTopic: "forum.new_topic"
        case .reply: "forum.reply"
        case .edit: "forum.edit"
        }
    }

    private func persistEditor() {
        store.updateComposer(title: title, body: bodyText, tags: tags)
    }

    private func submit() {
        persistEditor()
        store.submitComposer()
    }
}
