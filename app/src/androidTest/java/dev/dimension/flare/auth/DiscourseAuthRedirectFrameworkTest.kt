package dev.dimension.flare.auth

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.App
import dev.dimension.flare.MainActivity
import dev.dimension.flare.data.network.discourse.auth.DiscourseLoginService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the exported callback boundary against a real Android framework and installed manifest. */
class DiscourseAuthRedirectFrameworkTest {
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val targetContext: Context
        get() = instrumentation.targetContext

    private val redirectComponent: ComponentName
        get() = ComponentName(targetContext, DiscourseAuthRedirectActivity::class.java)

    @Test
    fun installedManifestKeepsTheRedirectEntryPointNarrow() {
        val packageManager = targetContext.packageManager
        val redirectInfo = packageManager.activityInfo(redirectComponent)
        val mainComponent = ComponentName(targetContext, MainActivity::class.java)

        assertTrue(redirectInfo.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, redirectInfo.launchMode)
        assertTrue(redirectInfo.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(redirectInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(redirectInfo.taskAffinity.isNullOrEmpty())
        assertTrue(packageManager.activityInfo(mainComponent).exported)
        assertEquals(
            setOf(mainComponent.className, redirectComponent.className),
            packageManager
                .packageInfo(targetContext.packageName)
                .activities
                .orEmpty()
                .mapTo(linkedSetOf()) { it.name },
        )

        assertTrue(redirectComponent in packageManager.resolvedActivities(EXACT_CALLBACK_URI))
        assertTrue(redirectComponent !in packageManager.resolvedActivities("https://auth_redirect?payload=AA%3D%3D"))
        assertTrue(redirectComponent !in packageManager.resolvedActivities("discourse://outside?payload=AA%3D%3D"))
    }

    @Test
    fun frameworkIntentSanitizerRejectsEveryDynamicAttackSurface() {
        val validator = DiscourseAuthIntentValidator()
        assertIs<DiscourseAuthRedirectValidation.Accepted>(
            validator.validate(validFrameworkIntent(), redirectComponent),
        )

        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidAction) {
            action = Intent.ACTION_SEND
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidComponent) {
            component = ComponentName(targetContext, MainActivity::class.java)
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidPackage) {
            `package` = "example.attacker"
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidCategories) {
            addCategory("example.attacker.CATEGORY")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidCallbackUri) {
            data = Uri.parse("https://auth_redirect?payload=AA%3D%3D")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidCallbackUri) {
            data = Uri.parse("discourse://outside?payload=AA%3D%3D")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidCallbackUri) {
            data = Uri.parse("discourse://auth_redirect/path?payload=AA%3D%3D")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.InvalidCallbackUri) {
            data = Uri.parse("$EXACT_CALLBACK_URI#fragment")
        }

        URI_GRANT_FLAGS.forEach { grantFlag ->
            assertRejected(DiscourseAuthRedirectAuditEvent.UriGrantBlocked) {
                addFlags(grantFlag)
            }
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.UnsupportedFlags) {
            addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION)
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.NestedIntentBlocked) {
            putExtra(Intent.EXTRA_INTENT, Intent(targetContext, MainActivity::class.java))
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.ExtrasBlocked) {
            putExtra("io.github.zhongjianhui.flaredo.UNTRUSTED", "value")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.ExtrasBlocked) {
            putExtra(
                "io.github.zhongjianhui.flaredo.NESTED",
                Intent(targetContext, MainActivity::class.java),
            )
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.ClipDataBlocked) {
            clipData = ClipData.newPlainText("untrusted", "value")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.SelectorBlocked) {
            selector = Intent(Intent.ACTION_SEND)
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.MimeTypeBlocked) {
            setDataAndType(data, "text/plain")
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.IdentifierBlocked) {
            identifier = "untrusted"
        }
        assertRejected(DiscourseAuthRedirectAuditEvent.SourceBoundsBlocked) {
            sourceBounds = Rect(0, 0, 1, 1)
        }
    }

    @Test
    fun coldAndWarmMaliciousIntentsNeverReachTheProductionSink() =
        runBlocking {
            val application = targetContext.applicationContext as App
            val loginService = application.koin.get<DiscourseLoginService>()
            val pending = loginService.beginAuthorization()
            val callbackUri = callbackWithAuthenticatedNonceAndInvalidApiKey(pending.url.toString())
            var pendingAttemptOwned = true

            try {
                val coldIntent =
                    validFrameworkIntent(callbackUri).apply {
                        putExtra("io.github.zhongjianhui.flaredo.UNTRUSTED", "cold")
                    }
                val activity = launchRedirectActivity(coldIntent)
                try {
                    var activityIdentity = 0
                    instrumentation.runOnMainSync {
                        activity.assertRejectedAndCleared()
                        activityIdentity = System.identityHashCode(activity)
                        activity.setIntent(Intent("io.github.zhongjianhui.flaredo.SENTINEL"))

                        // Starting the top singleTop Activity routes this through the real onNewIntent.
                        activity.startActivity(
                            validFrameworkIntent(callbackUri).apply {
                                putExtra(
                                    Intent.EXTRA_INTENT,
                                    Intent(targetContext, MainActivity::class.java),
                                )
                            },
                        )
                    }

                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals(activityIdentity, System.identityHashCode(activity))
                        activity.assertRejectedAndCleared()
                    }
                } finally {
                    finishRedirectActivity(activity)
                }

                // The correlated callback would consume the attempt before rejecting its bad API key
                // if either malicious envelope reached App.discourseAuthRedirectSink.
                assertTrue(loginService.cancelAuthorization())
                pendingAttemptOwned = false
            } finally {
                if (pendingAttemptOwned) {
                    withContext(NonCancellable) {
                        loginService.cancelAuthorization()
                    }
                }
            }
        }

    @Test
    fun failureReturnUsesOnlyTheFixedExplicitMainActivityIntent() {
        val capturedIntent = AtomicReference<Intent?>()
        val mainComponent = ComponentName(targetContext, MainActivity::class.java)
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component != mainComponent) return null
                    capturedIntent.set(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            val rejectedIntent =
                validFrameworkIntent().apply {
                    clipData = ClipData.newPlainText("untrusted", "must-not-be-forwarded")
                }
            val activity = launchRedirectActivity(rejectedIntent)
            try {
                instrumentation.runOnMainSync {
                    activity.requireReturnButton().performClick()
                }
                instrumentation.waitForIdleSync()
            } finally {
                finishRedirectActivity(activity)
            }

            val outbound = assertNotNull(capturedIntent.get())
            assertEquals(mainComponent, outbound.component)
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, outbound.flags)
            assertNull(outbound.action)
            assertNull(outbound.data)
            assertNull(outbound.`package`)
            assertNull(outbound.categories)
            assertNull(outbound.extras)
            assertNull(outbound.clipData)
            assertNull(outbound.selector)
            assertNull(outbound.type)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertRejected(
        expectedEvent: DiscourseAuthRedirectAuditEvent,
        mutate: Intent.() -> Unit,
    ) {
        val result =
            DiscourseAuthIntentValidator().validate(
                validFrameworkIntent().apply(mutate),
                redirectComponent,
            )
        assertEquals(
            expectedEvent,
            assertIs<DiscourseAuthRedirectValidation.Rejected>(result).event,
        )
    }

    private fun validFrameworkIntent(uri: String = EXACT_CALLBACK_URI): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri), targetContext, DiscourseAuthRedirectActivity::class.java).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

    /**
     * ActivityScenario identifies an Activity by comparing its retained Intent after onCreate.
     * Production deliberately clears that untrusted object during onCreate, so use the platform
     * Instrumentation waiter, which captures the instance immediately before onCreate instead.
     */
    private fun launchRedirectActivity(intent: Intent): DiscourseAuthRedirectActivity {
        val activity =
            instrumentation.startActivitySync(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        instrumentation.waitForIdleSync()
        return assertIs<DiscourseAuthRedirectActivity>(activity)
    }

    private fun finishRedirectActivity(activity: DiscourseAuthRedirectActivity) {
        instrumentation.runOnMainSync {
            if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
        }
        instrumentation.waitForIdleSync()
    }

    private fun PackageManager.resolvedActivities(uri: String): Set<ComponentName> =
        queryIntentActivitiesCompat(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                `package` = targetContext.packageName
            },
        ).mapTo(linkedSetOf()) { result ->
            ComponentName(result.activityInfo.packageName, result.activityInfo.name)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.activityInfo(component: ComponentName): ActivityInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0L))
        } else {
            getActivityInfo(component, 0)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        } else {
            getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.queryIntentActivitiesCompat(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
}

private fun DiscourseAuthRedirectActivity.assertRejectedAndCleared() {
    assertNull(intent.action)
    assertNull(intent.data)
    assertNull(intent.extras)
    assertNotNull(requireReturnButton())
}

private fun DiscourseAuthRedirectActivity.requireReturnButton(): Button =
    findViewById<ViewGroup>(android.R.id.content).findReturnButton()
        ?: error("The fail-closed authorization view did not expose its return action")

private fun View.findReturnButton(): Button? {
    if (this is Button && text.toString() == "Return to FlareDo") return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findReturnButton()?.let { return it }
    }
    return null
}

private fun callbackWithAuthenticatedNonceAndInvalidApiKey(authorizationUrl: String): String {
    val authorizationUri = Uri.parse(authorizationUrl)
    val nonce = checkNotNull(authorizationUri.getQueryParameter("nonce"))
    val publicKeyPem = checkNotNull(authorizationUri.getQueryParameter("public_key"))
    val payload = "{\"key\":\"invalid key with spaces\",\"nonce\":\"$nonce\",\"api\":4}"
    val encryptedPayload = encryptPkcs1(publicKeyPem, payload.encodeToByteArray())
    return Uri
        .Builder()
        .scheme(CALLBACK_SCHEME)
        .authority(CALLBACK_AUTHORITY)
        .appendQueryParameter("payload", Base64.getEncoder().encodeToString(encryptedPayload))
        // Parsing succeeds, but the invalid API key stops processing before this field is decrypted.
        .appendQueryParameter("oneTimePassword", "AA==")
        .build()
        .toString()
        .also { encryptedPayload.fill(0) }
}

private fun encryptPkcs1(
    publicKeyPem: String,
    plaintext: ByteArray,
): ByteArray {
    val publicKey = publicKeyPem.decodeRsaPublicKey()
    return try {
        Cipher
            .getInstance("RSA/ECB/PKCS1Padding")
            .apply { init(Cipher.ENCRYPT_MODE, publicKey) }
            .doFinal(plaintext)
    } finally {
        plaintext.fill(0)
    }
}

private fun String.decodeRsaPublicKey(): PublicKey {
    val der =
        Base64
            .getDecoder()
            .decode(
                removePrefix("-----BEGIN PUBLIC KEY-----")
                    .removeSuffix("-----END PUBLIC KEY-----")
                    .filterNot(Char::isWhitespace),
            )
    return try {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    } finally {
        der.fill(0)
    }
}

private const val EXACT_CALLBACK_URI: String =
    "discourse://auth_redirect?payload=AA%3D%3D&oneTimePassword=AA%3D%3D"

private val URI_GRANT_FLAGS: List<Int> =
    listOf(
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )
