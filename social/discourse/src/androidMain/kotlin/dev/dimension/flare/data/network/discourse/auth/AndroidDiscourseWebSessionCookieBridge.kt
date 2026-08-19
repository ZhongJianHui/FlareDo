package dev.dimension.flare.data.network.discourse.auth

import android.webkit.CookieManager
import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieEncoding
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import io.ktor.http.parseClientCookiesHeader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val MAX_WEB_COOKIE_HEADER_BYTES: Int = 64 * 1024

/** Android WebView CookieManager bridge restricted to the fixed Linux.do HTTPS origin. */
public class AndroidDiscourseWebSessionCookieBridge internal constructor(
    private val mainDispatcher: CoroutineDispatcher,
    private val backend: AndroidWebCookieBackend,
) : DiscourseWebSessionCookieBridge {
    public constructor(
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : this(mainDispatcher, CookieManagerWebCookieBackend)

    override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> =
        withContext(mainDispatcher) {
            val header = backend.getCookie(DISCOURSE_ORIGIN) ?: return@withContext emptyList()
            parseBoundedWebCookieHeader(header).also { cookies ->
                DiscourseCookieStorage().requireValidSnapshot(cookies)
            }
        }

    override suspend fun clearLinuxDoCookies() {
        withContext(mainDispatcher) {
            val names =
                backend
                    .getCookie(DISCOURSE_ORIGIN)
                    ?.let(::parseBoundedWebCookieHeader)
                    .orEmpty()
                    .map(DiscourseCookieSnapshot::name)
            names.forEach { name ->
                backend.expireCookie(
                    origin = DISCOURSE_ORIGIN,
                    cookie = "$name=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax",
                )
            }
            backend.flush()
        }
    }
}

/** Narrow test seam; production code cannot change the bridge origin. */
internal interface AndroidWebCookieBackend {
    fun getCookie(origin: String): String?

    suspend fun expireCookie(
        origin: String,
        cookie: String,
    )

    fun flush()
}

private object CookieManagerWebCookieBackend : AndroidWebCookieBackend {
    private val manager: CookieManager
        get() = CookieManager.getInstance()

    override fun getCookie(origin: String): String? = manager.getCookie(origin)

    override suspend fun expireCookie(
        origin: String,
        cookie: String,
    ) {
        suspendCancellableCoroutine { continuation ->
            manager.setCookie(origin, cookie) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override fun flush() {
        manager.flush()
    }
}

internal fun parseBoundedWebCookieHeader(header: String): List<DiscourseCookieSnapshot> {
    require(header.isNotBlank()) { "The WebView cookie header is empty" }
    require(header.length <= MAX_WEB_COOKIE_HEADER_BYTES) { "The WebView cookie header is too large" }
    require(header.encodeToByteArray().size <= MAX_WEB_COOKIE_HEADER_BYTES) {
        "The WebView cookie header is too large"
    }
    require(header.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "The WebView cookie header contains a forbidden control character"
    }

    val segments = header.split(';').map(String::trim)
    require(segments.isNotEmpty() && segments.all { it.contains('=') }) {
        "The WebView cookie header is malformed"
    }
    val parsed = parseClientCookiesHeader(header, skipEscaped = true)
    val names = segments.map { it.substringBefore('=').trim() }
    require(names.size == names.distinct().size && parsed.keys == names.toSet()) {
        "The WebView cookie header contains ambiguous fields"
    }
    return parsed.map { (name, value) ->
        DiscourseCookieSnapshot(
            name = name,
            value = value,
            encoding = DiscourseCookieEncoding.RAW,
            domain = "linux.do",
            path = "/",
            secure = true,
            // CookieManager.getCookie includes HttpOnly values even though JavaScript cannot read
            // them. Marking the known Discourse session name preserves that server-side property.
            httpOnly = name == "_t",
        )
    }
}
