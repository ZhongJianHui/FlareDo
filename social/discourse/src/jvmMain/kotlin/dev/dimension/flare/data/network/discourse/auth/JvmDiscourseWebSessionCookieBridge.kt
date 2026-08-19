package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieEncoding
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.CookieManager
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import kotlin.time.Clock

/**
 * Windows/Linux browser Cookie bridge backed by a host-owned JDK [CookieStore].
 *
 * The desktop WebView host must synchronize only its fixed `https://linux.do` profile into the
 * supplied store. This adapter still treats that store as untrusted: it asks only for the fixed
 * origin, rejects cookies whose declared domain is not exactly Linux.do, normalizes every cookie to
 * the HTTPS-only shared representation, and applies the same aggregate bounds as the network jar.
 * No default global store is used, so FlareDo cannot enumerate cookies belonging to another app.
 */
public class JvmDiscourseWebSessionCookieBridge private constructor(
    private val cookieStore: CookieStore,
    private val nowEpochMillis: () -> Long,
) : DiscourseWebSessionCookieBridge {
    private val operationMutex: Mutex = Mutex()

    /** Uses the private [CookieStore] shared by the restricted desktop WebView profile. */
    public constructor(
        cookieManager: CookieManager,
    ) : this(
        cookieStore = cookieManager.cookieStore,
        nowEpochMillis = { Clock.System.now().toEpochMilliseconds() },
    )

    internal constructor(
        cookieStore: CookieStore,
        nowEpochMillis: () -> Long,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(cookieStore, nowEpochMillis)

    override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> =
        operationMutex.withLock {
            val now = nowEpochMillis()
            cookieStore
                .get(LINUX_DO_URI)
                .asSequence()
                .filterNot(HttpCookie::hasExpired)
                .filter(HttpCookie::belongsToLinuxDo)
                .map { cookie -> cookie.toSnapshot(now) }
                .toList()
                .also { cookies -> DiscourseCookieStorage().requireValidSnapshot(cookies) }
        }

    override suspend fun clearLinuxDoCookies() {
        operationMutex.withLock {
            cookieStore
                .get(LINUX_DO_URI)
                .filter(HttpCookie::belongsToLinuxDo)
                .forEach { cookie ->
                    // CookieStore permits a null URI for domain-indexed cookies. Try the exact
                    // origin first, then the domain index without touching any unrelated value.
                    if (!cookieStore.remove(LINUX_DO_URI, cookie)) {
                        cookieStore.remove(null, cookie)
                    }
                }
        }
    }
}

private fun HttpCookie.belongsToLinuxDo(): Boolean {
    val declaredDomain = domain?.removePrefix(".")?.lowercase()
    // A null domain is a host-only cookie selected by CookieStore.get(LINUX_DO_URI).
    return declaredDomain == null || declaredDomain == LINUX_DO_HOST
}

private fun HttpCookie.toSnapshot(nowEpochMillis: Long): DiscourseCookieSnapshot =
    DiscourseCookieSnapshot(
        name = name,
        value = value,
        encoding = DiscourseCookieEncoding.RAW,
        expiresAtEpochMillis = maxAge.toAbsoluteExpiry(nowEpochMillis),
        domain = LINUX_DO_HOST,
        path = path?.takeIf(String::isNotBlank) ?: "/",
        secure = true,
        httpOnly = isHttpOnly || name == "_t",
    )

/** Saturates attacker-controlled Max-Age values instead of overflowing into an expired timestamp. */
private fun Long.toAbsoluteExpiry(nowEpochMillis: Long): Long? =
    when {
        this < 0L -> null
        this > (Long.MAX_VALUE - nowEpochMillis) / 1_000L -> Long.MAX_VALUE
        else -> nowEpochMillis + this * 1_000L
    }

private const val LINUX_DO_HOST: String = "linux.do"
private val LINUX_DO_URI: URI = URI.create("$DISCOURSE_ORIGIN/")
