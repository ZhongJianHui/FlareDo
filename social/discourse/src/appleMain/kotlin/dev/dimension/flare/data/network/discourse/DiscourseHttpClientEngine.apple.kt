package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun createDiscourseHttpClient(
    cookieStorage: DiscourseCookieStorage,
    userAgent: String?,
): HttpClient {
    val client =
        HttpClient(Darwin) {
            configureDiscourseHttpClient(cookieStorage, userAgent)
        }
    return client
}
