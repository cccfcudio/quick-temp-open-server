package app.openshelf.core.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class FileIconsTest {
    @Test
    fun mapsDirectoriesAndMainstreamExtensions() {
        assertEquals(FileIcon.FOLDER, FileIcons.of("song.mp3", true))
        assertEquals(FileIcon.FOLDER, FileIcons.of("album", true))
        assertEquals(FileIcon.IMAGE, FileIcons.of("Default.JPG", false))
        assertEquals(FileIcon.AUDIO, FileIcons.of("track.mp3", false))
        assertEquals(FileIcon.VIDEO, FileIcons.of("clip.mp4", false))
        assertEquals(FileIcon.PDF, FileIcons.of("notes.pdf", false))
        assertEquals(FileIcon.ARCHIVE, FileIcons.of("bundle.zip", false))
        assertEquals(FileIcon.TEXT, FileIcons.of("readme.md", false))
        assertEquals(FileIcon.CODE, FileIcons.of("Main.kt", false))
        assertEquals(FileIcon.SHEET, FileIcons.of("table.csv", false))
        assertEquals(FileIcon.SLIDES, FileIcons.of("deck.pptx", false))
        assertEquals(FileIcon.OTHER, FileIcons.of("readme", false))
        assertEquals(FileIcon.OTHER, FileIcons.of("voice.mp3.bak", false))
    }
}
