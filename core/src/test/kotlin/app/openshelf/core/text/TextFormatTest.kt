package app.openshelf.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormatTest {
    @Test
    fun sizeUsesBinaryUnitsWithoutSkippingAStep() {
        assertEquals("0 B", TextFormat.size(0))
        assertEquals("1023 B", TextFormat.size(1023))
        assertEquals("1.0 KB", TextFormat.size(1024))
        assertEquals("1.5 KB", TextFormat.size(1536))
        assertEquals("341.8 KB", TextFormat.size(350_000))
        assertEquals("1.0 MB", TextFormat.size(1024L * 1024))
        // A 5.0 MiB camera JPEG must not be labeled 5.0 GB.
        assertEquals("5.0 MB", TextFormat.size(5L * 1024 * 1024))
        assertEquals("5.1 MB", TextFormat.size((5.1 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", TextFormat.size(1024L * 1024 * 1024))
        assertEquals("2.0 GB", TextFormat.size(2L * 1024 * 1024 * 1024 + 1))
        assertEquals("5.0 GB", TextFormat.size(5L * 1024 * 1024 * 1024))
        assertEquals("—", TextFormat.size(-1))
    }

    @Test
    fun zeroMetadataUsesStatOnlyWhenTheFileIsNonEmpty() {
        assertEquals(350_000L, TextFormat.chooseByteSize(0, 350_000))
        assertEquals(0L, TextFormat.chooseByteSize(0, 0))
        assertEquals(0L, TextFormat.chooseByteSize(0, -1))
        assertEquals(5L * 1024 * 1024, TextFormat.chooseByteSize(5L * 1024 * 1024, 0))
    }
}
