package app.openshelf.core.text

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TextFormat {
    fun size(bytes: Long): String {
        if (bytes < 0L) {
            return "—"
        }
        if (bytes < 1024L) {
            return "$bytes B"
        }
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        // -1 means "still in bytes". Each division moves one unit, so 5 MiB is MB, not GB.
        var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** Prefer a positive metadata size. A zero report can mean "unknown" on SAF, so use stat when it is larger. */
    fun chooseByteSize(metadata: Long, statSize: Long): Long {
        if (metadata > 0L) {
            return metadata
        }
        return if (statSize > 0L) statSize else 0L
    }

    fun clock(epochMs: Long): String {
        if (epochMs <= 0L) {
            return ""
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return format.format(Date(epochMs))
    }

    fun httpDate(epochMs: Long): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(epochMs))
    }

    fun iso(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMs))
    }
}
