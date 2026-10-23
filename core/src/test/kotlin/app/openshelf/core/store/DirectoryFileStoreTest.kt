package app.openshelf.core.store

import app.openshelf.core.browse.ListingOptions
import app.openshelf.core.http.browsePage
import app.openshelf.core.text.TextFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryFileStoreTest {
    @Test
    fun listAndHtmlUseRealByteSizes() {
        val root = Files.createTempDirectory("openshelf-sizes").toFile()
        try {
            File(root, "empty.jpg").writeBytes(ByteArray(0))
            File(root, "Default_photo.jpg").writeBytes(ByteArray(350_000))
            val five = File(root, "Default_20251219_221552.jpg")
            five.outputStream().use { output ->
                val chunk = ByteArray(1024 * 1024)
                repeat(5) { output.write(chunk) }
            }
            val huge = File(root, "huge.bin")
            RandomAccessFile(huge, "rw").use { it.setLength(TWO_GIB_PLUS) }

            val store = DirectoryFileStore(listOf(DirectoryFileStore.Root("share", "分享夾", root)))
            val entries = store.list("share", "").associateBy { it.name }
            assertEquals(0L, entries.getValue("empty.jpg").size)
            assertEquals(350_000L, entries.getValue("Default_photo.jpg").size)
            assertEquals(5L * 1024 * 1024, entries.getValue("Default_20251219_221552.jpg").size)
            assertEquals(TWO_GIB_PLUS, entries.getValue("huge.bin").size)

            val html = browsePage("分享夾", "share", "", entries.values.toList(), ListingOptions(), false)
            assertTrue(html.contains("0 B"))
            assertTrue(html.contains("341.8 KB"))
            assertTrue(html.contains("5.0 MB"))
            assertTrue(html.contains("2.0 GB"))
            assertFalse(html.contains("5.0 GB"))
            assertEquals("5.0 MB", TextFormat.size(entries.getValue("Default_20251219_221552.jpg").size))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TWO_GIB_PLUS: Long = 2L * 1024 * 1024 * 1024 + 1
    }
}
