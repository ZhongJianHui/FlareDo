package dev.dimension.flare.data.network.discourse

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.decodeURLPart

/**
 * Accepts only references that Discourse can safely substitute into composer Markdown.
 *
 * Core `upload://<token>` references are opaque and deliberately limited to the token alphabet
 * emitted by Discourse. URL fallbacks must remain an `/uploads/` or `/secure-uploads/` resource on
 * the fixed Linux.do HTTPS origin. Protocol-relative references are rejected even when they name
 * Linux.do because their transport security depends on the renderer's surrounding document. No CDN
 * host is implicitly trusted; adding one requires an explicit host/path rule and dedicated tests.
 */
internal fun String.isSafeDiscourseUploadReference(): Boolean {
    if (
        isBlank() ||
        length > MAX_SAFE_UPLOAD_REFERENCE_CHARS ||
        any(Char::isWhitespace) ||
        any(Char::isUploadReferenceControlOrDelimiter)
    ) {
        return false
    }
    if (startsWith(DISCOURSE_UPLOAD_SCHEME)) {
        val token = removePrefix(DISCOURSE_UPLOAD_SCHEME)
        return token.isNotEmpty() &&
            token.length <= MAX_DISCOURSE_UPLOAD_TOKEN_CHARS &&
            token.all(Char::isDiscourseUploadTokenCharacter)
    }
    if (startsWith("//")) return false

    val resolved =
        try {
            if (startsWith('/')) {
                Url("$DISCOURSE_ORIGIN$this")
            } else {
                Url(this)
            }
        } catch (_: IllegalArgumentException) {
            return false
        }
    return resolved.protocol == URLProtocol.HTTPS &&
        resolved.host.equals(LINUX_DO_UPLOAD_HOST, ignoreCase = true) &&
        resolved.port == URLProtocol.HTTPS.defaultPort &&
        resolved.user.isNullOrEmpty() &&
        resolved.password.isNullOrEmpty() &&
        resolved.encodedPath.isTrustedDiscourseUploadPath()
}

/**
 * Verifies both the visible prefix and the path that remains after repeated percent decoding.
 *
 * Checking only `startsWith("/uploads/")` is insufficient: URL implementations and HTTP servers may
 * canonicalize `/uploads/../private` after this check. The same escape can be hidden behind `%2e`,
 * mixed-case hex, or an additional `%25` layer. Each encoded path segment is therefore decoded until
 * it is stable, and dot segments or encoded path separators fail closed at every layer. Query values
 * are intentionally outside this check because signed secure-upload URLs commonly carry them.
 */
private fun String.isTrustedDiscourseUploadPath(): Boolean {
    if (!startsWith("/uploads/") && !startsWith("/secure-uploads/")) return false
    return split('/').all(String::isCanonicalUploadPathSegment)
}

private fun String.isCanonicalUploadPathSegment(): Boolean {
    // A malformed escape in the wire path has no single canonical interpretation across clients and
    // servers. Reject it before decoding; a literal percent encoded as `%25` remains valid.
    if (!hasOnlyWellFormedPercentEscapes()) return false

    var canonical = this
    repeat(MAX_UPLOAD_PATH_DECODING_PASSES) {
        if (canonical == "." || canonical == ".." || canonical.any(Char::isDecodedPathSeparator)) {
            return false
        }
        if (!canonical.containsPercentEscape()) return true
        canonical =
            try {
                canonical.decodeURLPart()
            } catch (_: Exception) {
                return false
            }
    }

    // More encoding layers than the explicit bound are not produced by Discourse. Reject rather than
    // accepting a value whose canonical path has not been established.
    return !canonical.containsPercentEscape() &&
        canonical != "." &&
        canonical != ".." &&
        canonical.none(Char::isDecodedPathSeparator)
}

private fun String.hasOnlyWellFormedPercentEscapes(): Boolean {
    var index = 0
    while (index < length) {
        if (this[index] != '%') {
            index += 1
            continue
        }
        if (
            index + 2 >= length ||
            !this[index + 1].isAsciiHexDigit() ||
            !this[index + 2].isAsciiHexDigit()
        ) {
            return false
        }
        index += 3
    }
    return true
}

private fun String.containsPercentEscape(): Boolean {
    var index = indexOf('%')
    while (index >= 0 && index + 2 < length) {
        if (this[index + 1].isAsciiHexDigit() && this[index + 2].isAsciiHexDigit()) return true
        index = indexOf('%', startIndex = index + 1)
    }
    return false
}

private fun Char.isDecodedPathSeparator(): Boolean = this == '/' || this == '\\'

private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.isDiscourseUploadTokenCharacter(): Boolean = isAsciiLetterOrDigit() || this == '-' || this == '_' || this == '.'

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isUploadReferenceControlOrDelimiter(): Boolean =
    code < 0x20 ||
        code == 0x7f ||
        this == '\\' ||
        this == '`' ||
        this == '<' ||
        this == '>' ||
        this == '"' ||
        this == '\'' ||
        this == '[' ||
        this == ']' ||
        this == '(' ||
        this == ')' ||
        this == '{' ||
        this == '}'

private const val DISCOURSE_UPLOAD_SCHEME: String = "upload://"
private const val LINUX_DO_UPLOAD_HOST: String = "linux.do"
private const val MAX_SAFE_UPLOAD_REFERENCE_CHARS: Int = 4_096
private const val MAX_DISCOURSE_UPLOAD_TOKEN_CHARS: Int = 512
private const val MAX_UPLOAD_PATH_DECODING_PASSES: Int = 16
