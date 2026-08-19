package dev.dimension.flare.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiscourseAuthRedirectPolicyTest {
    @Test
    fun coldAndWarmDeliveriesUseTheSameValidationAndDelegationPath() =
        runTest {
            val calls = mutableListOf<DiscourseAuthRedirectCallback>()
            val audit = RecordingAuditLogger()
            val dispatcher =
                DiscourseAuthRedirectDispatcher(
                    sink =
                        DiscourseAuthRedirectSink { callback ->
                            calls += callback
                            DiscourseAuthRedirectSinkResult.Accepted
                        },
                    auditLogger = audit,
                )
            val validation = validate(validCandidate())

            val cold = dispatcher.dispatch(validation, DiscourseAuthRedirectEntryPoint.ColdStart)
            val warm = dispatcher.dispatch(validation, DiscourseAuthRedirectEntryPoint.WarmStart)

            assertIs<DiscourseAuthRedirectDispatchResult.Accepted>(cold)
            assertIs<DiscourseAuthRedirectDispatchResult.Accepted>(warm)
            assertEquals(2, calls.size)
            assertEquals(emptyList(), audit.records)
            assertFalse(calls.first().toString().contains("self-authored-payload"))
        }

    @Test
    fun forgedActionComponentPackageSchemeAndAuthorityAreRejected() {
        assertRejected(
            validCandidate(action = "android.intent.action.SEND"),
            DiscourseAuthRedirectAuditEvent.InvalidAction,
        )
        assertRejected(
            validCandidate(componentClass = "dev.dimension.flare.MainActivity"),
            DiscourseAuthRedirectAuditEvent.InvalidComponent,
        )
        assertRejected(
            validCandidate(packageName = "example.attacker"),
            DiscourseAuthRedirectAuditEvent.InvalidPackage,
        )
        assertRejected(
            validCandidate(uriScheme = "https"),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )
        assertRejected(
            validCandidate(uriAuthority = "attacker.invalid"),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )
        assertRejected(
            validCandidate(uriPath = "/forward"),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )
        assertRejected(
            validCandidate(uriFragment = "payload-copy"),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )
    }

    @Test
    fun grantsNestedIntentExtrasAndOtherDynamicMembersFailClosed() {
        assertRejected(
            validCandidate(hasUriGrantFlags = true),
            DiscourseAuthRedirectAuditEvent.UriGrantBlocked,
        )
        assertRejected(
            validCandidate(hasUnsupportedFlags = true),
            DiscourseAuthRedirectAuditEvent.UnsupportedFlags,
        )
        assertRejected(
            validCandidate(categories = setOf(CATEGORY_DEFAULT)),
            DiscourseAuthRedirectAuditEvent.InvalidCategories,
        )
        assertRejected(
            validCandidate(categories = setOf(CATEGORY_BROWSABLE, "attacker.category")),
            DiscourseAuthRedirectAuditEvent.InvalidCategories,
        )
        assertRejected(
            validCandidate(hasNestedIntent = true, hasExtras = true),
            DiscourseAuthRedirectAuditEvent.NestedIntentBlocked,
        )
        assertRejected(
            validCandidate(hasExtras = true),
            DiscourseAuthRedirectAuditEvent.ExtrasBlocked,
        )
        assertRejected(
            validCandidate(hasClipData = true),
            DiscourseAuthRedirectAuditEvent.ClipDataBlocked,
        )
        assertRejected(
            validCandidate(hasSelector = true),
            DiscourseAuthRedirectAuditEvent.SelectorBlocked,
        )
        assertRejected(
            validCandidate(mimeType = "text/plain"),
            DiscourseAuthRedirectAuditEvent.MimeTypeBlocked,
        )
        assertRejected(
            validCandidate(hasIdentifier = true),
            DiscourseAuthRedirectAuditEvent.IdentifierBlocked,
        )
        assertRejected(
            validCandidate(hasSourceBounds = true),
            DiscourseAuthRedirectAuditEvent.SourceBoundsBlocked,
        )
    }

    @Test
    fun callbackUriMustBePresentAndRemainWithinTheBoundedEnvelope() {
        assertRejected(
            validCandidate(encodedUri = null),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )
        assertRejected(
            validCandidate(encodedUri = ""),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )
        assertRejected(
            validCandidate(encodedUri = "x".repeat(MAX_CALLBACK_URI_CHARS + 1)),
            DiscourseAuthRedirectAuditEvent.InvalidCallbackUri,
        )

        val maximumLengthUri =
            VALID_CALLBACK.padEnd(MAX_CALLBACK_URI_CHARS, 'x')
        assertIs<DiscourseAuthRedirectValidation.Accepted>(
            validate(validCandidate(encodedUri = maximumLengthUri)),
        )
    }

    @Test
    fun replayDecisionIsDelegatedToTheSingleUseCommonSink() =
        runTest {
            val seen = mutableSetOf<String>()
            val audit = RecordingAuditLogger()
            var calls = 0
            val dispatcher =
                DiscourseAuthRedirectDispatcher(
                    sink =
                        DiscourseAuthRedirectSink { callback ->
                            calls += 1
                            if (seen.add(callback.encodedUri)) {
                                DiscourseAuthRedirectSinkResult.Accepted
                            } else {
                                DiscourseAuthRedirectSinkResult.Rejected
                            }
                        },
                    auditLogger = audit,
                )
            val validation = validate(validCandidate())

            assertIs<DiscourseAuthRedirectDispatchResult.Accepted>(
                dispatcher.dispatch(validation, DiscourseAuthRedirectEntryPoint.ColdStart),
            )
            assertIs<DiscourseAuthRedirectDispatchResult.Rejected>(
                dispatcher.dispatch(validation, DiscourseAuthRedirectEntryPoint.WarmStart),
            )

            assertEquals(2, calls)
            assertEquals(
                listOf(
                    DiscourseAuthRedirectAuditEvent.SinkRejected to
                        DiscourseAuthRedirectEntryPoint.WarmStart,
                ),
                audit.records,
            )
        }

    @Test
    fun missingOrFailingSinkProducesOnlyFixedAuditEvents() =
        runTest {
            val unavailableAudit = RecordingAuditLogger()
            val validation = validate(validCandidate())
            val unavailable =
                DiscourseAuthRedirectDispatcher(null, unavailableAudit)
                    .dispatch(validation, DiscourseAuthRedirectEntryPoint.ColdStart)

            val failureAudit = RecordingAuditLogger()
            val failing =
                DiscourseAuthRedirectDispatcher(
                    sink = DiscourseAuthRedirectSink { error("sink failure containing secret") },
                    auditLogger = failureAudit,
                ).dispatch(validation, DiscourseAuthRedirectEntryPoint.WarmStart)

            assertIs<DiscourseAuthRedirectDispatchResult.Rejected>(unavailable)
            assertIs<DiscourseAuthRedirectDispatchResult.Rejected>(failing)
            assertEquals(
                listOf(
                    DiscourseAuthRedirectAuditEvent.SinkUnavailable to
                        DiscourseAuthRedirectEntryPoint.ColdStart,
                ),
                unavailableAudit.records,
            )
            assertEquals(
                listOf(
                    DiscourseAuthRedirectAuditEvent.SinkFailure to
                        DiscourseAuthRedirectEntryPoint.WarmStart,
                ),
                failureAudit.records,
            )
            assertTrue(unavailableAudit.toString().contains("[redacted]"))
            assertFalse(failureAudit.toString().contains("secret"))
        }

    @Test
    fun lifecycleCancellationIsNeverConvertedIntoAnAuthenticationFailure() =
        runTest {
            val audit = RecordingAuditLogger()
            val dispatcher =
                DiscourseAuthRedirectDispatcher(
                    sink = DiscourseAuthRedirectSink { throw CancellationException("destroyed") },
                    auditLogger = audit,
                )

            assertFailsWith<CancellationException> {
                dispatcher.dispatch(
                    validate(validCandidate()),
                    DiscourseAuthRedirectEntryPoint.ColdStart,
                )
            }
            assertEquals(emptyList(), audit.records)
        }
}

private fun validate(candidate: DiscourseAuthRedirectCandidate): DiscourseAuthRedirectValidation =
    DiscourseAuthRedirectPolicy.validate(
        candidate = candidate,
        expectedPackage = EXPECTED_PACKAGE,
        expectedComponentClass = EXPECTED_COMPONENT,
    )

private fun assertRejected(
    candidate: DiscourseAuthRedirectCandidate,
    expected: DiscourseAuthRedirectAuditEvent,
) {
    val rejected = assertIs<DiscourseAuthRedirectValidation.Rejected>(validate(candidate))
    assertEquals(expected, rejected.event)
}

@Suppress("LongParameterList")
private fun validCandidate(
    action: String? = ACTION_VIEW,
    componentPackage: String? = EXPECTED_PACKAGE,
    componentClass: String? = EXPECTED_COMPONENT,
    packageName: String? = null,
    hasUriGrantFlags: Boolean = false,
    hasUnsupportedFlags: Boolean = false,
    categories: Set<String> = setOf(CATEGORY_BROWSABLE),
    hasNestedIntent: Boolean = false,
    hasExtras: Boolean = false,
    hasClipData: Boolean = false,
    hasSelector: Boolean = false,
    mimeType: String? = null,
    hasIdentifier: Boolean = false,
    hasSourceBounds: Boolean = false,
    uriScheme: String? = CALLBACK_SCHEME,
    uriAuthority: String? = CALLBACK_AUTHORITY,
    uriPath: String? = "",
    uriFragment: String? = null,
    encodedUri: String? = VALID_CALLBACK,
): DiscourseAuthRedirectCandidate =
    DiscourseAuthRedirectCandidate(
        action = action,
        componentPackage = componentPackage,
        componentClass = componentClass,
        packageName = packageName,
        hasUriGrantFlags = hasUriGrantFlags,
        hasUnsupportedFlags = hasUnsupportedFlags,
        categories = categories,
        hasNestedIntent = hasNestedIntent,
        hasExtras = hasExtras,
        hasClipData = hasClipData,
        hasSelector = hasSelector,
        mimeType = mimeType,
        hasIdentifier = hasIdentifier,
        hasSourceBounds = hasSourceBounds,
        uriScheme = uriScheme,
        uriAuthority = uriAuthority,
        uriPath = uriPath,
        uriFragment = uriFragment,
        encodedUri = encodedUri,
    )

private class RecordingAuditLogger : DiscourseAuthRedirectAuditLogger {
    val records = mutableListOf<Pair<DiscourseAuthRedirectAuditEvent, DiscourseAuthRedirectEntryPoint>>()

    override fun record(
        event: DiscourseAuthRedirectAuditEvent,
        entryPoint: DiscourseAuthRedirectEntryPoint,
    ) {
        records += event to entryPoint
    }

    override fun toString(): String = "RecordingAuditLogger([redacted])"
}

private const val EXPECTED_PACKAGE: String = "io.github.zhongjianhui.flaredo"
private const val EXPECTED_COMPONENT: String =
    "dev.dimension.flare.auth.DiscourseAuthRedirectActivity"
private const val VALID_CALLBACK: String =
    "discourse://auth_redirect?payload=self-authored-payload&nonce=single-use"
