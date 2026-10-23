package app.openshelf.core.browse

object PreviewSizing {
    const val DEFAULT_EDGE: Int = 160
    const val MIN_EDGE: Int = 48
    const val MAX_EDGE: Int = 512

    fun edge(raw: String?): Int {
        val parsed = raw?.toIntOrNull() ?: return DEFAULT_EDGE
        return parsed.coerceIn(MIN_EDGE, MAX_EDGE)
    }

    /** Power-of-two sample so the long edge lands near [maxEdge] before a final scale. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) {
            return 1
        }
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxEdge && sample < 64) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }
}
