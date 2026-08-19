package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

internal class AppleKeychainActualRoundTripTest {
    @Test
    fun genericPasswordRoundTripUsesTheLoginKeychain() =
        runTest {
            val store = AppleKeychainCredentialStore()
            val secret = "macos-keychain-round-trip-fixture".encodeToByteArray()
            val reference = store.save("macos-test-account", secret)
            try {
                assertContentEquals(secret, store.load(reference))
            } finally {
                store.delete(reference)
            }
            assertNull(store.load(reference))
        }
}
