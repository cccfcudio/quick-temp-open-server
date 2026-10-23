package app.openshelf.core.http

/**
 * A small JPEG for one image or one video frame.
 * Return null when this file has no preview. Do not return the original bytes.
 */
fun interface JpegPreview {
    fun jpeg(mountId: String, relativePath: String, maxEdge: Int): ByteArray?
}
