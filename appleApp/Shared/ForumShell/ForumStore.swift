import Combine
import Foundation
import KotlinSharedUI
import UniformTypeIdentifiers

/// Main-actor adapter between the Kotlin presenters and SwiftUI.
///
/// Kotlin owns network, persistence, session, paging, and mutation state. This store retains only
/// immutable bridge snapshots and publishes one complete Swift value at a time. In particular, an
/// old composer snapshot is never combined with a replacement account's forum snapshot.
@MainActor
final class ForumStore: ObservableObject {
    @Published private(set) var state: ForumViewState
    @Published var pendingAuthorizationURL: URL?
    @Published private(set) var restrictedBrowserRequest: ForumRestrictedBrowserRequest?
    @Published private(set) var isRestoringSession = false

    let productName: String

    private let isFixture: Bool
    private var host: AppleForumHost?
    private var forumObservation: AppleForumObservation?
    private var composerObservation: AppleForumObservation?
    private var manualChallengeObservation: AppleForumObservation?
    private var rawForumSnapshot: AppleForumSnapshot?
    private var rawComposerSnapshot: AppleComposerSnapshot?
    private var operations: [UUID: AppleForumObservation] = [:]
    private var pendingOperationIDs: Set<UUID> = []
    private var pendingAttachmentRead: PendingAttachmentRead?
    private var checkpointOperationID: UUID?
    private var cookieHandoffs: [UUID: PendingCookieHandoff] = [:]
    private var authenticationMessage: String?
    private var localComposerFailure: OwnedComposerFailure?
    private var pendingLoginRedirectURL: URL?
    private var isForeground = true
    private var checkpointRequested = false
    private var isClosed = false
    private var didFinishClosing = false
    private var closeCompletions: [() -> Void] = []

    init() {
        productName = AppleSharedHelper.shared.productName()
        state = ForumViewState()
        isFixture = false
        // HTTPCookieStorage is only a one-use Swift-to-Kotlin handoff buffer. Clear anything left
        // by a prior process termination before restoring the encrypted shared session.
        RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies()
        connectToSharedHost()
    }

    init(fixture: ForumViewState) {
        productName = "FlareDo"
        state = fixture
        isFixture = true
    }

    // Keep this explicitly nonisolated. Letting Swift synthesize an actor-isolated deinit invokes
    // the broken iOS 17 deinit-on-executor back-deployment thunk. Real resources are owned by close().
    deinit {}

    /// Idempotently releases every process-owned bridge resource on the main actor.
    ///
    /// macOS waits for the completion before replying to `applicationShouldTerminate`; iOS calls
    /// the same path opportunistically from `willTerminate`. Swift 6's isolated-deinit
    /// back-deployment path is not reliable on iOS 17, so deinit intentionally owns no resources.
    func close(completion: (() -> Void)? = nil) {
        if let completion {
            if didFinishClosing {
                completion()
                return
            }
            closeCompletions.append(completion)
        }
        if isFixture {
            isClosed = true
            finishClosing()
            return
        }
        guard !isClosed else { return }
        isClosed = true
        isRestoringSession = false
        checkpointRequested = false
        pendingLoginRedirectURL = nil
        forumObservation?.cancel()
        composerObservation?.cancel()
        manualChallengeObservation?.cancel()
        forumObservation = nil
        composerObservation = nil
        manualChallengeObservation = nil
        operations.values.forEach { $0.cancel() }
        operations.removeAll()
        pendingOperationIDs.removeAll()
        checkpointOperationID = nil
        cancelPendingAttachmentRead()
        cookieHandoffs.values.forEach { $0.token.finish(clearCookies: true) }
        cookieHandoffs.removeAll()
        RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies()
        restrictedBrowserRequest = nil
        pendingAuthorizationURL = nil
        rawForumSnapshot = nil
        rawComposerSnapshot = nil
        localComposerFailure = nil
        let closingHost = host
        host = nil
        guard let closingHost else {
            finishClosing()
            return
        }
        closingHost.close { [weak self] _ in
            // Keep this hop even though the facade promises a Main callback. It preserves actor
            // safety for the already-closed fast path and future Kotlin/Native export changes.
            Task { @MainActor [weak self] in
                self?.finishClosing()
            }
        }
    }

    private func finishClosing() {
        guard !didFinishClosing else { return }
        didFinishClosing = true
        let completions = closeCompletions
        closeCompletions.removeAll()
        completions.forEach { $0() }
    }

    func setForeground(_ isForeground: Bool) {
        guard !isFixture, !isClosed, let host else { return }
        let wasForeground = self.isForeground
        self.isForeground = isForeground
        host.setForeground(isForeground: isForeground)
        if isForeground {
            checkpointRequested = false
        } else if !wasForeground {
            return
        } else if isRestoringSession {
            checkpointRequested = true
        } else if checkpointOperationID != nil {
            // Queue one follow-up when a new active cycle produced mutable state while the previous
            // checkpoint was still running.
            checkpointRequested = true
        } else {
            checkpointSession(using: host)
        }
    }

    func selectDestination(_ destination: ForumDestination) {
        if isFixture {
            state.destination = destination
            if destination != .latest && destination != .hot {
                state.selectedTopicID = nil
                state.selectedTopic = nil
            }
            return
        }
        _ = host?.selectDestination(destination: destination.appleValue)
    }

    func refresh() {
        guard !isFixture else { return }
        _ = host?.refresh()
    }

    func loadMore() {
        guard !isFixture else { return }
        _ = host?.loadNextPage()
    }

    func loadMoreSearch() {
        guard !isFixture else { return }
        _ = host?.loadNextSearchPage()
    }

    func loadMoreNotifications() {
        guard !isFixture else { return }
        _ = host?.loadNextNotificationsPage()
    }

    func loadMoreProfileActivity() {
        guard !isFixture else { return }
        _ = host?.loadNextActivityPage()
    }

    func retryTopic() {
        guard !isFixture else { return }
        _ = host?.retryTopic()
    }

    func retrySearch() {
        guard !isFixture else { return }
        _ = host?.retrySearch()
    }

    func retryNotifications() {
        guard !isFixture else { return }
        _ = host?.retryNotifications()
    }

    func retryProfile() {
        guard !isFixture else { return }
        _ = host?.retryProfile()
    }

    func selectCategory(_ category: ForumCategoryModel) {
        if isFixture {
            state.selectedCategoryID = category.id
            state.selectedTagID = nil
            return
        }
        guard
            let value = rawForumSnapshot?.categories.first(where: { $0.id == category.id }),
            let host
        else { return }
        _ = host.selectCategory(
            id: value.id,
            slug: value.slug,
            parentSlug: value.parentSlug,
            name: value.name
        )
    }

    func selectTag(_ tag: ForumTagModel) {
        if isFixture {
            state.selectedTagID = tag.id
            state.selectedCategoryID = nil
            return
        }
        guard
            let value = rawForumSnapshot?.tags.first(where: { $0.id == tag.id }),
            let host
        else { return }
        _ = host.selectTag(name: value.name, slug: value.slug)
    }

    func openTopic(_ topicID: Int64, postNumber: Int? = nil) {
        if isFixture {
            state.selectedTopicID = topicID
            return
        }
        _ = host?.openTopic(topicId: topicID, postNumber: postNumber.kotlinInt)
    }

    func closeTopic() {
        if isFixture {
            state.selectedTopicID = nil
            state.selectedTopic = nil
            return
        }
        _ = host?.closeTopic()
    }

    func updateSearchQuery(_ query: String) {
        if isFixture {
            state.searchQuery = query
            return
        }
        _ = host?.updateSearchQuery(query: query)
    }

    func submitSearch() {
        guard !isFixture else { return }
        _ = host?.submitSearch()
    }

    func markNotificationRead(_ notificationID: Int64?) {
        if isFixture {
            state.notifications = state.notifications.map { item in
                guard notificationID == nil || notificationID == item.id else { return item }
                return ForumNotificationModel(
                    id: item.id,
                    topicID: item.topicID,
                    postNumber: item.postNumber,
                    title: item.title,
                    detail: item.detail,
                    unread: false
                )
            }
            state.unreadNotificationCount = state.notifications.filter(\.unread).count
            return
        }
        guard let owner = forumOwner(), let host else { return }
        _ = host.markNotificationsRead(
            notificationId: notificationID.kotlinLong,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID
        )
    }

    func beginLogin() {
        guard !isFixture, !isClosed, !isRestoringSession, let host else { return }
        authenticationMessage = nil
        let operationID = beginOperation()
        let observation = host.beginAuthorization { [weak self] result in
            guard let self else { return }
            self.finishOperation(operationID)
            if let rawURL = result.url,
               let url = URL(string: rawURL),
               url.scheme?.lowercased() == "https",
               url.host?.lowercased() == "linux.do",
               url.user == nil,
               url.password == nil {
                self.pendingAuthorizationURL = url
            } else {
                self.authenticationMessage = self.message(for: result.error)
            }
            self.publishStateIfPossible()
        }
        retain(observation, for: operationID)
    }

    /// Opens the fixed-origin fallback only after deleting any unfinished User API Key attempt.
    func beginFallbackLogin() {
        guard !isFixture,
              !isClosed,
              !isRestoringSession,
              !state.isAuthenticated,
              restrictedBrowserRequest == nil,
              let host else {
            return
        }
        authenticationMessage = nil
        publishStateIfPossible()
        let operationID = beginOperation()
        let observation = host.cancelAuthorization { [weak self] result in
            guard let self else { return }
            self.finishOperation(operationID)
            guard !self.state.isAuthenticated else { return }
            if let error = result.error,
               error == AppleForumOperationError.hostClosed {
                self.authenticationMessage = self.message(for: error)
            } else {
                // `false` without an error simply means there was no pending browser attempt.
                self.restrictedBrowserRequest = ForumRestrictedBrowserRequest(kind: .fallbackLogin)
            }
            self.publishStateIfPossible()
        }
        retain(observation, for: operationID)
    }

    /// Cancels only the currently displayed browser request and, for a challenge, its exact id.
    func cancelRestrictedBrowser(_ request: ForumRestrictedBrowserRequest) {
        guard restrictedBrowserRequest?.id == request.id else { return }
        restrictedBrowserRequest = nil
        RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies()

        guard !isRestoringSession,
              case .manualChallenge(let requestID) = request.kind,
              let host else { return }
        let operationID = beginOperation()
        let observation = host.resolveManualChallenge(
            requestId: requestID,
            completed: false
        ) { [weak self] result in
            guard let self else { return }
            self.finishOperation(operationID)
            if !result.value {
                self.authenticationMessage = self.message(for: result.error)
                self.publishStateIfPossible()
            }
        }
        retain(observation, for: operationID)
    }

    /// Starts the Kotlin consumer while retaining exclusive ownership of the one-use Cookie buffer.
    func completeRestrictedBrowser(
        _ request: ForumRestrictedBrowserRequest,
        handoff: RestrictedLinuxDoCookieHandoffToken
    ) {
        guard !isClosed,
              !isRestoringSession,
              restrictedBrowserRequest?.id == request.id,
              let host else {
            handoff.finish(clearCookies: true)
            return
        }

        let operationID = beginOperation()
        cookieHandoffs[operationID] = PendingCookieHandoff(request: request, token: handoff)
        switch request.kind {
        case .fallbackLogin:
            restrictedBrowserRequest = nil
            let observation = host.completeWebSession { [weak self] result in
                guard let self else { return }
                self.finishCookieHandoff(operationID, clearCookies: true)
                self.finishOperation(operationID)
                self.authenticationMessage = self.message(for: result)
                self.publishStateIfPossible()
            }
            retain(observation, for: operationID)

        case .manualChallenge(let requestID):
            let observation = host.resolveManualChallenge(
                requestId: requestID,
                completed: true
            ) { [weak self] result in
                guard let self else { return }
                // On success the request-bound Kotlin handler snapshots and clears the bridge.
                // A stale/failed resolution has no consumer, so Swift clears it immediately.
                self.finishCookieHandoff(operationID, clearCookies: !result.value)
                self.finishOperation(operationID)
                if !result.value {
                    self.authenticationMessage = self.message(for: result.error)
                    self.publishStateIfPossible()
                }
            }
            retain(observation, for: operationID)
        }
    }

    func reportRestrictedBrowserFailure(
        _ request: ForumRestrictedBrowserRequest,
        failure: RestrictedLinuxDoWebFailure
    ) {
        guard restrictedBrowserRequest?.id == request.id else { return }
        switch failure {
        case .pageLoadFailed, .webContentProcessTerminated:
            authenticationMessage = String(localized: "forum.failure.network")
        case .invalidInitialURL, .blockedTopLevelNavigation:
            authenticationMessage = String(localized: "forum.failure.invalid_response")
        case .cookieSynchronizationFailed:
            authenticationMessage = String(localized: "forum.failure.authentication")
        }
        publishStateIfPossible()
    }

    /// Passes the untouched absolute callback string to Kotlin's one-use RSA/nonce validator.
    func completeLogin(redirectURL: URL) {
        guard !isFixture, !isClosed else { return }
        guard !isRestoringSession else {
            // A cold-start callback can arrive before the encrypted session restore completes.
            // Preserve only the first callback and submit it after restore so the two session
            // generations can never race each other.
            if pendingLoginRedirectURL == nil {
                pendingLoginRedirectURL = redirectURL
            }
            return
        }
        completeLoginAfterRestore(redirectURL)
    }

    private func completeLoginAfterRestore(_ redirectURL: URL) {
        guard !isClosed, let host else { return }
        let operationID = beginOperation()
        let observation = host.completeAuthorization(rawUri: redirectURL.absoluteString) { [weak self] result in
            guard let self else { return }
            self.finishOperation(operationID)
            self.authenticationMessage = self.message(for: result)
            self.publishStateIfPossible()
        }
        retain(observation, for: operationID)
    }

    func logout() {
        guard !isFixture else {
            state.isAuthenticated = false
            state.accountUsername = nil
            return
        }
        guard let owner = forumOwner(), let host else { return }
        let operationID = beginOperation()
        let observation = host.logout(
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID
        ) { [weak self] result in
            guard let self else { return }
            self.finishOperation(operationID)
            self.authenticationMessage = result.value ? nil : self.message(for: result.error)
            self.publishStateIfPossible()
        }
        retain(observation, for: operationID)
    }

    func openNewTopic() {
        if isFixture {
            state.composer.mode = .newTopic
            state.composer.canEdit = true
            state.composer.canSubmit = true
            return
        }
        _ = host?.openNewTopic(categoryId: state.selectedCategoryID.kotlinLong)
    }

    func openReply(postNumber: Int? = nil) {
        guard let topicID = state.selectedTopicID else { return }
        if isFixture {
            state.composer.mode = .reply
            state.composer.canEdit = true
            state.composer.canSubmit = true
            return
        }
        _ = host?.openReply(topicId: topicID, replyToPostNumber: postNumber.kotlinInt)
    }

    func openEdit(post: ForumPostModel) {
        guard let topicID = state.selectedTopicID else { return }
        guard !isFixture else {
            state.composer.mode = .edit
            state.composer.body = post.blocks.map(\.text).joined(separator: "\n\n")
            state.composer.canEdit = true
            state.composer.canSubmit = true
            return
        }
        _ = host?.openEdit(topicId: topicID, postId: post.id, postNumber: Int32(post.postNumber))
    }

    func updateComposer(title: String, body: String, tags: String) {
        if isFixture {
            state.composer.title = title
            state.composer.body = body
            state.composer.tags = tags
            return
        }
        guard let owner = composerOwner(), let host else { return }
        localComposerFailure = nil
        _ = host.updateDraft(
            title: owner.target.kind == AppleComposerTargetKind.theNewTopic ? title : nil,
            raw: body,
            tags: parseTags(tags),
            expectedContentVersion: owner.contentVersion,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID,
            expectedTarget: owner.target
        )
    }

    func submitComposer() {
        guard !isFixture else {
            state.composer.submitState = .published
            return
        }
        guard let owner = composerOwner(), let host else { return }
        localComposerFailure = nil
        _ = host.submitComposer(
            expectedContentVersion: owner.contentVersion,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID,
            expectedTarget: owner.target
        )
    }

    func closeComposer(discard: Bool = false) {
        if isFixture {
            state.composer = ForumComposerModel()
            return
        }
        guard let owner = composerOwner(), let host else { return }
        cancelPendingAttachmentRead()
        localComposerFailure = nil
        if discard {
            _ = host.discardDraft(
                expectedContentVersion: owner.contentVersion,
                expectedSessionGeneration: owner.sessionGeneration,
                expectedAccountId: owner.accountID,
                expectedTarget: owner.target
            )
        } else {
            _ = host.closeComposer(
                expectedContentVersion: owner.contentVersion,
                expectedSessionGeneration: owner.sessionGeneration,
                expectedAccountId: owner.accountID,
                expectedTarget: owner.target
            )
        }
    }

    /// Reads one owner-bound security-scoped attachment at a time, off the main actor.
    func uploadAttachment(url: URL) {
        guard !isFixture, let owner = composerOwner() else { return }
        cancelPendingAttachmentRead()
        localComposerFailure = nil
        let taskID = UUID()
        let maxBytes = 16 * 1_024 * 1_024
        let readTask: Task<AttachmentPayload, Error> = Task.detached(priority: .userInitiated) {
            try Self.readAttachment(at: url, maxBytes: maxBytes)
        }
        let waiterTask = Task { [weak self] in
            do {
                let payload = try await withTaskCancellationHandler(
                    operation: { try await readTask.value },
                    onCancel: { readTask.cancel() }
                )
                try Task.checkCancellation()
                guard let self else { return }
                guard self.consumePendingAttachmentRead(id: taskID),
                      !self.isClosed,
                      self.matches(ComposerIdentity(owner)),
                      let host = self.host else { return }
                _ = host.startUploadData(
                    fileName: payload.fileName,
                    contentType: payload.contentType,
                    data: payload.data,
                    expectedContentVersion: owner.contentVersion,
                    expectedSessionGeneration: owner.sessionGeneration,
                    expectedAccountId: owner.accountID,
                    expectedTarget: owner.target
                )
            } catch is CancellationError {
                self?.consumePendingAttachmentRead(id: taskID)
            } catch {
                guard let self else { return }
                guard self.consumePendingAttachmentRead(id: taskID),
                      !self.isClosed,
                      self.matches(ComposerIdentity(owner)) else { return }
                self.localComposerFailure = OwnedComposerFailure(
                    owner: ComposerIdentity(owner),
                    failure: .invalidResponse
                )
                self.publishStateIfPossible()
            }
        }
        pendingAttachmentRead = PendingAttachmentRead(
            id: taskID,
            owner: ComposerIdentity(owner),
            readTask: readTask,
            waiterTask: waiterTask
        )
    }

    func cancelUpload() {
        guard !isFixture, let owner = composerOwner(), let host else { return }
        _ = host.cancelUpload(
            expectedContentVersion: owner.contentVersion,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID,
            expectedTarget: owner.target
        )
    }

    func retryUpload() {
        guard !isFixture, let owner = composerOwner(), let host else { return }
        _ = host.retryUpload(
            expectedContentVersion: owner.contentVersion,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID,
            expectedTarget: owner.target
        )
    }

    func toggleLike(postID: Int64) {
        guard !isFixture, let owner = forumOwner(), let host else { return }
        _ = host.toggleLike(
            postId: postID,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID
        )
    }

    func togglePostBookmark(postID: Int64) {
        guard !isFixture, let owner = forumOwner(), let host else { return }
        _ = host.togglePostBookmark(
            postId: postID,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID
        )
    }

    func toggleTopicBookmark() {
        guard let topicID = state.selectedTopicID, !isFixture else { return }
        guard let owner = forumOwner(), let host else { return }
        _ = host.toggleTopicBookmark(
            topicId: topicID,
            expectedSessionGeneration: owner.sessionGeneration,
            expectedAccountId: owner.accountID
        )
    }

    func focusSearch() {
        selectDestination(.search)
    }

    func dismissTopmost() {
        if state.composer.isPresented {
            guard state.composer.submitState != .submitting else { return }
            closeComposer()
        } else if state.selectedTopicID != nil {
            closeTopic()
        }
    }

    // MARK: - Host lifecycle

    private func connectToSharedHost() {
        guard !isFixture else { return }
        do {
            let databasePath = try Self.databasePath()
            let result = AppleForumHost.companion.create(databasePath: databasePath)
            guard let host = result.host else {
                state.isFeedLoading = false
                state.failure = .invalidResponse
                return
            }
            self.host = host
            isRestoringSession = true
            forumObservation = host.observeForum { [weak self] snapshot in
                self?.receiveForum(snapshot)
            }
            composerObservation = host.observeComposer { [weak self] snapshot in
                self?.receiveComposer(snapshot)
            }
            manualChallengeObservation = host.observeManualChallenge { [weak self] snapshot in
                self?.receiveManualChallenge(snapshot)
            }

            let operationID = beginOperation()
            let restore = host.restoreSession { [weak self] result in
                guard let self else { return }
                self.finishOperation(operationID)
                guard !self.isClosed else { return }
                self.isRestoringSession = false
                if let error = result.error {
                    self.authenticationMessage = self.message(for: error)
                }
                self.publishStateIfPossible()

                if self.checkpointRequested, !self.isForeground, let host = self.host {
                    self.checkpointRequested = false
                    self.checkpointSession(using: host)
                }
                if let redirectURL = self.pendingLoginRedirectURL {
                    self.pendingLoginRedirectURL = nil
                    self.completeLoginAfterRestore(redirectURL)
                }
            }
            retain(restore, for: operationID)
        } catch {
            state.isFeedLoading = false
            state.failure = .invalidResponse
        }
    }

    private func receiveForum(_ snapshot: AppleForumSnapshot) {
        rawForumSnapshot = snapshot
        reconcileComposerOwnership()
        if snapshot.isAuthenticated {
            authenticationMessage = nil
            synchronizeActions(for: snapshot)
        }
        publishStateIfPossible()
    }

    private func receiveComposer(_ snapshot: AppleComposerSnapshot) {
        rawComposerSnapshot = snapshot
        reconcileComposerOwnership()
        publishStateIfPossible()
    }

    private func receiveManualChallenge(_ snapshot: AppleManualChallengeSnapshot?) {
        guard let snapshot else {
            if let current = restrictedBrowserRequest,
               case .manualChallenge = current.kind {
                restrictedBrowserRequest = nil
            }
            return
        }
        guard snapshot.requestId > 0, snapshot.origin == "https://linux.do" else {
            authenticationMessage = String(localized: "forum.failure.invalid_response")
            publishStateIfPossible()
            return
        }

        // Emitting a challenge proves `completeWebSession` already captured the fallback `_t`.
        // Release that handoff without clearing it; the challenge surface will replace the shared
        // bridge with non-session Cloudflare cookies before the request-bound handler consumes them.
        let nestedLoginIDs = cookieHandoffs.compactMap { id, pending in
            if case .fallbackLogin = pending.request.kind { return id }
            return nil
        }
        nestedLoginIDs.forEach { finishCookieHandoff($0, clearCookies: false) }
        restrictedBrowserRequest = ForumRestrictedBrowserRequest(
            kind: .manualChallenge(requestID: snapshot.requestId)
        )
    }

    /// Seeds action permissions using the same forum owner that supplied the visible controls.
    private func synchronizeActions(for snapshot: AppleForumSnapshot) {
        guard
            let host,
            let accountID = snapshot.accountId,
            let topic = snapshot.selectedTopic
        else { return }
        _ = host.synchronizeSelectedTopicActions(
            expectedTopicId: topic.topicId,
            expectedSessionGeneration: snapshot.sessionGeneration,
            expectedAccountId: accountID
        )
        for article in topic.articles {
            guard let postID = article.postId?.int64Value else { continue }
            _ = host.synchronizePostActions(
                postId: postID,
                expectedSessionGeneration: snapshot.sessionGeneration,
                expectedAccountId: accountID
            )
        }
    }

    private func publishStateIfPossible() {
        guard let forum = rawForumSnapshot else { return }
        let composer: AppleComposerSnapshot?
        if let candidate = rawComposerSnapshot,
           candidate.sessionGeneration == forum.sessionGeneration,
           candidate.accountId == forum.accountId {
            composer = candidate
        } else {
            composer = nil
        }

        let categoryColors = Dictionary(
            uniqueKeysWithValues: forum.categories.map { ($0.id, $0.colorHex) }
        )
        var next = ForumViewState()
        next.destination = forum.destination.swiftValue
        next.topics = forum.topics.compactMap { topic in
            guard let topicID = topic.topicId?.int64Value else { return nil }
            return ForumTopicRowModel(
                id: topicID,
                title: topic.title,
                excerpt: topic.excerpt,
                author: topic.author.displayName,
                replyCount: Int(topic.replyCount),
                viewCount: Int(topic.viewCount),
                category: topic.categoryName,
                categoryColorHex: topic.categoryId.flatMap { categoryColors[$0.int64Value] } ?? nil,
                tags: topic.tags,
                unread: topic.unread
            )
        }
        next.categories = forum.categories.map {
            ForumCategoryModel(
                id: $0.id,
                name: $0.name,
                topicCount: Int($0.topicCount),
                colorHex: $0.colorHex
            )
        }
        next.tags = forum.tags.map {
            ForumTagModel(id: $0.id, name: $0.name, count: Int($0.count))
        }
        next.selectedCategoryID = forum.selection.kind == AppleForumFeedKind.category
            ? forum.selection.id?.int64Value
            : nil
        next.selectedTagID = forum.selection.kind == AppleForumFeedKind.tag
            ? forum.tags.first(where: { $0.slug == forum.selection.slug })?.id
            : nil
        next.selectedTopicID = forum.selectedTopicId?.int64Value
        next.selectedTopic = forum.selectedTopic.map { mapTopic($0, composer: composer) }
        next.searchQuery = forum.search.query
        next.searchResults = forum.search.items.map {
            ForumSearchHitModel(
                id: $0.postId,
                topicID: $0.topicId,
                postNumber: Int($0.postNumber),
                title: $0.title,
                excerpt: $0.excerpt,
                author: $0.author.displayName
            )
        }
        next.profile = forum.profile.value.map(mapProfile)
        next.profileActivity = forum.profile.activity.map(mapActivity)
        next.notifications = forum.notifications.items.map(mapNotification)
        next.unreadNotificationCount = Int(forum.notifications.unreadCount)
        next.isAuthenticated = forum.isAuthenticated
        next.accountUsername = forum.accountUsername
        next.canCreateTopic = forum.canCreateTopic
        next.isFeedLoading = forum.isFeedLoading
        next.isTopicLoading = forum.isTopicLoading
        next.isSearchLoading = forum.search.isLoading
        next.isProfileLoading = forum.profile.isLoading || forum.profile.isActivityLoading
        next.isNotificationsLoading = forum.notifications.isLoading || forum.notifications.isMarkingRead
        next.isAppending = forum.isAppending
        next.isSearchAppending = forum.search.isAppending
        next.isNotificationsAppending = forum.notifications.isAppending
        next.isProfileActivityAppending = forum.profile.isAppendingActivity
        next.hasMore = forum.nextPage != nil && forum.appendFailure == nil
        next.searchHasMore = forum.search.nextPage != nil && forum.search.appendFailure == nil
        next.notificationsHasMore = forum.notifications.nextOffset != nil && forum.notifications.appendFailure == nil
        next.profileActivityHasMore = forum.profile.nextOffset != nil && forum.profile.activityAppendFailure == nil
        next.contentSource = forum.feedSource?.swiftValue
        next.failure = (forum.appendFailure ?? forum.feedFailure ?? forum.taxonomyFailure)?.swiftValue
        next.topicFailure = forum.topicFailure?.swiftValue
        next.searchFailure = (forum.search.appendFailure ?? forum.search.failure)?.swiftValue
        next.notificationsFailure = (
            forum.notifications.markFailure
                ?? forum.notifications.appendFailure
                ?? forum.notifications.failure
        )?.swiftValue
        next.profileFailure = (
            forum.profile.activityAppendFailure
                ?? forum.profile.activityFailure
                ?? forum.profile.failure
        )?.swiftValue
        next.composer = composer.map(mapComposer) ?? ForumComposerModel()
        next.authenticationMessage = authenticationMessage
        state = next
    }

    // MARK: - Snapshot mapping

    private func mapTopic(
        _ topic: AppleForumTopicSnapshot,
        composer: AppleComposerSnapshot?
    ) -> ForumTopicDocumentModel {
        let topicAction = composer?.postActions.first {
            $0.targetKind == AppleActionTargetKind.topic && $0.targetId == topic.topicId
        }
        return ForumTopicDocumentModel(
            id: topic.topicId,
            title: topic.title,
            tags: topic.tags,
            posts: topic.articles.compactMap { article in
                guard
                    let postID = article.postId?.int64Value,
                    let postNumber = article.postNumber?.intValue
                else { return nil }
                let action = composer?.postActions.first {
                    $0.targetKind == AppleActionTargetKind.post && $0.targetId == postID
                }
                return ForumPostModel(
                    id: postID,
                    postNumber: postNumber,
                    author: article.author.displayName,
                    blocks: article.blocks.map(mapBlock),
                    canReply: article.canReply,
                    canEdit: article.canEdit,
                    canLike: action?.canLike ?? article.canLike,
                    liked: action?.liked ?? article.liked,
                    likeCount: Int(action?.likeCount ?? article.likeCount),
                    canBookmark: action?.canBookmark ?? article.canBookmark,
                    bookmarked: action?.bookmarked ?? article.bookmarked
                )
            },
            canReply: topic.canReply,
            canBookmark: topicAction?.canBookmark ?? topic.canBookmark,
            bookmarked: topicAction?.bookmarked ?? topic.bookmarked
        )
    }

    private func mapBlock(_ block: AppleRichTextBlockSnapshot) -> ForumPostBlockModel {
        ForumPostBlockModel(
            id: block.id,
            kind: block.kind.swiftValue,
            text: block.text,
            secondaryText: block.auxiliaryText,
            url: safeRemoteURL(block.url),
            linkURL: safeRemoteURL(block.linkUrl),
            inlines: block.inlines.map(mapInline),
            children: block.children.map(mapBlock),
            ordered: block.ordered,
            startIndex: Int(block.startIndex),
            itemIndex: block.itemIndex?.intValue,
            isHeader: block.isHeader,
            columnSpan: Int(block.columnSpan),
            rowSpan: Int(block.rowSpan)
        )
    }

    private func mapInline(_ inline: AppleRichTextInlineSnapshot) -> ForumPostInlineModel {
        ForumPostInlineModel(
            kind: inline.kind.swiftValue,
            text: inline.text,
            url: safeRemoteURL(inline.url),
            auxiliaryText: inline.auxiliaryText,
            children: inline.children.map(mapInline)
        )
    }

    private func mapProfile(_ value: AppleForumProfileValueSnapshot) -> ForumProfileModel {
        ForumProfileModel(
            username: value.username,
            displayName: value.displayName,
            title: value.title,
            location: value.location,
            bio: flatten(value.bio),
            trustLevel: Int(value.trustLevel),
            postCount: Int(value.summary.postCount),
            topicCount: Int(value.summary.topicCount),
            daysVisited: Int(value.summary.daysVisited)
        )
    }

    private func mapActivity(_ value: AppleForumActivitySnapshot) -> ForumActivityModel {
        ForumActivityModel(
            id: value.id,
            title: value.title ?? String(localized: "forum.activity"),
            excerpt: value.excerpt,
            actor: value.actingUser?.displayName ?? value.user?.displayName,
            topicID: value.topicId?.int64Value,
            postNumber: value.postNumber?.intValue
        )
    }

    private func mapNotification(_ value: AppleForumNotificationSnapshot) -> ForumNotificationModel {
        let title = value.title ?? value.data.topicTitle ?? String(localized: "forum.notifications")
        let actor = value.actingUser?.displayName
            ?? value.data.displayUsername
            ?? value.data.username
            ?? value.data.originalUsername
        let context = value.data.badgeName ?? value.data.groupName
        return ForumNotificationModel(
            id: value.id,
            topicID: value.topicId?.int64Value,
            postNumber: value.postNumber?.intValue,
            title: title,
            detail: [actor, context].compactMap { $0 }.joined(separator: " - "),
            unread: !value.read
        )
    }

    private func mapComposer(_ value: AppleComposerSnapshot) -> ForumComposerModel {
        let uploadTotal = value.upload.totalBytes?.int64Value
        let progress: Double?
        if let uploadTotal, uploadTotal > 0 {
            progress = min(1, Double(value.upload.bytesSent) / Double(uploadTotal))
        } else {
            progress = nil
        }
        return ForumComposerModel(
            mode: value.mode.swiftValue,
            title: value.title ?? "",
            body: value.raw,
            tags: value.tags.joined(separator: ", "),
            canEdit: value.canEdit,
            canSubmit: value.canSubmit,
            isUploading: value.upload.status == AppleComposerUploadStatus.uploading,
            uploadProgress: progress,
            canCancelUpload: value.upload.status == AppleComposerUploadStatus.uploading,
            canRetryUpload: value.upload.status == AppleComposerUploadStatus.failed
                || value.upload.status == AppleComposerUploadStatus.cancelled,
            submitState: value.submitStatus.swiftValue,
            failure: localComposerFailure.flatMap { ownedFailure in
                guard ownedFailure.matches(composerOwner()) else { return nil }
                return ownedFailure.failure
            }
                ?? value.upload.failure?.swiftValue
                ?? value.submitFailure?.swiftValue
                ?? value.draftFailure?.swiftValue
                ?? value.initializationFailure?.swiftValue
                ?? (value.validationFailure == nil ? nil : .invalidResponse)
        )
    }

    private func flatten(_ blocks: [AppleRichTextBlockSnapshot]) -> String {
        blocks.compactMap { block in
            let inlineText = block.inlines.map(\.text).joined()
            let ownText = block.text.isEmpty ? inlineText : block.text
            let childText = flatten(block.children)
            return [ownText, childText]
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
                .nilIfEmpty
        }.joined(separator: "\n\n")
    }

    private func safeRemoteURL(_ rawValue: String?) -> URL? {
        guard
            let rawValue,
            let url = URL(string: rawValue),
            let scheme = url.scheme?.lowercased(),
            scheme == "https" || scheme == "http",
            url.host != nil,
            url.user == nil,
            url.password == nil
        else { return nil }
        return url
    }

    // MARK: - Immutable mutation owners

    private func forumOwner() -> ForumOwner? {
        guard
            let forum = rawForumSnapshot,
            forum.isAuthenticated,
            let accountID = forum.accountId
        else { return nil }
        return ForumOwner(sessionGeneration: forum.sessionGeneration, accountID: accountID)
    }

    private func composerOwner() -> ComposerOwner? {
        guard
            let forum = rawForumSnapshot,
            let composer = rawComposerSnapshot,
            composer.sessionGeneration == forum.sessionGeneration,
            composer.accountId == forum.accountId,
            let accountID = composer.accountId,
            let target = composer.target
        else { return nil }
        return ComposerOwner(
            sessionGeneration: composer.sessionGeneration,
            accountID: accountID,
            contentVersion: composer.contentVersion,
            target: target
        )
    }

    private func matches(_ owner: ComposerIdentity) -> Bool {
        guard let current = composerOwner() else { return false }
        return ComposerIdentity(current) == owner
    }

    // MARK: - Bounded asynchronous ownership

    private func reconcileComposerOwnership() {
        if let pending = pendingAttachmentRead, !matches(pending.owner) {
            cancelPendingAttachmentRead()
        }
        if let failure = localComposerFailure, !failure.matches(composerOwner()) {
            localComposerFailure = nil
        }
    }

    private func cancelPendingAttachmentRead() {
        guard let pending = pendingAttachmentRead else { return }
        pendingAttachmentRead = nil
        pending.waiterTask.cancel()
        pending.readTask.cancel()
    }

    @discardableResult
    private func consumePendingAttachmentRead(id: UUID) -> Bool {
        guard pendingAttachmentRead?.id == id else { return false }
        pendingAttachmentRead = nil
        return true
    }

    /// Retains the callback facade until the encrypted session/draft checkpoint reaches a terminal
    /// result. Repeated inactive/background scene notifications share the same in-flight checkpoint.
    private func checkpointSession(using host: AppleForumHost) {
        guard checkpointOperationID == nil, !isClosed else { return }
        let operationID = beginOperation()
        checkpointOperationID = operationID
        let observation = host.checkpointSession { [weak self] _ in
            guard let self else { return }
            if self.checkpointOperationID == operationID {
                self.checkpointOperationID = nil
            }
            self.finishOperation(operationID)
            guard !self.isClosed else { return }
            if self.checkpointRequested, !self.isForeground, let host = self.host {
                self.checkpointRequested = false
                self.checkpointSession(using: host)
            }
        }
        retain(observation, for: operationID)
    }

    private func beginOperation() -> UUID {
        let id = UUID()
        pendingOperationIDs.insert(id)
        return id
    }

    private func retain(_ observation: AppleForumObservation, for id: UUID) {
        if pendingOperationIDs.contains(id) {
            operations[id] = observation
        } else {
            // Handles a future facade implementation that invokes a terminal callback inline.
            observation.cancel()
        }
    }

    private func finishOperation(_ id: UUID) {
        pendingOperationIDs.remove(id)
        operations[id] = nil
    }

    private func finishCookieHandoff(_ id: UUID, clearCookies: Bool) {
        guard let pending = cookieHandoffs.removeValue(forKey: id) else { return }
        pending.token.finish(clearCookies: clearCookies)
    }

    private func message(for result: AppleLoginResult) -> String? {
        if result.status == AppleLoginStatus.authenticated { return nil }
        if result.status == AppleLoginStatus.expired {
            return String(localized: "forum.failure.authentication")
        }
        return message(for: result.error) ?? String(localized: "forum.failure.invalid_response")
    }

    private func message(for error: AppleForumOperationError?) -> String? {
        guard let error else { return nil }
        if error == AppleForumOperationError.authentication || error == AppleForumOperationError.staleSession {
            return String(localized: "forum.failure.authentication")
        }
        if error == AppleForumOperationError.permission {
            return String(localized: "forum.failure.permission")
        }
        if error == AppleForumOperationError.rateLimited {
            return String(localized: "forum.failure.rate_limited")
        }
        if error == AppleForumOperationError.challengeRequired {
            return String(localized: "forum.failure.challenge")
        }
        if error == AppleForumOperationError.network {
            return String(localized: "forum.failure.network")
        }
        if error == AppleForumOperationError.server {
            return String(localized: "forum.failure.server")
        }
        return String(localized: "forum.failure.invalid_response")
    }

    private func parseTags(_ value: String) -> [String] {
        value.split(separator: ",", omittingEmptySubsequences: true)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private static func databasePath() throws -> String {
        let manager = FileManager.default
        guard let base = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw ForumStoreError.applicationSupportUnavailable
        }
        let directory = base.appendingPathComponent("FlareDo", isDirectory: true)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("flaredo.sqlite", isDirectory: false).path
    }

    nonisolated private static func readAttachment(at url: URL, maxBytes: Int) throws -> AttachmentPayload {
        guard maxBytes > 0, maxBytes < Int.max else { throw ForumStoreError.invalidAttachment }
        try Task.checkCancellation()
        let hasSecurityScope = url.startAccessingSecurityScopedResource()
        defer {
            if hasSecurityScope { url.stopAccessingSecurityScopedResource() }
        }

        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        try Task.checkCancellation()
        guard values.isRegularFile == true else { throw ForumStoreError.invalidAttachment }
        if let fileSize = values.fileSize, fileSize <= 0 || fileSize > maxBytes {
            throw ForumStoreError.invalidAttachment
        }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        let maximumRead = maxBytes + 1
        let chunkSize = 64 * 1_024
        var data = Data()
        if let fileSize = values.fileSize {
            data.reserveCapacity(min(fileSize, maximumRead))
        }
        while data.count < maximumRead {
            try Task.checkCancellation()
            let count = min(chunkSize, maximumRead - data.count)
            guard let chunk = try handle.read(upToCount: count), !chunk.isEmpty else { break }
            data.append(chunk)
        }
        try Task.checkCancellation()
        guard !data.isEmpty, data.count <= maxBytes else {
            throw ForumStoreError.invalidAttachment
        }
        let contentType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
        return AttachmentPayload(data: data, fileName: url.lastPathComponent, contentType: contentType)
    }
}

private struct ForumOwner {
    let sessionGeneration: Int64
    let accountID: String
}

private struct ComposerOwner {
    let sessionGeneration: Int64
    let accountID: String
    let contentVersion: Int64
    let target: AppleComposerTargetSnapshot
}

/// Stable identity used by Swift-only failures so a replacement account or composer target can
/// never inherit a late result from the previous owner. Content version remains on ComposerOwner
/// because upload mutations also require the exact draft version that selected the attachment.
private struct ComposerIdentity: Equatable {
    let sessionGeneration: Int64
    let accountID: String
    let targetKey: String

    init(_ owner: ComposerOwner) {
        sessionGeneration = owner.sessionGeneration
        accountID = owner.accountID
        targetKey = owner.target.stableKey
    }
}

private struct OwnedComposerFailure {
    let owner: ComposerIdentity
    let failure: ForumFailure

    func matches(_ candidate: ComposerOwner?) -> Bool {
        guard let candidate else { return false }
        return owner == ComposerIdentity(candidate)
    }
}

private struct PendingAttachmentRead {
    let id: UUID
    let owner: ComposerIdentity
    let readTask: Task<AttachmentPayload, Error>
    let waiterTask: Task<Void, Never>
}

private struct AttachmentPayload: Sendable {
    let data: Data
    let fileName: String
    let contentType: String?
}

private struct PendingCookieHandoff {
    let request: ForumRestrictedBrowserRequest
    let token: RestrictedLinuxDoCookieHandoffToken
}

private enum ForumStoreError: Error {
    case applicationSupportUnavailable
    case invalidAttachment
}

private extension ForumDestination {
    var appleValue: AppleForumDestination {
        switch self {
        case .latest: AppleForumDestination.latest
        case .hot: AppleForumDestination.hot
        case .search: AppleForumDestination.search
        case .notifications: AppleForumDestination.notifications
        case .profile: AppleForumDestination.profile
        }
    }
}

private extension AppleForumDestination {
    var swiftValue: ForumDestination {
        if self == AppleForumDestination.hot { return .hot }
        if self == AppleForumDestination.search { return .search }
        if self == AppleForumDestination.notifications { return .notifications }
        if self == AppleForumDestination.profile { return .profile }
        return .latest
    }
}

private extension AppleForumContentSource {
    var swiftValue: ForumContentSource {
        self == AppleForumContentSource.staleCache ? .staleCache : .network
    }
}

private extension AppleForumFailure {
    var swiftValue: ForumFailure {
        if self == AppleForumFailure.authentication { return .authentication }
        if self == AppleForumFailure.permission { return .permission }
        if self == AppleForumFailure.rateLimited { return .rateLimited }
        if self == AppleForumFailure.challengeRequired { return .challengeRequired }
        if self == AppleForumFailure.server { return .server }
        if self == AppleForumFailure.invalidResponse { return .invalidResponse }
        if self == AppleForumFailure.http { return .http }
        return .network
    }
}

private extension AppleComposerMode {
    var swiftValue: ForumComposerMode {
        if self == AppleComposerMode.theNewTopic { return .newTopic }
        if self == AppleComposerMode.reply { return .reply }
        if self == AppleComposerMode.edit { return .edit }
        return .closed
    }
}

private extension AppleComposerSubmitStatus {
    var swiftValue: ForumComposerSubmitState {
        if self == AppleComposerSubmitStatus.submitting { return .submitting }
        if self == AppleComposerSubmitStatus.published { return .published }
        if self == AppleComposerSubmitStatus.pendingModeration { return .pendingModeration }
        if self == AppleComposerSubmitStatus.failed { return .failed }
        return .idle
    }
}

private extension AppleRichTextBlockKind {
    var swiftValue: ForumPostBlockKind {
        if self == AppleRichTextBlockKind.quote { return .quote }
        if self == AppleRichTextBlockKind.code { return .code }
        if self == AppleRichTextBlockKind.image { return .image }
        if self == AppleRichTextBlockKind.list { return .list }
        if self == AppleRichTextBlockKind.listItem { return .listItem }
        if self == AppleRichTextBlockKind.table { return .table }
        if self == AppleRichTextBlockKind.tableRow { return .tableRow }
        if self == AppleRichTextBlockKind.tableCell { return .tableCell }
        if self == AppleRichTextBlockKind.spoiler { return .spoiler }
        return .paragraph
    }
}

private extension AppleRichTextInlineKind {
    var swiftValue: ForumPostInlineKind {
        if self == AppleRichTextInlineKind.link { return .link }
        if self == AppleRichTextInlineKind.code { return .code }
        if self == AppleRichTextInlineKind.image { return .image }
        if self == AppleRichTextInlineKind.spoiler { return .spoiler }
        return .text
    }
}

private extension Optional where Wrapped == Int {
    var kotlinInt: KotlinInt? {
        map { KotlinInt(int: Int32($0)) }
    }
}

private extension Optional where Wrapped == Int64 {
    var kotlinLong: KotlinLong? {
        map { KotlinLong(longLong: $0) }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
