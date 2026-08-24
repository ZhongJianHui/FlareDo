import Foundation
import XCTest

@testable import FlareDo

nonisolated final class RestrictedLinuxDoWebViewPolicyTests: XCTestCase {
    func testTopLevelPolicyAllowsOnlyExactPortlessHTTPSOrigin() throws {
        let allowed = try XCTUnwrap(URL(string: "https://linux.do/session/sso?return_path=%2Flatest"))
        let uppercaseHost = try XCTUnwrap(URL(string: "HTTPS://LINUX.DO/latest"))

        XCTAssertTrue(RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(to: allowed))
        XCTAssertTrue(RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(to: uppercaseHost))
    }

    func testTopLevelPolicyRejectsSchemesCredentialsPortsAndLookalikeHosts() throws {
        let rejectedURLs = [
            "http://linux.do/latest",
            "https://user@linux.do/latest",
            "https://user:password@linux.do/latest",
            "https://linux.do:443/latest",
            "https://linux.do:/latest",
            "https://www.linux.do/latest",
            "https://account.linux.do/latest",
            "https://notlinux.do/latest",
            "https://linux.do.example/latest",
            "https://linux.do./latest",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///tmp/linux.do"
        ]

        for value in rejectedURLs {
            let url = try XCTUnwrap(URL(string: value), "Foundation should parse the test case: \(value)")
            XCTAssertFalse(
                RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(to: url),
                "Unexpectedly allowed \(value)"
            )
        }
        XCTAssertFalse(RestrictedLinuxDoNavigationPolicy.allowsTopLevelNavigation(to: nil))
    }

    func testCookiePolicyAcceptsOnlyExactLinuxDoDomainsThatHaveNotExpired() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)

        XCTAssertTrue(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "session",
                domain: "linux.do",
                expiresAt: nil,
                now: now,
                mode: .login
            )
        )
        XCTAssertTrue(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "session",
                domain: ".LINUX.DO",
                expiresAt: now.addingTimeInterval(1),
                now: now,
                mode: .login
            )
        )
        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "session",
                domain: "linux.do",
                expiresAt: now,
                now: now,
                mode: .login
            )
        )
        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "session",
                domain: "linux.do",
                expiresAt: now.addingTimeInterval(-1),
                now: now,
                mode: .login
            )
        )

        for domain in ["www.linux.do", ".account.linux.do", "notlinux.do", "linux.do.example", "linux.do."] {
            XCTAssertFalse(
                RestrictedLinuxDoCookiePolicy.isEligible(
                    name: "_t",
                    value: "session",
                    domain: domain,
                    expiresAt: nil,
                    now: now,
                    mode: .login
                ),
                "Unexpectedly accepted cookie domain \(domain)"
            )
        }

        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "",
                domain: "linux.do",
                expiresAt: nil,
                now: now,
                mode: .login
            )
        )
    }

    func testCookiePolicySeparatesLoginAndChallengeEvidence() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)

        XCTAssertTrue(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "session",
                domain: "linux.do",
                expiresAt: nil,
                now: now,
                mode: .login
            )
        )
        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "_t",
                value: "session",
                domain: "linux.do",
                expiresAt: nil,
                now: now,
                mode: .challenge
            )
        )
        XCTAssertTrue(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "cf_clearance",
                value: "clearance",
                domain: ".linux.do",
                expiresAt: nil,
                now: now,
                mode: .challenge
            )
        )
        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.isEligible(
                name: "cf_clearance",
                value: "",
                domain: ".linux.do",
                expiresAt: nil,
                now: now,
                mode: .challenge
            )
        )
        XCTAssertTrue(
            RestrictedLinuxDoCookiePolicy.hasRequiredEvidence(
                cookieNames: ["_t"],
                mode: .login
            )
        )
        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.hasRequiredEvidence(
                cookieNames: [],
                mode: .login
            )
        )
        XCTAssertFalse(
            RestrictedLinuxDoCookiePolicy.hasRequiredEvidence(
                cookieNames: ["_t"],
                mode: .challenge
            )
        )
        XCTAssertTrue(
            RestrictedLinuxDoCookiePolicy.hasRequiredEvidence(
                cookieNames: ["cf_clearance"],
                mode: .challenge
            )
        )
    }

    @MainActor
    func testCookieHandoffCoordinatorRejectsConcurrentOwner() {
        let first = UUID()
        let second = UUID()
        XCTAssertTrue(RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: first))
        XCTAssertFalse(RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: second))

        RestrictedLinuxDoCookieHandoffCoordinator.release(owner: first)
        XCTAssertTrue(RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: second))
        RestrictedLinuxDoCookieHandoffCoordinator.release(owner: second)
    }

    @MainActor
    func testCancelledOwnerLateCallbackCannotClearReplacementCookieBuffer() throws {
        let cancelledOwner = UUID()
        let replacementOwner = UUID()
        let unrelatedOwner = UUID()
        let replacementCookie = try XCTUnwrap(
            HTTPCookie(
                properties: [
                    .domain: "linux.do",
                    .path: "/",
                    .name: "_t",
                    .value: "replacement-session",
                    .secure: "TRUE"
                ]
            )
        )

        XCTAssertTrue(RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: cancelledOwner))
        RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(
            ifOwnedBy: cancelledOwner
        )
        RestrictedLinuxDoCookieHandoffCoordinator.release(owner: cancelledOwner)

        XCTAssertTrue(RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: replacementOwner))
        HTTPCookieStorage.shared.setCookie(replacementCookie)
        defer {
            RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(
                ifOwnedBy: replacementOwner
            )
            RestrictedLinuxDoCookieHandoffCoordinator.release(owner: replacementOwner)
        }

        // This is the completion order produced when an uncancellable WK cookie read returns after
        // cancel() released its owner and a replacement sheet has already populated the buffer.
        XCTAssertFalse(
            RestrictedLinuxDoCookieHandoffCoordinator.clearSharedLinuxDoCookies(
                ifOwnedBy: cancelledOwner
            )
        )
        RestrictedLinuxDoCookieHandoffCoordinator.release(owner: cancelledOwner)

        let storedReplacement = (HTTPCookieStorage.shared.cookies ?? []).contains { cookie in
            cookie.domain.lowercased() == "linux.do"
                && cookie.name == "_t"
                && cookie.path == "/"
                && cookie.value == "replacement-session"
        }
        XCTAssertTrue(storedReplacement)
        XCTAssertFalse(RestrictedLinuxDoCookieHandoffCoordinator.acquire(owner: unrelatedOwner))
    }
}
