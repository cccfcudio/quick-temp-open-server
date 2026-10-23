package app.openshelf.core.http

import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

internal data class UploadedPart(
    val field: String,
    val filename: String,
    val file: File,
)

internal data class ParsedForm(
    val fields: Map<String, String>,
    val files: List<UploadedPart>,
)

internal fun spoolBody(request: HttpRequest, maxBytes: Long): File {
    val file = File.createTempFile("openshelf-body-", ".bin")
    var written = 0L
    try {
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = request.body.read(buffer)
                if (count < 0) {
                    break
                }
                written += count
                if (written > maxBytes) {
                    throw StoreException(413, "上載太大")
                }
                output.write(buffer, 0, count)
            }
        }
    } catch (error: Exception) {
        file.delete()
        throw error
    }
    return file
}

internal fun parseMultipart(spool: File, contentType: String): ParsedForm {
    val boundary = boundaryOf(contentType)
    val startToken = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
    val files = ArrayList<UploadedPart>()
    try {
        RandomAccessFile(spool, "r").use { file ->
            val length = file.length()
            var position = indexOf(file, startToken, 0L, length)
            if (position < 0L) {
                throw StoreException(400, "上載格式唔啱")
            }
            position += startToken.size
            val fields = LinkedHashMap<String, String>()
            while (true) {
                val marker = peek(file, position, 2)
                if (marker.startsWith("--")) {
                    break
                }
                position += when {
                    marker == "\r\n" -> 2
                    marker.startsWith("\n") -> 1
                    else -> throw StoreException(400, "上載格式唔啱")
                }
                val (headers, afterHeaders) = readPartHeaders(file, position)
                position = afterHeaders
                val next = indexOf(file, delimiter, position, length)
                if (next < 0L) {
                    throw StoreException(400, "上載格式唔啱")
                }
                val (name, filename) = dispositionParts(headers["content-disposition"] ?: "")
                if (filename != null) {
                    val safe = Paths.safeFileName(filename) ?: throw StoreException(400, "檔案名稱唔合法")
                    val part = File.createTempFile("openshelf-part-", ".bin")
                    copyRange(file, position, next, part)
                    files.add(UploadedPart(name, safe, part))
                } else if (name.isNotEmpty()) {
                    val size = next - position
                    if (size > 1_048_576L) {
                        throw StoreException(413, "欄位太大")
                    }
                    val bytes = ByteArray(size.toInt())
                    file.seek(position)
                    if (bytes.isNotEmpty()) {
                        file.readFully(bytes)
                    }
                    fields[name] = bytes.toString(Charsets.UTF_8)
                }
                position = next + delimiter.size
            }
            return ParsedForm(fields, files)
        }
    } catch (error: Exception) {
        files.forEach { it.file.delete() }
        throw error
    }
}

internal fun boundaryOf(contentType: String): String {
    for (piece in splitSemi(contentType)) {
        val item = piece.trim()
        if (item.startsWith("boundary=", ignoreCase = true)) {
            var value = item.substringAfter('=').trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            if (value.isNotEmpty()) {
                return value
            }
        }
    }
    throw StoreException(400, "缺少 boundary")
}

internal fun dispositionParts(header: String): Pair<String, String?> {
    var name = ""
    var plain: String? = null
    var star: String? = null
    for (piece in splitSemi(header)) {
        val item = piece.trim()
        val eq = item.indexOf('=')
        if (eq <= 0) {
            continue
        }
        val key = item.substring(0, eq).trim().lowercase()
        var value = item.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        when (key) {
            "name" -> name = value
            "filename" -> plain = value.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
            "filename*" -> {
                val encoded = value.substringAfter("''", value)
                star = Paths.percentDecode(encoded) ?: value
            }
        }
    }
    return name to (star ?: plain)
}

internal fun splitSemi(raw: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    for (ch in raw) {
        if (ch == '"') {
            quoted = !quoted
        }
        if (ch == ';' && !quoted) {
            out.add(current.toString())
            current.clear()
        } else {
            current.append(ch)
        }
    }
    if (current.isNotEmpty()) {
        out.add(current.toString())
    }
    return out
}

private fun readPartHeaders(file: RandomAccessFile, start: Long): Pair<Map<String, String>, Long> {
    file.seek(start)
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val line = readFileLine(file) ?: throw StoreException(400, "上載格式唔啱")
        if (line.isEmpty()) {
            break
        }
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
    return headers to file.filePointer
}

private fun readFileLine(file: RandomAccessFile): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val value = file.read()
        if (value < 0) {
            return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
        }
        if (value == '\n'.code) {
            break
        }
        if (value != '\r'.code) {
            out.write(value)
        }
        if (out.size() > 16 * 1024) {
            throw StoreException(400, "上載格式唔啱")
        }
    }
    return out.toString(Charsets.ISO_8859_1.name())
}

private fun peek(file: RandomAccessFile, position: Long, count: Int): String {
    if (position >= file.length()) {
        return ""
    }
    file.seek(position)
    val buffer = ByteArray(count)
    val read = file.read(buffer)
    if (read <= 0) {
        return ""
    }
    return String(buffer, 0, read, Charsets.ISO_8859_1)
}

private fun copyRange(file: RandomAccessFile, start: Long, end: Long, destination: File) {
    file.seek(start)
    destination.outputStream().use { output ->
        val buffer = ByteArray(8192)
        var left = end - start
        while (left > 0L) {
            val count = file.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (count < 0) {
                break
            }
            output.write(buffer, 0, count)
            left -= count
        }
    }
}

private fun indexOf(file: RandomAccessFile, pattern: ByteArray, start: Long, end: Long): Long {
    if (pattern.isEmpty() || start >= end) {
        return -1L
    }
    val overlap = pattern.size - 1
    var position = start
    val buffer = ByteArray(64 * 1024)
    while (position < end) {
        file.seek(position)
        val want = minOf(buffer.size.toLong(), end - position).toInt()
        val count = file.read(buffer, 0, want)
        if (count <= 0) {
            return -1L
        }
        val found = indexOfSlice(buffer, count, pattern)
        if (found >= 0) {
            return position + found
        }
        if (count <= overlap) {
            return -1L
        }
        position += count - overlap
    }
    return -1L
}

private fun indexOfSlice(haystack: ByteArray, haystackLength: Int, needle: ByteArray): Int {
    val last = haystackLength - needle.size
    if (last < 0) {
        return -1
    }
    for (start in 0..last) {
        var matched = true
        for (offset in needle.indices) {
            if (haystack[start + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) {
            return start
        }
    }
    return -1
}
