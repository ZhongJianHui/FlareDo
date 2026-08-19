package dev.dimension.flare.auth

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.dimension.flare.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The sole exported endpoint for `discourse://auth_redirect`.
 *
 * Both lifecycle entry points call [handleIncomingIntent]. No field from the untrusted Intent is
 * copied into an outgoing Intent: returning to the app always uses a fixed explicit MainActivity
 * component. The common authentication sink owns cryptographic validation and one-time nonce
 * consumption after the Android envelope has passed this component's strict allowlist.
 */
class DiscourseAuthRedirectActivity : ComponentActivity() {
    private val validator = DiscourseAuthIntentValidator()
    private val auditLogger = AndroidDiscourseAuthRedirectAuditLogger
    private var dispatchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showProcessing()
        handleIncomingIntent(intent, DiscourseAuthRedirectEntryPoint.ColdStart)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keeping Activity.intent current is required before applying the exact same warm-path checks.
        setIntent(intent)
        showProcessing()
        handleIncomingIntent(intent, DiscourseAuthRedirectEntryPoint.WarmStart)
    }

    private fun handleIncomingIntent(
        untrustedIntent: Intent,
        entryPoint: DiscourseAuthRedirectEntryPoint,
    ) {
        // Lifecycle callbacks run on Main, so this check and assignment form one serialized gate.
        // Keeping the first callback avoids cancelling an exchange after its nonce was consumed.
        if (dispatchJob?.isActive == true) {
            setIntent(Intent())
            auditLogger.record(DiscourseAuthRedirectAuditEvent.DispatchInProgress, entryPoint)
            return
        }

        val expectedComponent = ComponentName(this, DiscourseAuthRedirectActivity::class.java)
        val validation = validator.validate(untrustedIntent, expectedComponent)

        // Do not retain an Activity-level reference to a rejected URI/payload after validation.
        setIntent(Intent())
        val sink =
            (application as? DiscourseAuthRedirectSinkOwner)
                ?.discourseAuthRedirectSink
        val dispatcher =
            DiscourseAuthRedirectDispatcher(
                sink = sink,
                auditLogger = auditLogger,
            )
        dispatchJob =
            lifecycleScope.launch {
                when (dispatcher.dispatch(validation, entryPoint)) {
                    DiscourseAuthRedirectDispatchResult.Accepted -> returnToMainActivity()
                    DiscourseAuthRedirectDispatchResult.Rejected -> showFailure()
                }
            }
    }

    private fun showProcessing() {
        val content = statusContainer()
        content.addView(ProgressBar(this))
        content.addView(
            statusText(
                text = "Authorization in progress",
                bold = false,
            ),
        )
        setContentView(content)
    }

    private fun showFailure() {
        val content = statusContainer()
        content.addView(
            statusText(
                text = "Unable to complete authorization",
                bold = true,
            ),
        )
        content.addView(
            Button(this).apply {
                text = "Return to FlareDo"
                setOnClickListener { returnToMainActivity() }
            },
        )
        setContentView(content)
    }

    private fun statusContainer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
        }

    private fun statusText(
        text: String,
        bold: Boolean,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16.dp, 0, 16.dp)
        }

    /** Fixed explicit navigation prevents callback-controlled open redirects. */
    private fun returnToMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
        finish()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

private object AndroidDiscourseAuthRedirectAuditLogger : DiscourseAuthRedirectAuditLogger {
    private const val TAG: String = "FlareDoAuthRedirect"

    override fun record(
        event: DiscourseAuthRedirectAuditEvent,
        entryPoint: DiscourseAuthRedirectEntryPoint,
    ) {
        // Both values are closed enums. Never append an Intent, URI, exception, or sink payload.
        Log.w(TAG, "${entryPoint.name}:${event.name}")
    }
}
