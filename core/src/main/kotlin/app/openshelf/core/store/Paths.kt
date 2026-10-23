package app.openshelf.core.store

import java.io.ByteArrayOutputStream

object Paths {
    private val mountIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun isMountId(id: String): Boolean = mountIdPattern.matches(id)

    fun normalizeRelative(raw: String): String? {
        if (raw.length > 4096 || raw.indexOf('\u0000') >= 0 || raw.indexOf('\\') >= 0) {
            return null
        }
        val parts = ArrayList<String>()
        for (segment in raw.split('/')) {
            if (segment.isEmpty() || segment == ".") {
                continue
            }
            if (segment == "..") {
                return null
            }
            parts.add(segment)
        }
        return parts.joinToString("/")
    }

    fun parentRelative(relative: String): String? {
        if (relative.isEmpty()) {
            return null
        }
        val slash = relative.lastIndexOf('/')
        return if (slash < 0) "" else relative.substring(0, slash)
    }

    fun fileName(relative: String): String = relative.substringAfterLast('/')

    fun safeFileName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == ".." || base.length > 180) {
            return null
        }
        if (base.any { it.code < 32 }) {
            return null
        }
        return base
    }

    fun percentDecode(raw: String): String? {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            when {
                ch == '%' -> {
                    if (index + 2 >= raw.length) {
                        return null
                    }
                    val hi = hex(raw[index + 1]) ?: return null
                    val lo = hex(raw[index + 2]) ?: return null
                    out.write((hi shl 4) or lo)
                    index += 3
                }
                ch.code < 32 -> return null
                Character.isHighSurrogate(ch) &&
                    index + 1 < raw.length &&
                    Character.isLowSurrogate(raw[index + 1]) -> {
                    val codePoint = Character.toCodePoint(ch, raw[index + 1])
                    out.write(Character.toString(codePoint).toByteArray(Charsets.UTF_8))
                    index += 2
                }
                Character.isSurrogate(ch) -> return null
                else -> {
                    out.write(ch.toString().toByteArray(Charsets.UTF_8))
                    index += 1
                }
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    fun formDecode(raw: String): String? = percentDecode(raw.replace("+", "%20"))

    fun percentEncode(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            val unreserved = value in 'a'.code..'z'.code ||
                value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code ||
                value == '-'.code ||
                value == '.'.code ||
                value == '_'.code ||
                value == '~'.code
            if (unreserved) {
                out.append(value.toChar())
            } else {
                out.append('%')
                out.append(HEX[value ushr 4])
                out.append(HEX[value and 0x0f])
            }
        }
        return out.toString()
    }

    fun decodeRequestTarget(target: String): Pair<String, Map<String, String>>? {
        val pathRaw = target.substringBefore('?')
        val queryRaw = if (target.contains('?')) target.substringAfter('?') else ""
        if (!pathRaw.startsWith("/")) {
            return null
        }
        val decoded = ArrayList<String>(pathRaw.count { it == '/' } + 1)
        for (segment in pathRaw.split('/')) {
            decoded.add(percentDecode(segment) ?: return null)
        }
        val joined = decoded.joinToString("/").removePrefix("/")
        val normalized = if (joined.isEmpty()) "" else normalizeRelative(joined) ?: return null
        val path = if (normalized.isEmpty()) "/" else "/$normalized"
        return path to parseQuery(queryRaw)
    }

    fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, values) in parseQueryAll(query)) {
            values.lastOrNull()?.let { out[key] = it }
        }
        return out
    }

    fun parseQueryAll(query: String): Map<String, List<String>> {
        if (query.isEmpty()) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, MutableList<String>>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) {
                continue
            }
            val key = formDecode(pair.substringBefore('=')) ?: continue
            val value = formDecode(pair.substringAfter('=', "")) ?: continue
            out.getOrPut(key) { ArrayList() }.add(value)
        }
        return out
    }

    fun splitMountPath(path: String, prefix: String): Pair<String, String>? {
        if (path == prefix.trimEnd('/') || path == prefix) {
            return null
        }
        if (!path.startsWith(prefix)) {
            return null
        }
        val rest = path.removePrefix(prefix).trim('/')
        if (rest.isEmpty()) {
            return null
        }
        val mount = rest.substringBefore('/')
        if (!isMountId(mount)) {
            return null
        }
        val relative = if (rest.contains('/')) rest.substringAfter('/') else ""
        return mount to relative
    }

    fun joinUrl(prefix: String, mount: String, relative: String, trailingSlash: Boolean = false): String {
        val out = StringBuilder()
        out.append(prefix.trimEnd('/'))
        out.append('/')
        out.append(percentEncode(mount))
        if (relative.isNotEmpty()) {
            for (segment in relative.split('/')) {
                out.append('/')
                out.append(percentEncode(segment))
            }
        }
        if (trailingSlash && !out.endsWith("/")) {
            out.append('/')
        }
        return out.toString()
    }

    private fun hex(ch: Char): Int? = when (ch) {
        in '0'..'9' -> ch.code - '0'.code
        in 'a'..'f' -> ch.code - 'a'.code + 10
        in 'A'..'F' -> ch.code - 'A'.code + 10
        else -> null
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
