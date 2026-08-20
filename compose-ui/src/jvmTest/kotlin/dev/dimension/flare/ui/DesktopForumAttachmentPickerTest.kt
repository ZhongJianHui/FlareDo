package dev.dimension.flare.ui

import dev.dimension.flare.data.network.discourse.MAX_DISCOURSE_UPLOAD_BYTES
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class DesktopForumAttachmentPickerTest {
    @Test
    fun sharedUploadCapIsSixteenMiB() {
        assertEquals(16 * 1024 * 1024, MAX_DISCOURSE_UPLOAD_BYTES)
    }

    @Test
    fun selectedFileReturnsBytesAndLeafMetadataOnly() {
        val directory = Files.createTempDirectory("flaredo-picker-test")
        val file = directory.resolve("attachment.txt")
        val expected = "bounded fixture".encodeToByteArray()
        try {
            Files.write(file, expected)

            val result = assertIs<ForumAttachmentPickResult.Selected>(readDesktopForumAttachment(file))

            assertContentEquals(expected, result.attachment.bytes)
            assertEquals("attachment.txt", result.attachment.fileName)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun sixteenMiBBoundaryIsAcceptedWithoutTruncation() {
        val file = Files.createTempFile("flaredo-picker-exact-limit", ".bin")
        try {
            RandomAccessFile(file.toFile(), "rw").use { handle ->
                handle.setLength(MAX_DISCOURSE_UPLOAD_BYTES.toLong())
            }

            val result = assertIs<ForumAttachmentPickResult.Selected>(readDesktopForumAttachment(file))
            assertEquals(MAX_DISCOURSE_UPLOAD_BYTES, result.attachment.bytes.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun byteBeyondSixteenMiBIsRejectedBeforeAllocatingIt() {
        val file = Files.createTempFile("flaredo-picker-limit", ".bin")
        try {
            RandomAccessFile(file.toFile(), "rw").use { handle ->
                handle.setLength(MAX_DISCOURSE_UPLOAD_BYTES.toLong() + 1L)
            }

            assertIs<ForumAttachmentPickResult.TooLarge>(readDesktopForumAttachment(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun emptyFileIsRejectedAsUnreadableUploadInput() {
        val file = Files.createTempFile("flaredo-picker-empty", ".bin")
        try {
            assertIs<ForumAttachmentPickResult.ReadFailed>(readDesktopForumAttachment(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
