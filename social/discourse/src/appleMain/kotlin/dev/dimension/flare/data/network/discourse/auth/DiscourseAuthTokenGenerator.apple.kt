@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dimension.flare.data.network.discourse.auth

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal actual fun createPlatformDiscourseAuthTokenGenerator(): DiscourseAuthTokenGenerator = AppleSecureDiscourseAuthTokenGenerator()

internal class AppleSecureDiscourseAuthTokenGenerator(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DiscourseAuthTokenGenerator {
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun generate(byteCount: Int): String =
        withContext(dispatcher) {
            require(byteCount in 16..128) { "Authorization random-byte count is outside the bound" }
            val bytes = ByteArray(byteCount)
            try {
                val status =
                    bytes.usePinned { pinned ->
                        SecRandomCopyBytes(kSecRandomDefault, byteCount.toULong(), pinned.addressOf(0))
                    }
                check(status == errSecSuccess) { "Apple secure random generation failed" }
                Base64.UrlSafe.encode(bytes).trimEnd('=')
            } finally {
                bytes.fill(0)
            }
        }
}
