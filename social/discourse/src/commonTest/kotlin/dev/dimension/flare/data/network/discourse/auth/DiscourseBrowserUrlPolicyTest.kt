package dev.dimension.flare.data.network.discourse.auth

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DiscourseBrowserUrlPolicyTest {
    @Test
    fun topLevelPolicyAcceptsOnlyExactPortlessLinuxDoHttpsAuthority() {
        assertTrue(DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl("https://linux.do"))
        assertTrue(DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl("https://linux.do/login?safe=true"))

        listOf(
            "http://linux.do/login",
            "https://linux.do:443/login",
            "https://linux.do:444/login",
            "https://linux.do.evil.invalid/login",
            "https://linux.do@evil.invalid/login",
            "https://member:secret@linux.do/login",
            "https://assets.linux.do/login",
            "https://LINUX.DO/login",
            "https://linux.do%2eevil.invalid/login",
        ).forEach { candidate ->
            assertFalse(
                DiscourseBrowserUrlPolicy.isAllowedTopLevelUrl(candidate),
                "Unexpectedly accepted $candidate",
            )
        }
    }

    @Test
    fun externalAuthorizationRequiresTheFixedUserApiKeyPath() {
        assertTrue(
            DiscourseBrowserUrlPolicy.isExternalAuthorizationUrl(
                "https://linux.do/user-api-key/new?nonce=fixture",
            ),
        )
        assertFalse(DiscourseBrowserUrlPolicy.isExternalAuthorizationUrl("https://linux.do/login"))
        assertFalse(
            DiscourseBrowserUrlPolicy.isExternalAuthorizationUrl(
                "https://linux.do/user-api-key/new#fragment",
            ),
        )
    }

    @Test
    fun authorizationValueRejectsLookalikesAndRedactsItsUrl() {
        val value =
            DiscourseExternalAuthorization(
                requestId = 1L,
                url = "https://linux.do/user-api-key/new?nonce=do-not-print",
                expiresAtEpochMillis = 2L,
            )
        assertFalse(value.toString().contains("do-not-print"))

        assertFailsWith<IllegalArgumentException> {
            DiscourseExternalAuthorization(
                requestId = 1L,
                url = "https://linux.do.evil.invalid/user-api-key/new",
                expiresAtEpochMillis = 2L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DiscourseExternalAuthorization(
                requestId = 1L,
                url = "https://linux.do:443/user-api-key/new",
                expiresAtEpochMillis = 2L,
            )
        }
    }
}
