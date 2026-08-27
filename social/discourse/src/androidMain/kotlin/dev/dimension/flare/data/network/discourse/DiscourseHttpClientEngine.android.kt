package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createDiscourseHttpClient(
    cookieStorage: DiscourseCookieStorage,
    userAgent: String?,
): HttpClient {
    val client =
        HttpClient(OkHttp) {
            configureDiscourseHttpClient(cookieStorage, userAgent)
        }
    return client
}
