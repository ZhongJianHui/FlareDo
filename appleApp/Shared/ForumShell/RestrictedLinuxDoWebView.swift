import Combine
import Foundation
import SwiftUI
import WebKit

/// The only failures exposed by the restricted browser surface.
///
/// WebKit and Foundation errors can contain request URLs, response details, or platform-specific
/// diagnostics. None of those values cross this boundary: callers receive a stable category and
/// can present a localized, non-sensitive message without accidentally logging browser state.
enum RestrictedLinuxDoWebFailure: String, Error, Equatable, Sendable {
    case invalidInitialURL
    case blockedTopLevelNavigation
    case pageLoadFailed
    case webContentProcessTerminated
    case cookieSynchronizationFailed

    fileprivate var message: LocalizedStringKey {
        switch self {
        case .invalidInitialURL, .blockedTopLevelNavigation:
            "forum.failure.invalid_response"
        case .pageLoadFailed, .webContentProcessTerminated:
            "forum.failure.network"
        case .cookieSynchronizationFailed:
            "forum.failure.authentication"
        }
    }
}

/// The browser purpose controls which Cookie is valid evidence for the explicit Continue action.
///
/// A fallback login must provide Discourse's `_t` session Cookie. A Cloudflare challenge must
/// provide proxy state such as `cf_clearance`, and deliberately excludes `_t` so a browser account
/// can never replace the account already owned by the shared Kotlin session.
enum RestrictedLinuxDoWebMode: String, Equatable, Sendable {
    case login
    case challenge
}

/// Local presentation request. Kotlin supplies only the challenge id and fixed origin; Swift owns
/// the login path and all user-facing presentation strings.
struct ForumRestrictedBrowserRequest: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case fallbackLogin
        case manualChallenge(requestID: Int64)
    }

    let kind: Kind

    var id: String {
        switch kind {
        case .fallbackLogin: "fallback-login"
        case .manualChallenge(let requestID): "manual-challenge-\(requestID)"
        }
    }

    var initialURL: URL {
        switch kind {
        case .fallbackLogin:
            URL(string: "https://linux.do/login")!
        case .manualChallenge:
            RestrictedLinuxDoNavigationPolicy.origin
        }
    }

    var mode: RestrictedLinuxDoWebMode {
        switch kind {
        case .fallbackLogin: .login
        case .manualChallenge: .challenge
        }
    }

    var title: String {
        switch kind {
        case .fallbackLogin: String(localized: "forum.web_login_title")
        case .manualChallenge: String(localized: "forum.challenge_title")
        }
    }

    var continueTitle: String {
        switch kind {
        case .fallbackLogin: String(localized: "forum.finish_login")
        case .manualChallenge: String(localized: "forum.continue")
        }
    }
}

/// Pure origin checks shared by initial loading, redirects, responses, and new-window requests.
///
/// Comparing an `ends(with:)` hostname would admit attacker-controlled names such as
/// `notlinux.do` or `linux.do.example`. Comparing only `URL.port` is also insufficient because
/// Foundation represents an explicitly empty port (`https://linux.do:/`) as `nil`. The authority
/// comparison therefore requires the encoded authority to be exactly `linux.do`, which excludes
/// credentials, explicit ports, trailing dots, escaped hosts, and subdomains in one step.
enum RestrictedLinuxDoNavigationPolicy {
    static let origin = URL(string: "https://linux.do")!

    nonisolated static func allowsTopLevelNavigation(to url: URL?) -> Bool {
        guard let url,
              url.baseURL == nil,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == "linux.do",
              components.user == nil,
              components.password == nil,
              components.port == nil,
              let schemeDelimiter = url.absoluteString.range(of: "://") else {
            return false
        }

        let encodedScheme = url.absoluteString[..<schemeDelimiter.lowerBound]
        guard encodedScheme.lowercased() == "https" else { return false }

        let authorityStart = schemeDelimiter.upperBound
        let authorityEnd = url.absoluteString[authorityStart...].firstIndex { character in
            character == "/" || character == "?" || character == "#"
        } ?? url.absoluteString.endIndex
        let encodedAuthority = url.absoluteString[authorityStart..<authorityEnd]
        return encodedAuthority.lowercased() == "linux.do"
    }
}

/// Pure cookie-domain and expiry checks used before any mutation of shared cookie storage.
///
/// WebKit may spell a cookie scoped to the registrable host as either `linux.do` (host-only) or
/// `.linux.do` (domain cookie). Those are the only two accepted spellings. In particular, this
/// intentionally does not use suffix matching, so cookies for subdomains or lookalike domains
/// cannot enter the application's HTTP session.
enum RestrictedLinuxDoCookiePolicy {
    nonisolated static func isEligible(
        name: String,
        value: String,
        domain: String,
        expiresAt: Date?,
        now: Date,
        mode: RestrictedLinuxDoWebMode
    ) -> Bool {
        guard !name.isEmpty, !value.isEmpty else { return false }
        let canonicalDomain = domain.lowercased()
        guard canonicalDomain == "linux.do" || canonicalDomain == ".linux.do" else {
            return false
        }
        guard expiresAt.map({ $0 > now }) ?? true else { return false }
        switch mode {
        case .login:
            return true
        case .challenge:
            return name != "_t"
        }
    }

    nonisolated static func hasRequiredEvidence(
        cookieNames: [String],
        mode: RestrictedLinuxDoWebMode
    ) -> Bool {
        switch mode {
        case .login:
            cookieNames.contains("_t")
        case .challenge:
            cookieNames.contains("cf_clearance")
        }
    }
}

/// Serializes writes to `HTTPCookieStorage.shared`, which is used only as a one-shot handoff buffer.
///
/// The token stays owned by `ForumStore` until Kotlin finishes consuming the buffer. A nested
/// Cloudflare challenge may take ownership only after Kotlin has emitted that challenge, which
/// proves the fallback login already captured its `_t` Cookie.
@MainActor
enum RestrictedLinuxDoCookieHandoffCoordinator {
    private static var owner: UUID?

    static func acquire(owner candidate: UUID) -> Bool {
        guard owner == nil else { return false }
        owner = candidate
        return true
    }

    static func release(owner candidate: UUID) {
        if owner == candidate { owner = nil }
    }

    /// Clears the shared handoff buffer only while `candidate` still owns it.
    ///
    /// `WKHTTPCookieStore.getAllCookies` cannot be cancelled. Its callback may therefore arrive
    /// after the originating sheet released ownership and a replacement sheet populated the same
    /// process-wide buffer. Keeping the owner check and deletion on `MainActor` makes that stale
    /// callback a no-op instead of deleting the replacement operation's credentials.
    @discardableResult
    static func clearSharedLinuxDoCookies(ifOwnedBy candidate: UUID) -> Bool {
        guard owner == candidate else { return false }
        clearSharedLinuxDoCookies()
        return true
    }

    static func clearSharedLinuxDoCookies() {
        let now = Date()
        for cookie in HTTPCookieStorage.shared.cookies ?? [] where
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: cookie.name,
                value: cookie.value,
                domain: cookie.domain,
                expiresAt: nil,
                now: now,
                mode: .login
            ) {
            HTTPCookieStorage.shared.deleteCookie(cookie)
        }
    }
}

/// One-use ownership for a synchronized Cookie buffer.
///
/// `finish` is idempotent so inline Kotlin callbacks, cancellation, and host teardown can converge
/// on the same cleanup path without deleting another operation's buffer twice.
@MainActor
final class RestrictedLinuxDoCookieHandoffToken {
    private let ownerID: UUID
    private(set) var isFinished = false

    fileprivate init(ownerID: UUID) {
        self.ownerID = ownerID
    }

    func finish(clearCookies: Bool) {
        guard !isFinished else { return }
        isFinished = true
        if clearCookies {
            RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(ifOwnedBy: ownerID)
        }
        RestrictedLinuxDoCookieHandoffCoordinator.release(owner: ownerID)
    }

    // Ownership paths explicitly call finish(). An actor-isolated synthesized deinit is avoided
    // because its iOS 17 back-deployment thunk can abort while freeing the MainActor task-local.
    deinit {}

}

/// A short-lived Linux.do browser used for fallback login and manual challenge completion.
///
/// The caller supplies the user-visible title, an optional opaque request identifier, and explicit
/// cancel/continue closures. This view never invokes the Kotlin facade itself. A challenge caller
/// can therefore return the exact request identifier to the presenter, while a login caller can use
/// `nil` and complete its own web-session exchange after cookies have been synchronized.
struct RestrictedLinuxDoWebView: View {
    let title: String
    let mode: RestrictedLinuxDoWebMode
    let requestIdentifier: String?
    let cancelTitle: String
    let continueTitle: String
    let onCancel: (String?) -> Void
    let onContinue: (String?, RestrictedLinuxDoCookieHandoffToken) -> Void
    let onFailure: (String?, RestrictedLinuxDoWebFailure) -> Void

    @StateObject private var session: RestrictedLinuxDoWebSession

    init(
        title: String,
        initialURL: URL,
        mode: RestrictedLinuxDoWebMode,
        requestIdentifier: String? = nil,
        cancelTitle: String = String(localized: "forum.cancel"),
        continueTitle: String,
        onCancel: @escaping (String?) -> Void,
        onContinue: @escaping (String?, RestrictedLinuxDoCookieHandoffToken) -> Void,
        onFailure: @escaping (String?, RestrictedLinuxDoWebFailure) -> Void = { _, _ in }
    ) {
        self.title = title
        self.mode = mode
        self.requestIdentifier = requestIdentifier
        self.cancelTitle = cancelTitle
        self.continueTitle = continueTitle
        self.onCancel = onCancel
        self.onContinue = onContinue
        self.onFailure = onFailure
        _session = StateObject(
            wrappedValue: RestrictedLinuxDoWebSession(initialURL: initialURL, mode: mode)
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            browserHeader
            Divider()
            ZStack(alignment: .top) {
                RestrictedLinuxDoPlatformWebView(session: session)

                if session.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .padding(8)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .padding(10)
                        .accessibilityLabel(Text("forum.loading"))
                }

                if let failure = session.failure {
                    failureBanner(failure)
                        .padding(10)
                }
            }
        }
        .background(Color.primary.opacity(0.025))
        .onChange(of: session.failureRevision, initial: true) { _, revision in
            guard revision > 0, let failure = session.failure else { return }
            onFailure(requestIdentifier, failure)
        }
        .onDisappear {
            session.cancel()
        }
        .interactiveDismissDisabled()
        .accessibilityIdentifier("restricted_linux_do_web_view")
    }

    private var browserHeader: some View {
        HStack(spacing: 12) {
            Button {
                session.cancel()
                onCancel(requestIdentifier)
            } label: {
                Label(cancelTitle, systemImage: "xmark")
            }
            .accessibilityIdentifier("restricted_web_cancel")

            Spacer(minLength: 4)

            VStack(spacing: 1) {
                Text(title)
                    .font(.headline)
                    .lineLimit(1)
                Label("linux.do", systemImage: "lock.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)

            Spacer(minLength: 4)

            Button {
                session.synchronizeEligibleCookies { handoff in
                    onContinue(requestIdentifier, handoff)
                }
            } label: {
                if session.isSynchronizingCookies {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityLabel(Text("forum.loading"))
                } else {
                    Label(continueTitle, systemImage: "checkmark")
                }
            }
            .disabled(
                session.isSynchronizingCookies
                    || session.hasConsumedHandoff
                    || !session.hasValidInitialURL
            )
            .accessibilityIdentifier("restricted_web_continue")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private func failureBanner(_ failure: RestrictedLinuxDoWebFailure) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.yellow)
            Text(failure.message)
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                session.dismissFailure()
            } label: {
                Image(systemName: "xmark")
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("forum.cancel"))
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
        .accessibilityElement(children: .contain)
    }
}

@MainActor
private final class RestrictedLinuxDoWebSession: NSObject, ObservableObject {
    @Published private(set) var isLoading = false
    @Published private(set) var isSynchronizingCookies = false
    @Published private(set) var hasConsumedHandoff = false
    @Published private(set) var failure: RestrictedLinuxDoWebFailure?
    @Published private(set) var failureRevision = 0

    let webView: WKWebView
    let hasValidInitialURL: Bool
    private let mode: RestrictedLinuxDoWebMode
    private var cookieSynchronizationGeneration = 0
    private var synchronizingOwnerID: UUID?

    init(initialURL: URL, mode: RestrictedLinuxDoWebMode) {
        let configuration = WKWebViewConfiguration()

        // The fallback browser is a temporary authentication boundary. An ephemeral store ensures
        // its cookies, cache, local storage, service workers, and IndexedDB do not survive this
        // view's lifetime. Only the explicitly filtered cookies copied on Continue escape it.
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all

        webView = WKWebView(frame: .zero, configuration: configuration)
        hasValidInitialURL = RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(to: initialURL)
        self.mode = mode
        super.init()

        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isInspectable = false

        #if os(iOS)
        webView.allowsLinkPreview = false
        webView.scrollView.keyboardDismissMode = .interactive
        #elseif os(macOS)
        webView.allowsMagnification = true
        #endif

        if hasValidInitialURL {
            webView.load(URLRequest(url: initialURL, cachePolicy: .reloadIgnoringLocalCacheData))
        } else {
            recordFailure(.invalidInitialURL)
        }
    }

    // Keep destruction nonisolated for the same iOS 17 Swift back-deployment issue documented by
    // ForumStore. SwiftUI calls cancel() from onDisappear while the session is still main-actor
    // isolated; an empty deinit prevents synthesis of the crashing deinit-on-executor thunk.
    deinit {}

    func cancel() {
        // WKHTTPCookieStore has no cancellation API. Invalidating the local generation prevents a
        // late callback from invoking Continue after the user has explicitly cancelled the sheet.
        cookieSynchronizationGeneration &+= 1
        isSynchronizingCookies = false
        if let synchronizingOwnerID {
            RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(
                ifOwnedBy: synchronizingOwnerID
            )
            RestrictedLinuxDoCookieHandoffCoordinator.release(owner: synchronizingOwnerID)
            self.synchronizingOwnerID = nil
        }
        webView.stopLoading()
    }

    func dismissFailure() {
        failure = nil
    }

    /// Replaces only Linux.do cookies in the process-wide HTTP storage.
    ///
    /// Shared cookies are cleared before copying so a cancelled or partially expired WebKit session
    /// cannot be combined with stale credentials. Third-party cookies collected by page resources
    /// remain confined to the ephemeral store. A post-write check detects an accept policy that
    /// silently rejects cookies and fails closed instead of advancing the authentication flow.
    func synchronizeEligibleCookies(
        onSuccess: @escaping (RestrictedLinuxDoCookieHandoffToken) -> Void
    ) {
        guard !isSynchronizingCookies, !hasConsumedHandoff else { return }
        let ownerID = UUID()
        guard RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: ownerID) else {
            recordFailure(.cookieSynchronizationFailed)
            return
        }
        synchronizingOwnerID = ownerID
        isSynchronizingCookies = true
        cookieSynchronizationGeneration &+= 1
        let generation = cookieSynchronizationGeneration
        let now = Date()

        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self, self.cookieSynchronizationGeneration == generation else {
                RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(ifOwnedBy: ownerID)
                RestrictedLinuxDoCookieHandoffCoordinator.release(owner: ownerID)
                return
            }
            let eligibleCookies = cookies.filter { cookie in
                RestrictedLinuxDoCookiePolicy.isEligible(
                    name: cookie.name,
                    value: cookie.value,
                    domain: cookie.domain,
                    expiresAt: cookie.expiresDate,
                    now: now,
                    mode: self.mode
                )
            }
            let hasRequiredEvidence = RestrictedLinuxDoCookiePolicy.hasRequiredEvidence(
                cookieNames: eligibleCookies.map(\.name),
                mode: self.mode
            )
            let hasDuplicateCookieIdentity = Dictionary(
                grouping: eligibleCookies,
                by: { "\($0.name)\u{0}\($0.path)" }
            ).values.contains { $0.count > 1 }
            guard hasRequiredEvidence, !hasDuplicateCookieIdentity else {
                self.failCookieSynchronization(ownerID: ownerID)
                return
            }

            let storage = HTTPCookieStorage.shared
            guard RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(
                ifOwnedBy: ownerID
            ) else {
                self.failCookieSynchronization(ownerID: ownerID)
                return
            }
            eligibleCookies.forEach(storage.setCookie)

            let storedCookies = (storage.cookies ?? []).filter { cookie in
                RestrictedLinuxDoCookiePolicy.isEligible(
                    name: cookie.name,
                    value: cookie.value,
                    domain: cookie.domain,
                    expiresAt: cookie.expiresDate,
                    now: now,
                    mode: self.mode
                )
            }
            let synchronized = storedCookies.count == eligibleCookies.count
                && eligibleCookies.allSatisfy { expected in
                    storedCookies.contains { actual in
                        RestrictedLinuxDoCookiePolicy.isEligible(
                            name: actual.name,
                            value: actual.value,
                            domain: actual.domain,
                            expiresAt: actual.expiresDate,
                            now: now,
                            mode: self.mode
                        ) && actual.name == expected.name
                            && actual.path == expected.path
                            && actual.value == expected.value
                    }
                }

            self.isSynchronizingCookies = false
            if synchronized {
                self.hasConsumedHandoff = true
                self.synchronizingOwnerID = nil
                onSuccess(RestrictedLinuxDoCookieHandoffToken(ownerID: ownerID))
            } else {
                self.failCookieSynchronization(ownerID: ownerID)
            }
        }
    }

    private func failCookieSynchronization(ownerID: UUID) {
        guard synchronizingOwnerID == ownerID else { return }
        RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(ifOwnedBy: ownerID)
        RestrictedLinuxDoCookieHandoffCoordinator.release(owner: ownerID)
        synchronizingOwnerID = nil
        isSynchronizingCookies = false
        recordFailure(.cookieSynchronizationFailed)
    }

    private func recordFailure(_ value: RestrictedLinuxDoWebFailure) {
        failure = value
        failureRevision &+= 1
    }
}

extension RestrictedLinuxDoWebSession: WKNavigationDelegate {
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
    ) {
        // `targetFrame == nil` means a new browsing context (for example target=_blank). Treat it
        // as a top-level request, otherwise a page could use window.open to bypass the origin gate.
        let isTopLevel = navigationAction.targetFrame?.isMainFrame ?? true
        guard !isTopLevel ||
                RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(
                    to: navigationAction.request.url
                ) else {
            recordFailure(.blockedTopLevelNavigation)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationResponsePolicy) -> Void
    ) {
        // Redirect actions are normally checked above. Re-checking the committed main-frame
        // response keeps the invariant intact if WebKit changes redirect callback ordering.
        guard !navigationResponse.isForMainFrame ||
                RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(
                    to: navigationResponse.response.url
                ) else {
            recordFailure(.blockedTopLevelNavigation)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation?) {
        isLoading = true
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation?) {
        isLoading = false
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation?,
        withError error: any Error
    ) {
        handleLoadFailure(error)
    }

    func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation?,
        withError error: any Error
    ) {
        handleLoadFailure(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        isLoading = false
        recordFailure(.webContentProcessTerminated)
    }

    private func handleLoadFailure(_ error: any Error) {
        isLoading = false
        // Policy cancellations are an implementation detail of a failure already reported above.
        // All other platform errors collapse to one fixed category; the underlying object is never
        // formatted, logged, stored, or returned to the caller.
        let nsError = error as NSError
        guard nsError.domain != NSURLErrorDomain || nsError.code != NSURLErrorCancelled else {
            return
        }
        recordFailure(.pageLoadFailed)
    }
}

extension RestrictedLinuxDoWebSession: WKUIDelegate {
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        guard navigationAction.targetFrame == nil else { return nil }
        guard RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(
            to: navigationAction.request.url
        ) else {
            recordFailure(.blockedTopLevelNavigation)
            return nil
        }

        // Keep allowed target=_blank links inside this already-restricted, ephemeral web view.
        // Returning nil prevents WebKit from creating an unmanaged second browser surface.
        webView.load(navigationAction.request)
        return nil
    }
}

#if os(iOS)
private struct RestrictedLinuxDoPlatformWebView: UIViewRepresentable {
    let session: RestrictedLinuxDoWebSession

    func makeUIView(context: Context) -> WKWebView {
        session.webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
#elseif os(macOS)
private struct RestrictedLinuxDoPlatformWebView: NSViewRepresentable {
    let session: RestrictedLinuxDoWebSession

    func makeNSView(context: Context) -> WKWebView {
        session.webView
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {}
}
#endif
