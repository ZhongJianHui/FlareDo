package dev.dimension.flare.data.network.discourse.session

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Verifies the production Android Keystore backend inside a real Android process.
 *
 * Host tests inject an authenticated fake because the AndroidKeyStore provider is unavailable on
 * the JVM. This device test intentionally uses the public production constructor so a missing
 * provider, an invalid key specification, or an incompatible AES-GCM implementation fails CI.
 */
internal class AndroidKeystoreActualRoundTripTest {
    @Test
    fun realKeystoreRoundTripsAndRemovesCredential(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = AndroidKeystoreCredentialStore(context)
            val secret = "flaredo-android-keystore-round-trip-fixture".encodeToByteArray()
            var cleanupReference: SecureCredentialRef? = null
            var primaryFailure: Throwable? = null

            try {
                val reference = store.save("device-test", secret)
                cleanupReference = reference

                val loaded =
                    requireNotNull(store.load(reference)) {
                        "Android Keystore lost the saved credential"
                    }
                try {
                    assertContentEquals(secret, loaded)
                } finally {
                    loaded.fill(0)
                }

                store.delete(reference)
                assertNull(store.load(reference), "Android Keystore retained the deleted credential")
                cleanupReference = null
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                secret.fill(0)
                cleanupReference?.let { reference ->
                    withContext(NonCancellable) {
                        try {
                            store.delete(reference)
                        } catch (cleanupFailure: Throwable) {
                            primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                        }
                    }
                }
            }
        }
}
