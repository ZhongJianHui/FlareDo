package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64

internal actual fun createPlatformDiscourseAuthTokenGenerator(): DiscourseAuthTokenGenerator = JavaSecureDiscourseAuthTokenGenerator()

internal class JavaSecureDiscourseAuthTokenGenerator(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val secureRandom: SecureRandom = SecureRandom(),
) : DiscourseAuthTokenGenerator {
    override suspend fun generate(byteCount: Int): String =
        withContext(dispatcher) {
            require(byteCount in 16..128) { "Authorization random-byte count is outside the bound" }
            val bytes = ByteArray(byteCount)
            try {
                secureRandom.nextBytes(bytes)
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            } finally {
                bytes.fill(0)
            }
        }
}
