package app.openshelf.share

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.openshelf.OpenShelfApp
import app.openshelf.core.browse.FileIcon
import app.openshelf.core.browse.FileIcons
import app.openshelf.core.browse.PreviewSizing
import app.openshelf.core.http.JpegPreview
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Phone previews. Still images are sampled down before decode. Videos contribute
 * one scaled frame. Results are cached as small JPEGs so list cells never hold
 * the original file.
 */
class AndroidJpegPreview(app: Application) : JpegPreview {
    private val context: Context = app.applicationContext
    private val store = AndroidFileStore(context) {
        (context as OpenShelfApp).prefs.currentMounts()
    }
    private val cache = object : LruCache<String, ByteArray>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val gate = Semaphore(2)

    override fun jpeg(mountId: String, relativePath: String, maxEdge: Int): ByteArray? {
        val edge = maxEdge.coerceIn(PreviewSizing.MIN_EDGE, PreviewSizing.MAX_EDGE)
        val stat = try {
            store.stat(mountId, relativePath)
        } catch (error: Exception) {
            null
        } ?: return null
        if (stat.directory) {
            return null
        }
        val kind = FileIcons.of(stat.name, false)
        if (kind != FileIcon.IMAGE && kind != FileIcon.VIDEO) {
            return null
        }
        if (kind == FileIcon.IMAGE && (stat.name.endsWith(".svg", ignoreCase = true) || stat.size > IMAGE_LIMIT)) {
            return null
        }
        val key = "$mountId|$relativePath|${stat.modifiedEpochMs}|${stat.size}|$edge"
        cached(key)?.let { return it }
        val acquired = try {
            gate.acquire()
            true
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            return null
        }
        try {
            cached(key)?.let { return it }
            val target = store.previewTarget(mountId, relativePath) ?: return null
            val bitmap = when (kind) {
                FileIcon.IMAGE -> decodeImage(target, edge)
                FileIcon.VIDEO -> decodeVideo(target, edge)
                FileIcon.FOLDER,
                FileIcon.AUDIO,
                FileIcon.PDF,
                FileIcon.ARCHIVE,
                FileIcon.TEXT,
                FileIcon.CODE,
                FileIcon.SHEET,
                FileIcon.SLIDES,
                FileIcon.OTHER,
                -> null
            } ?: return null
            val bytes = compress(bitmap) ?: return null
            if (bytes.size > 512 * 1024) {
                return null
            }
            synchronized(cache) { cache.put(key, bytes) }
            return bytes
        } finally {
            gate.release()
        }
    }

    private fun cached(key: String): ByteArray? = synchronized(cache) { cache.get(key) }

    private fun decodeImage(target: PreviewTarget, maxEdge: Int): Bitmap? {
        val orientation = readOrientation(target)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(target, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = PreviewSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = decode(target, options) ?: return null
        return scaleDown(oriented(decoded, orientation), maxEdge)
    }

    private fun decode(target: PreviewTarget, options: BitmapFactory.Options): Bitmap? {
        return try {
            when (target) {
                is PreviewTarget.Disk -> BitmapFactory.decodeFile(target.file.absolutePath, options)
                is PreviewTarget.Document -> {
                    val descriptor = openDescriptor(target) ?: return null
                    descriptor.use { parcel ->
                        BitmapFactory.decodeFileDescriptor(parcel.fileDescriptor, null, options)
                    }
                }
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun openDescriptor(target: PreviewTarget.Document): ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(target.uri, "r")
        } catch (error: Exception) {
            null
        }
    }

    private fun decodeVideo(target: PreviewTarget, maxEdge: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            when (target) {
                is PreviewTarget.Disk -> retriever.setDataSource(target.file.absolutePath)
                is PreviewTarget.Document -> retriever.setDataSource(context, target.uri)
            }
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    FRAME_TIME_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxEdge,
                    maxEdge,
                ) ?: retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null
            return scaleDown(frame, maxEdge)
        } catch (error: Exception) {
            return null
        } finally {
            try {
                retriever.release()
            } catch (error: Exception) {
                // Opening the retriever can fail before it owns a source.
            }
        }
    }

    private fun readOrientation(target: PreviewTarget): Int {
        return try {
            val exif = when (target) {
                is PreviewTarget.Disk -> ExifInterface(target.file.absolutePath)
                is PreviewTarget.Document -> {
                    val descriptor = openDescriptor(target) ?: return ExifInterface.ORIENTATION_NORMAL
                    descriptor.use { ExifInterface(it.fileDescriptor) }
                }
            }
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (error: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun oriented(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned != bitmap) {
            bitmap.recycle()
        }
        return turned
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge || longEdge <= 0) {
            return bitmap
        }
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)
            if (ok) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val IMAGE_LIMIT: Long = 64L * 1024L * 1024L
        private const val FRAME_TIME_US: Long = 500_000L

        @Volatile
        private var instance: AndroidJpegPreview? = null

        fun get(context: Context): AndroidJpegPreview {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AndroidJpegPreview(context.applicationContext as Application).also { instance = it }
            }
        }
    }
}
