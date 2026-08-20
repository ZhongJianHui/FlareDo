package dev.dimension.flare.data.network.discourse

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DiscourseUploadRequestTest {
    @Test
    fun constructorAccessorCopyAndComponentsNeverExposeOwnedBytes() {
        val source = byteArrayOf(1, 2, 3)
        val request =
            DiscourseUploadRequest(
                bytes = source,
                fileName = "private-fixture-name.bin",
                messageBusClientId = "private-fixture-client",
            )
        val stableHash = request.hashCode()

        source[0] = 9
        request.bytes[1] = 9
        val (componentBytes) = request
        componentBytes[2] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), request.bytes)
        assertEquals(stableHash, request.hashCode())
        assertEquals(request, request.copy())
        assertEquals(
            DiscourseUploadRequest(
                bytes = byteArrayOf(1, 2, 3),
                fileName = "private-fixture-name.bin",
                messageBusClientId = "private-fixture-client",
            ),
            request,
        )
        assertFalse(request.toString().contains("private-fixture-name"))
        assertFalse(request.toString().contains("private-fixture-client"))
    }

    @Test
    fun publicCopiesStayDefensiveWhileTransportBorrowsOneBoundedSnapshot() {
        val source = byteArrayOf(1, 2, 3)
        val request = DiscourseUploadRequest(bytes = source, fileName = "fixture.bin")
        val firstTransportBorrow = request.borrowOwnedBytesForTransport()

        assertNotSame(source, firstTransportBorrow)
        assertNotSame(request.bytes, firstTransportBorrow)
        assertSame(firstTransportBorrow, request.borrowOwnedBytesForTransport())

        val replacement = byteArrayOf(4, 5, 6)
        val copied = request.copy(bytes = replacement)
        replacement[0] = 9
        copied.bytes[1] = 9
        assertContentEquals(byteArrayOf(4, 5, 6), copied.bytes)

        val maximum =
            DiscourseUploadRequest(
                bytes = ByteArray(MAX_DISCOURSE_UPLOAD_BYTES),
                fileName = "maximum.bin",
            )
        assertEquals(MAX_DISCOURSE_UPLOAD_BYTES, maximum.borrowOwnedBytesForTransport().size)
        assertFailsWith<IllegalArgumentException> {
            DiscourseUploadRequest(
                bytes = ByteArray(MAX_DISCOURSE_UPLOAD_BYTES + 1),
                fileName = "too-large.bin",
            )
        }
    }

    @Test
    fun uploadReferenceAllowlistIsFixedToDiscourseTokensAndLinuxDoUploadPaths() {
        assertTrue("upload://Abc_123-file.png".isSafeDiscourseUploadReference())
        assertTrue("/uploads/default/fixture.png".isSafeDiscourseUploadReference())
        assertTrue("/secure-uploads/default/fixture.pdf?token=fixture".isSafeDiscourseUploadReference())
        assertTrue("https://linux.do/uploads/default/fixture.png".isSafeDiscourseUploadReference())
        assertTrue("/uploads/default/percent%25name.png".isSafeDiscourseUploadReference())

        listOf(
            "javascript:fixture",
            "data:text/plain,fixture",
            "uploads/default/fixture.png",
            "//linux.do/uploads/default/fixture.png",
            "//untrusted.invalid/uploads/default/fixture.png",
            "https://untrusted.invalid/uploads/default/fixture.png",
            "https://linux.do/not-an-upload/fixture.png",
            "/uploads/../session/current.json",
            "/uploads/./fixture.png",
            "/uploads/%2e%2e/session/current.json",
            "https://linux.do/secure-uploads/%2E/fixture.png",
            "/uploads/%252e%252E/session/current.json",
            "/uploads/default%2F..%2Fsession/current.json",
            "upload://fixture) ![unsafe](javascript:fixture",
        ).forEach { reference ->
            assertFalse(reference.isSafeDiscourseUploadReference(), reference)
        }
    }
}
