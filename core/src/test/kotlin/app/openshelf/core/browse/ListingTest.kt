package app.openshelf.core.browse

import app.openshelf.core.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingTest {
    @Test
    fun sortKeepsFoldersFirstAndHonorsDirection() {
        val arranged = Listing.arrange(sample(), ListingOptions(SortKey.SIZE, SortDirection.DESC))
        assertEquals(
            listOf("album", "big.jpg", "clip.mp4", "readme", "notes.txt", "small.txt"),
            arranged.map { it.name },
        )
        val byTime = Listing.arrange(sample(), ListingOptions(SortKey.MODIFIED, SortDirection.ASC))
        assertEquals("album", byTime.first().name)
        assertEquals(listOf("big.jpg", "small.txt", "clip.mp4", "notes.txt", "readme"), byTime.drop(1).map { it.name })
    }

    @Test
    fun kindAndNameFilters() {
        val images = Listing.arrange(sample(), ListingOptions(kind = FileKind.IMAGE))
        assertEquals(listOf("big.jpg"), images.map { it.name })
        val docs = Listing.arrange(sample(), ListingOptions(query = "note", kind = FileKind.DOCUMENT))
        assertEquals(listOf("notes.txt"), docs.map { it.name })
        val folders = Listing.arrange(sample(), ListingOptions(query = "alb"))
        assertEquals(listOf("album"), folders.map { it.name })
    }

    @Test
    fun cookieRoundTripAndUnsafePaths() {
        val options = ListingOptions(SortKey.MODIFIED, SortDirection.DESC, FileKind.VIDEO, "片段")
        val restored = Listing.decodeCookie(Listing.encodeCookie(options))
        assertEquals(options, restored)
        assertEquals(ListingOptions(), Listing.decodeCookie("nope"))
        val sized = ListingOptions(SortKey.NAME, SortDirection.DESC, FileKind.DOCUMENT, "a.b", 20)
        assertEquals(sized, Listing.decodeCookie(Listing.encodeCookie(sized)))
        assertEquals("a.b", Listing.decodeCookie("name.asc.all.a.b").query)
        assertEquals(50, Listing.decodeCookie("name.asc.all.a.b").pageSize)
        assertEquals(FileKind.IMAGE, Listing.decodeCookie("name.asc.image.a.b").kind)
        assertEquals(20, Listing.decodeCookie("name.desc.doc.20.hi").pageSize)
        assertEquals("hi", Listing.decodeCookie("name.desc.doc.20.hi").query)
        assertEquals(100, Listing.parse(mapOf("ps" to "100")).pageSize)
        assertEquals(50, Listing.parse(mapOf("ps" to "15")).pageSize)
        val items = (0 until 25).map { "n%02d".format(it) }
        val first = Listing.window(items, 1, 20)
        assertEquals(20, first.items.size)
        assertEquals("n00", first.items.first())
        assertEquals("n19", first.items.last())
        assertEquals(2, first.pages)
        assertEquals(25, first.total)
        val second = Listing.window(items, 2, 20)
        assertEquals(listOf("n20", "n21", "n22", "n23", "n24"), second.items)
        assertEquals(2, Listing.window(items, 9, 20).page)
        assertTrue(Listing.queryString(ListingOptions(pageSize = 20), page = 2).contains("ps=20"))
        assertTrue(Listing.queryString(ListingOptions(pageSize = 20), page = 2).contains("page=2"))
        assertEquals(listOf("a.txt"), Listing.childrenIn("", listOf("a.txt", "../x", "dir/a.txt", "a.txt")))
        assertEquals(listOf("dir/b.txt"), Listing.childrenIn("dir", listOf("dir/b.txt", "b.txt")))
    }

    private fun sample(): List<FileEntry> {
        return listOf(
            FileEntry("album", true, 0, 9),
            FileEntry("big.jpg", false, 100, 1),
            FileEntry("clip.mp4", false, 20, 3),
            FileEntry("notes.txt", false, 4, 4),
            FileEntry("small.txt", false, 2, 2),
            FileEntry("readme", false, 8, 5),
        )
    }
}
