package dev.dimension.flare.auth

/**
 * A callback URI that passed the Android component, intent-shape, and route allowlists.
 *
 * The encoded value still contains authentication material. Consumers must pass it directly to the
 * common RSA/nonce verifier and must never log it. [toString] is deliberately redacted so routine
 * diagnostics cannot accidentally disclose the payload.
 */
class DiscourseAuthRedirectCallback internal constructor(
    val encodedUri: String,
) {
    init {
        require(encodedUri.isNotEmpty() && encodedUri.length <= MAX_CALLBACK_URI_CHARS)
    }

    override fun equals(other: Any?): Boolean = other is DiscourseAuthRedirectCallback && encodedUri == other.encodedUri

    override fun hashCode(): Int = encodedUri.hashCode()

    override fun toString(): String = "DiscourseAuthRedirectCallback([redacted])"
}

/** Result returned after the validated callback is offered to the bounded in-memory inbox. */
enum class DiscourseAuthRedirectSinkResult {
    Accepted,
    Rejected,
}

/**
 * Exported-Activity-to-process-memory authorization bridge.
 *
 * Implementations must only enqueue the callback in bounded process memory. RSA decryption, nonce
 * and expiry validation, and the OTP exchange run later through the visible Activity's retained
 * authentication presenter. This synchronous boundary guarantees the exported Activity never waits
 * for network or challenge work before returning to the fixed app destination.
 */
fun interface DiscourseAuthRedirectSink {
    /** Must be fast and main-safe; callback data must never be persisted or logged. */
    fun enqueue(callback: DiscourseAuthRedirectCallback): DiscourseAuthRedirectSinkResult
}

/** Implemented by the Application once the common authentication graph is available. */
interface DiscourseAuthRedirectSinkOwner {
    val discourseAuthRedirectSink: DiscourseAuthRedirectSink
}

/** Cold and warm deliveries are recorded without retaining any callback data. */
internal enum class DiscourseAuthRedirectEntryPoint {
    ColdStart,
    WarmStart,
}

/** Fixed, non-sensitive security events. No event contains an URI, query, payload, or exception. */
internal enum class DiscourseAuthRedirectAuditEvent {
    MalformedIntent,
    InvalidAction,
    InvalidComponent,
    InvalidPackage,
    UriGrantBlocked,
    UnsupportedFlags,
    InvalidCategories,
    ClipDataBlocked,
    SelectorBlocked,
    MimeTypeBlocked,
    IdentifierBlocked,
    SourceBoundsBlocked,
    InvalidCallbackUri,
    SanitizerRejected,
    SinkUnavailable,
    SinkRejected,
    SinkFailure,
}

internal fun interface DiscourseAuthRedirectAuditLogger {
    fun record(
        event: DiscourseAuthRedirectAuditEvent,
        entryPoint: DiscourseAuthRedirectEntryPoint,
    )
}

/**
 * Android-free projection of an untrusted Intent.
 *
 * Keeping policy evaluation free of framework objects makes every rejection branch deterministic in
 * local tests. [toString] remains redacted because [encodedUri] contains the callback query.
 */
internal class DiscourseAuthRedirectCandidate(
    val action: String?,
    val componentPackage: String?,
    val componentClass: String?,
    val packageName: String?,
    val activityFlags: Int,
    val hasUriGrantFlags: Boolean,
    val hasUnsupportedFlags: Boolean,
    val categories: Set<String>,
    val hasClipData: Boolean,
    val hasSelector: Boolean,
    val mimeType: String?,
    val hasIdentifier: Boolean,
    val hasSourceBounds: Boolean,
    val uriScheme: String?,
    val uriAuthority: String?,
    val uriPath: String?,
    val uriFragment: String?,
    val encodedUri: String?,
) {
    override fun toString(): String = "DiscourseAuthRedirectCandidate([redacted])"
}

internal sealed interface DiscourseAuthRedirectValidation {
    data class Accepted(
        val callback: DiscourseAuthRedirectCallback,
    ) : DiscourseAuthRedirectValidation

    data class Rejected(
        val event: DiscourseAuthRedirectAuditEvent,
    ) : DiscourseAuthRedirectValidation
}

/** Strict allowlist shared by the Android adapter and pure tests. */
internal object DiscourseAuthRedirectPolicy {
    fun validate(
        candidate: DiscourseAuthRedirectCandidate,
        expectedPackage: String,
        expectedComponentClass: String,
    ): DiscourseAuthRedirectValidation {
        val rejection =
            when {
                candidate.action != ACTION_VIEW -> {
                    DiscourseAuthRedirectAuditEvent.InvalidAction
                }

                candidate.componentPackage != expectedPackage ||
                    candidate.componentClass != expectedComponentClass -> {
                    DiscourseAuthRedirectAuditEvent.InvalidComponent
                }

                candidate.packageName != null && candidate.packageName != expectedPackage -> {
                    DiscourseAuthRedirectAuditEvent.InvalidPackage
                }

                candidate.hasUriGrantFlags -> {
                    DiscourseAuthRedirectAuditEvent.UriGrantBlocked
                }

                candidate.hasUnsupportedFlags -> {
                    DiscourseAuthRedirectAuditEvent.UnsupportedFlags
                }

                CATEGORY_BROWSABLE !in candidate.categories ||
                    candidate.categories.any { it !in ALLOWED_CATEGORIES } -> {
                    DiscourseAuthRedirectAuditEvent.InvalidCategories
                }

                candidate.hasClipData -> {
                    DiscourseAuthRedirectAuditEvent.ClipDataBlocked
                }

                candidate.hasSelector -> {
                    DiscourseAuthRedirectAuditEvent.SelectorBlocked
                }

                candidate.mimeType != null -> {
                    DiscourseAuthRedirectAuditEvent.MimeTypeBlocked
                }

                candidate.hasIdentifier -> {
                    DiscourseAuthRedirectAuditEvent.IdentifierBlocked
                }

                candidate.hasSourceBounds -> {
                    DiscourseAuthRedirectAuditEvent.SourceBoundsBlocked
                }

                !isAllowedCallbackUri(candidate) -> {
                    DiscourseAuthRedirectAuditEvent.InvalidCallbackUri
                }

                else -> {
                    null
                }
            }
        if (rejection != null) return DiscourseAuthRedirectValidation.Rejected(rejection)
        return DiscourseAuthRedirectValidation.Accepted(
            DiscourseAuthRedirectCallback(checkNotNull(candidate.encodedUri)),
        )
    }

    fun isAllowedCallbackUri(candidate: DiscourseAuthRedirectCandidate): Boolean =
        isAllowedCallbackUri(
            scheme = candidate.uriScheme,
            authority = candidate.uriAuthority,
            path = candidate.uriPath,
            fragment = candidate.uriFragment,
            encodedUri = candidate.encodedUri,
        )

    fun isAllowedCallbackUri(
        scheme: String?,
        authority: String?,
        path: String?,
        fragment: String?,
        encodedUri: String?,
    ): Boolean {
        val value = encodedUri ?: return false
        return value.isNotEmpty() &&
            value.length <= MAX_CALLBACK_URI_CHARS &&
            scheme == CALLBACK_SCHEME &&
            authority == CALLBACK_AUTHORITY &&
            path.isNullOrEmpty() &&
            fragment == null
    }
}

internal sealed interface DiscourseAuthRedirectDispatchResult {
    data object Accepted : DiscourseAuthRedirectDispatchResult

    data object Rejected : DiscourseAuthRedirectDispatchResult
}

/** Serial, one-call delegation boundary between the validated Android callback and its inbox. */
internal class DiscourseAuthRedirectDispatcher(
    private val sink: DiscourseAuthRedirectSink?,
    private val auditLogger: DiscourseAuthRedirectAuditLogger,
) {
    fun dispatch(
        validation: DiscourseAuthRedirectValidation,
        entryPoint: DiscourseAuthRedirectEntryPoint,
    ): DiscourseAuthRedirectDispatchResult =
        when (validation) {
            is DiscourseAuthRedirectValidation.Rejected -> {
                auditLogger.record(validation.event, entryPoint)
                DiscourseAuthRedirectDispatchResult.Rejected
            }

            is DiscourseAuthRedirectValidation.Accepted -> {
                val target = sink
                if (target == null) {
                    auditLogger.record(DiscourseAuthRedirectAuditEvent.SinkUnavailable, entryPoint)
                    DiscourseAuthRedirectDispatchResult.Rejected
                } else {
                    try {
                        when (target.enqueue(validation.callback)) {
                            DiscourseAuthRedirectSinkResult.Accepted -> {
                                DiscourseAuthRedirectDispatchResult.Accepted
                            }

                            DiscourseAuthRedirectSinkResult.Rejected -> {
                                auditLogger.record(
                                    DiscourseAuthRedirectAuditEvent.SinkRejected,
                                    entryPoint,
                                )
                                DiscourseAuthRedirectDispatchResult.Rejected
                            }
                        }
                    } catch (_: Exception) {
                        auditLogger.record(DiscourseAuthRedirectAuditEvent.SinkFailure, entryPoint)
                        DiscourseAuthRedirectDispatchResult.Rejected
                    }
                }
            }
        }
}

internal const val ACTION_VIEW: String = "android.intent.action.VIEW"
internal const val CATEGORY_DEFAULT: String = "android.intent.category.DEFAULT"
internal const val CATEGORY_BROWSABLE: String = "android.intent.category.BROWSABLE"
internal const val CALLBACK_SCHEME: String = "discourse"
internal const val CALLBACK_AUTHORITY: String = "auth_redirect"
internal const val MAX_CALLBACK_URI_CHARS: Int = 16 * 1024

private val ALLOWED_CATEGORIES: Set<String> = setOf(CATEGORY_DEFAULT, CATEGORY_BROWSABLE)
