package app.openshelf.core.http

import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
)

internal class HttpReply(private val output: OutputStream) {
    var started: Boolean = false
        private set

    fun send(
        status: Int,
        body: ByteArray,
        contentType: String,
        extra: List<Pair<String, String>> = emptyList(),
        headOnly: Boolean = false,
    ) {
        writeHead(status, body.size.toLong(), contentType, extra)
        if (!headOnly && body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    fun sendStream(
        status: Int,
        body: InputStream,
        length: Long,
        contentType: String,
        extra: List<Pair<String, String>> = emptyList(),
        headOnly: Boolean = false,
    ) {
        writeHead(status, length, contentType, extra)
        if (!headOnly && length > 0L) {
            copyLimited(body, output, length)
        }
        output.flush()
    }

    /**
     * Sends the bytes actually available on [input].
     * A metadata length of 0, or a length far larger than the stream, must not produce an empty
     * body or a Content-Length the client waits on forever.
     */
    fun deliverStream(
        input: InputStream,
        contentType: String,
        extra: List<Pair<String, String>> = emptyList(),
        headOnly: Boolean = false,
        declaredLength: Long = -1L,
        emptyError: String? = null,
    ) {
        if (headOnly) {
            sendStream(200, input, declaredLength.coerceAtLeast(0L), contentType, extra, true)
            return
        }
        val temp = File.createTempFile("openshelf-body", ".part")
        try {
            val pending = ByteArray(8192)
            var pendingLength = 0
            var total = 0L
            var overflow = false
            temp.outputStream().use { staged ->
                val buffer = ByteArray(8192)
                var zeros = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    if (count == 0) {
                        if (++zeros > 8) {
                            throw IOException("讀取停住")
                        }
                        continue
                    }
                    zeros = 0
                    if (total + count > MEASURE_LIMIT) {
                        buffer.copyInto(pending, 0, 0, count)
                        pendingLength = count
                        overflow = true
                        break
                    }
                    staged.write(buffer, 0, count)
                    total += count
                }
            }
            if (!overflow && total == 0L && emptyError != null) {
                throw StoreException(400, emptyError)
            }
            if (!overflow) {
                temp.inputStream().use { staged ->
                    sendStream(200, staged, total, contentType, extra, false)
                }
                return
            }
            openChunked(200, contentType, extra).use { chunked ->
                temp.inputStream().use { staged -> copyFully(staged, chunked) }
                if (pendingLength > 0) {
                    chunked.write(pending, 0, pendingLength)
                }
                copyFully(input, chunked)
            }
        } finally {
            temp.delete()
        }
    }

    fun continue100() {
        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    fun openChunked(
        status: Int,
        contentType: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): OutputStream {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Transfer-Encoding: chunked\r\n")
        head.append("Connection: close\r\n")
        head.append("Server: QuickTempOpenServer/0.1.7\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("Cache-Control: no-store\r\n")
        for ((name, value) in extra) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
        started = true
        return ChunkedOutputStream(output)
    }

    private fun writeHead(status: Int, length: Long, contentType: String, extra: List<Pair<String, String>>) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Server: QuickTempOpenServer/0.1.7\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("Cache-Control: no-store\r\n")
        for ((name, value) in extra) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        started = true
    }
}

internal fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    207 -> "Multi-Status"
    302 -> "Found"
    303 -> "See Other"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    412 -> "Precondition Failed"
    413 -> "Payload Too Large"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    else -> "Status"
}

internal fun readHttpRequest(input: InputStream, maxUpload: Long): HttpRequest {
    val lines = ArrayList<String>()
    while (lines.size < 100) {
        val line = readAsciiLine(input, 8192) ?: throw StoreException(400, "請求不完整")
        if (line.isEmpty()) {
            break
        }
        lines.add(line)
    }
    if (lines.isEmpty()) {
        throw StoreException(400, "請求不完整")
    }
    val parts = lines[0].split(' ')
    if (parts.size < 2) {
        throw StoreException(400, "請求不完整")
    }
    val method = parts[0].uppercase()
    val decoded = Paths.decodeRequestTarget(parts[1]) ?: throw StoreException(400, "路徑唔合法")
    val headers = LinkedHashMap<String, String>()
    for (index in 1 until lines.size) {
        if (!addHeader(headers, lines[index])) {
            throw StoreException(400, "標頭唔合法")
        }
    }
    val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    val lengthText = headers["content-length"]
    val length = lengthText?.toLongOrNull()
    if (lengthText != null && length == null) {
        throw StoreException(400, "Content-Length 唔合法")
    }
    if (chunked && length != null) {
        throw StoreException(400, "請求唔合法")
    }
    if (length != null && (length < 0L || length > maxUpload)) {
        throw StoreException(413, "上載太大")
    }
    val body: InputStream = when {
        chunked -> ChunkedInputStream(input, maxUpload)
        length != null && length > 0L -> LimitedInputStream(input, length)
        else -> EmptyBody
    }
    return HttpRequest(method, decoded.first, decoded.second, headers, body)
}

internal fun readSmallBody(request: HttpRequest, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var total = 0
    while (true) {
        val count = request.body.read(buffer)
        if (count < 0) {
            break
        }
        total += count
        if (total > max) {
            throw StoreException(413, "上載太大")
        }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

internal fun readAsciiLine(input: InputStream, max: Int): String? {
    val out = ByteArrayOutputStream()
    while (out.size() <= max) {
        val value = input.read()
        if (value < 0) {
            return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
        }
        if (value == '\n'.code) {
            return out.toString(Charsets.ISO_8859_1.name())
        }
        if (value != '\r'.code) {
            out.write(value)
        }
    }
    throw StoreException(400, "標頭太長")
}

private fun addHeader(headers: MutableMap<String, String>, line: String): Boolean {
    if (line.startsWith(" ") || line.startsWith("\t")) {
        return true
    }
    val colon = line.indexOf(':')
    if (colon <= 0) {
        return false
    }
    val name = line.substring(0, colon).trim().lowercase()
    val value = line.substring(colon + 1).trim()
    val existing = headers[name]
    if (existing == null) {
        headers[name] = value
        return true
    }
    if (name == "content-length") {
        return existing == value
    }
    if (name == "cookie") {
        headers[name] = "$existing; $value"
    }
    return true
}

internal class LimitedInputStream(
    private val input: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0L) {
            return -1
        }
        val value = input.read()
        if (value >= 0) {
            remaining -= 1
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) {
            return -1
        }
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) {
            remaining -= count
        }
        return count
    }
}

internal class ChunkedInputStream(
    private val input: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var remainingInChunk: Int = 0
    private var done: Boolean = false
    private var total: Long = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (done) {
            return -1
        }
        if (remainingInChunk == 0) {
            val line = readAsciiLine(input, 64) ?: run {
                done = true
                return -1
            }
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw StoreException(400, "chunk 唔合法")
            if (size == 0) {
                while (true) {
                    val trailer = readAsciiLine(input, 8192) ?: break
                    if (trailer.isEmpty()) {
                        break
                    }
                }
                done = true
                return -1
            }
            remainingInChunk = size
        }
        val count = input.read(buffer, offset, minOf(length, remainingInChunk))
        if (count < 0) {
            done = true
            return -1
        }
        remainingInChunk -= count
        total += count
        if (total > maxBytes) {
            throw StoreException(413, "上載太大")
        }
        if (remainingInChunk == 0) {
            readAsciiLine(input, 8)
        }
        return count
    }
}

internal class ChunkedOutputStream(private val raw: OutputStream) : OutputStream() {
    private val buffer = ByteArray(8192)
    private var filled: Int = 0
    private var closed: Boolean = false

    override fun write(value: Int) {
        val one = byteArrayOf(value.toByte())
        write(one, 0, 1)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (closed) {
            throw IOException("串流已關閉")
        }
        var index = offset
        var remaining = length
        while (remaining > 0) {
            val space = buffer.size - filled
            val copy = minOf(space, remaining)
            System.arraycopy(data, index, buffer, filled, copy)
            filled += copy
            index += copy
            remaining -= copy
            if (filled == buffer.size) {
                flushChunk()
            }
        }
    }

    override fun flush() {
        flushChunk()
        raw.flush()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        flushChunk()
        raw.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        raw.flush()
    }

    private fun flushChunk() {
        if (filled == 0) {
            return
        }
        raw.write((filled.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
        raw.write(buffer, 0, filled)
        raw.write("\r\n".toByteArray(Charsets.US_ASCII))
        filled = 0
    }
}

private object EmptyBody : InputStream() {
    override fun read(): Int = -1
}

private const val MEASURE_LIMIT: Long = 64L * 1024L * 1024L

private fun copyLimited(input: InputStream, output: OutputStream, length: Long) {
    val buffer = ByteArray(8192)
    var remaining = length
    var zeros = 0
    while (remaining > 0L) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) {
            return
        }
        if (count == 0) {
            if (++zeros > 8) {
                return
            }
            continue
        }
        zeros = 0
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun copyFully(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8192)
    var zeros = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) {
            return
        }
        if (count == 0) {
            if (++zeros > 8) {
                throw IOException("讀取停住")
            }
            continue
        }
        zeros = 0
        output.write(buffer, 0, count)
    }
}
