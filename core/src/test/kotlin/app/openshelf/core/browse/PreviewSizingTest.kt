package app.openshelf.core.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSizingTest {
    @Test
    fun sampleSizeShrinksTheLongEdge() {
        assertEquals(1, PreviewSizing.sampleSize(100, 80, 160))
        assertEquals(16, PreviewSizing.sampleSize(4000, 3000, 160))
        assertEquals(32, PreviewSizing.sampleSize(10000, 200, 160))
        assertEquals(1, PreviewSizing.sampleSize(0, 3000, 160))
    }

    @Test
    fun edgeIsClamped() {
        assertEquals(PreviewSizing.DEFAULT_EDGE, PreviewSizing.edge(null))
        assertEquals(PreviewSizing.DEFAULT_EDGE, PreviewSizing.edge("nope"))
        assertEquals(PreviewSizing.MIN_EDGE, PreviewSizing.edge("10"))
        assertEquals(PreviewSizing.MAX_EDGE, PreviewSizing.edge("9000"))
        assertEquals(96, PreviewSizing.edge("96"))
    }
}
