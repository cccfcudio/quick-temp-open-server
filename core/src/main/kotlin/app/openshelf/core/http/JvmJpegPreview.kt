package app.openshelf.core.http

import app.openshelf.core.browse.FileIcon
import app.openshelf.core.browse.FileIcons
import app.openshelf.core.browse.PreviewSizing
import app.openshelf.core.store.FileStore
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Desktop/JVM previews. ImageIO reads the whole picture, so this is only for
 * the JVM dev server and tests. The phone decodes with a sample size instead.
 * Videos stay icon-only here; a frame grab needs Android.
 */
class JvmJpegPreview(private val store: FileStore) : JpegPreview {
    private val cache = LinkedHashMap<String, ByteArray>()

    override fun jpeg(mountId: String, relativePath: String, maxEdge: Int): ByteArray? {
        val stat = try {
            store.stat(mountId, relativePath)
        } catch (error: Exception) {
            null
        } ?: return null
        if (stat.directory) {
            return null
        }
        val icon = FileIcons.of(stat.name, false)
        if (icon != FileIcon.IMAGE || stat.name.endsWith(".svg", ignoreCase = true)) {
            return null
        }
        if (stat.size > MAX_BYTES) {
            return null
        }
        val edge = maxEdge.coerceIn(PreviewSizing.MIN_EDGE, PreviewSizing.MAX_EDGE)
        val key = "$mountId|$relativePath|${stat.modifiedEpochMs}|${stat.size}|$edge"
        synchronized(cache) { cache[key] }?.let { return it }
        val encoded = encode(mountId, relativePath, edge) ?: return null
        synchronized(cache) {
            cache[key] = encoded
            while (cache.size > 64) {
                val eldest = cache.entries.firstOrNull()?.key ?: break
                cache.remove(eldest)
            }
        }
        return encoded
    }

    private fun encode(mountId: String, relativePath: String, edge: Int): ByteArray? {
        val image = try {
            store.openRead(mountId, relativePath).use { input -> ImageIO.read(input) }
        } catch (error: Exception) {
            null
        } ?: return null
        val longEdge = max(image.width, image.height).coerceAtLeast(1)
        val scale = min(1.0, edge.toDouble() / longEdge.toDouble())
        val width = max(1, (image.width * scale).roundToInt())
        val height = max(1, (image.height * scale).roundToInt())
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(scaled, "jpeg", out)) {
            return null
        }
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
    }

    private companion object {
        const val MAX_BYTES: Long = 32L * 1024L * 1024L
    }
}
