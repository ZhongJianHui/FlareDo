package dev.dimension.flare.auth

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentSanitizer

/**
 * Android adapter around the pure redirect policy.
 *
 * Extras are an opaque attacker-controlled parcel at this boundary. The initial projection reads
 * only safe scalar fields and whether separately stored dynamic members exist; it never queries an
 * extra key, size, or value. After policy rejects grants, ClipData, selector, type, identifier, and
 * source bounds, the adapter builds a fresh Intent from allowlisted fields only. It never invokes
 * Intent's recursive copy constructor. [IntentSanitizer.sanitizeByThrowing] independently enforces
 * that defensive envelope before its URI is delegated.
 */
internal class DiscourseAuthIntentValidator {
    fun validate(
        untrustedIntent: Intent,
        expectedComponent: ComponentName,
    ): DiscourseAuthRedirectValidation {
        val initial =
            try {
                untrustedIntent.toCandidate()
            } catch (_: RuntimeException) {
                return rejected(DiscourseAuthRedirectAuditEvent.MalformedIntent)
            }
        val initialValidation =
            DiscourseAuthRedirectPolicy.validate(
                candidate = initial,
                expectedPackage = expectedComponent.packageName,
                expectedComponentClass = expectedComponent.className,
            )
        if (initialValidation is DiscourseAuthRedirectValidation.Rejected) {
            return initialValidation
        }

        val defensiveIntent =
            try {
                initial.toDefensiveIntent()
            } catch (_: RuntimeException) {
                return rejected(DiscourseAuthRedirectAuditEvent.MalformedIntent)
            }
        val sanitized =
            try {
                sanitizer(expectedComponent).sanitizeByThrowing(defensiveIntent)
            } catch (_: SecurityException) {
                return rejected(DiscourseAuthRedirectAuditEvent.SanitizerRejected)
            } catch (_: RuntimeException) {
                return rejected(DiscourseAuthRedirectAuditEvent.MalformedIntent)
            }

        return try {
            DiscourseAuthRedirectPolicy.validate(
                candidate = sanitized.toCandidate(),
                expectedPackage = expectedComponent.packageName,
                expectedComponentClass = expectedComponent.className,
            )
        } catch (_: RuntimeException) {
            rejected(DiscourseAuthRedirectAuditEvent.MalformedIntent)
        }
    }

    private fun sanitizer(expectedComponent: ComponentName): IntentSanitizer =
        IntentSanitizer
            .Builder()
            .allowComponent(expectedComponent)
            .allowPackage(expectedComponent.packageName)
            .allowAction(Intent.ACTION_VIEW)
            .allowCategory(Intent.CATEGORY_DEFAULT)
            .allowCategory(Intent.CATEGORY_BROWSABLE)
            .allowFlags(ALLOWED_ACTIVITY_FLAGS)
            .allowData(::isAllowedCallbackData)
            .build()
}

/** Projects only scalar routing fields and dynamic-member presence; it never accesses [Intent.getExtras]. */
internal fun Intent.toCandidate(): DiscourseAuthRedirectCandidate {
    val callbackData = data
    return DiscourseAuthRedirectCandidate(
        action = action,
        componentPackage = component?.packageName,
        componentClass = component?.className,
        packageName = `package`,
        activityFlags = flags,
        hasUriGrantFlags = flags and URI_GRANT_FLAGS != 0,
        hasUnsupportedFlags = flags and ALLOWED_ACTIVITY_FLAGS.inv() != 0,
        categories = categories?.toSet().orEmpty(),
        hasClipData = clipData != null,
        hasSelector = selector != null,
        mimeType = type,
        hasIdentifier = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && identifier != null,
        hasSourceBounds = sourceBounds != null,
        uriScheme = callbackData?.scheme,
        uriAuthority = callbackData?.encodedAuthority,
        uriPath = callbackData?.encodedPath,
        uriFragment = callbackData?.encodedFragment,
        encodedUri = callbackData?.toString(),
    )
}

/**
 * Reconstructs the validated routing envelope without copying any attacker-owned object container.
 *
 * In particular, no extras, ClipData, selector, MIME type, identifier, or source bounds can enter
 * this Intent. The caller must run the pure policy first so every required field is non-null and the
 * raw activity flags contain only the browser/task allowlist.
 */
internal fun DiscourseAuthRedirectCandidate.toDefensiveIntent(): Intent =
    Intent().apply {
        action = this@toDefensiveIntent.action
        data = Uri.parse(checkNotNull(encodedUri))
        component =
            ComponentName(
                checkNotNull(componentPackage),
                checkNotNull(componentClass),
            )
        `package` = packageName
        this@toDefensiveIntent.categories.forEach { category -> addCategory(category) }
        flags = activityFlags
    }

private fun isAllowedCallbackData(uri: Uri): Boolean =
    DiscourseAuthRedirectPolicy.isAllowedCallbackUri(
        scheme = uri.scheme,
        authority = uri.encodedAuthority,
        path = uri.encodedPath,
        fragment = uri.encodedFragment,
        encodedUri = uri.toString(),
    )

private fun rejected(event: DiscourseAuthRedirectAuditEvent): DiscourseAuthRedirectValidation =
    DiscourseAuthRedirectValidation.Rejected(event)

private const val URI_GRANT_FLAGS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

/** Task-navigation flags used by browsers/framework delivery and carrying no URI permission. */
private const val ALLOWED_ACTIVITY_FLAGS: Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
        // Android mirrors the exported Activity's excludeFromRecents manifest policy here.
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
