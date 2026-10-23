package app.openshelf.core.http

import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import app.openshelf.core.text.TextFormat
import java.net.URI

internal fun handleWebDav(reply: HttpReply, request: HttpRequest, store: FileStore) {
    if (request.method == "OPTIONS") {
        reply.send(200, ByteArray(0), TEXT, davHeaders())
        return
    }
    val location = davLocation(request.path)
    when (request.method) {
        "PROPFIND" -> {
            discard(request)
            propfind(reply, request, store, location)
        }
        "GET", "HEAD" -> get(reply, request, store, location)
        "PUT" -> put(reply, request, store, location)
        "DELETE" -> {
            discard(request)
            delete(reply, store, location)
        }
        "MKCOL" -> {
            discard(request)
            mkcol(reply, store, location)
        }
        "MOVE" -> {
            discard(request)
            move(reply, request, store, location)
        }
        else -> reply.send(405, "方法唔支援".toByteArray(Charsets.UTF_8), TEXT, davHeaders())
    }
}

private fun davLocation(path: String): Pair<String, String>? {
    if (path == "/dav") {
        return null
    }
    return Paths.splitMountPath(path, "/dav/")
}

private fun propfind(
    reply: HttpReply,
    request: HttpRequest,
    store: FileStore,
    location: Pair<String, String>?,
) {
    val depth = request.headers["depth"]?.trim()?.lowercase()
    val children = depth != "0"
    val xml = StringBuilder()
    xml.append("""<?xml version="1.0" encoding="utf-8"?>""")
    xml.append("""<d:multistatus xmlns:d="DAV:">""")
    if (location == null) {
        appendEntry(xml, "/dav/", "快速臨時開放伺服器", directory = true, size = 0L, modified = 0L)
        if (children) {
            for (mount in store.mounts()) {
                val href = Paths.joinUrl("/dav", mount.id, "", trailingSlash = true)
                appendEntry(xml, href, mount.displayName, directory = true, size = 0L, modified = 0L)
            }
        }
    } else {
        val (mount, relative) = location
        val stat = store.stat(mount, relative) ?: throw StoreException(404, "搵唔到")
        val href = Paths.joinUrl("/dav", mount, relative, trailingSlash = stat.directory)
        appendEntry(xml, href, stat.name, stat.directory, stat.size, stat.modifiedEpochMs)
        if (children && stat.directory) {
            for (child in store.list(mount, relative)) {
                val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                val childHref = Paths.joinUrl("/dav", mount, childRelative, trailingSlash = child.directory)
                appendEntry(xml, childHref, child.name, child.directory, child.size, child.modifiedEpochMs)
            }
        }
    }
    xml.append("</d:multistatus>")
    reply.send(207, xml.toString().toByteArray(Charsets.UTF_8), "application/xml; charset=utf-8", davHeaders())
}

private fun appendEntry(
    xml: StringBuilder,
    href: String,
    name: String,
    directory: Boolean,
    size: Long,
    modified: Long,
) {
    xml.append("<d:response><d:href>").append(xmlEsc(href)).append("</d:href><d:propstat><d:prop>")
    xml.append("<d:displayname>").append(xmlEsc(name)).append("</d:displayname>")
    if (directory) {
        xml.append("<d:resourcetype><d:collection/></d:resourcetype>")
    } else {
        xml.append("<d:resourcetype/>")
        xml.append("<d:getcontentlength>").append(size).append("</d:getcontentlength>")
        xml.append("<d:getcontenttype>").append(xmlEsc(mimeFor(name))).append("</d:getcontenttype>")
    }
    if (modified > 0L) {
        xml.append("<d:getlastmodified>").append(xmlEsc(TextFormat.httpDate(modified))).append("</d:getlastmodified>")
    }
    xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
}

private fun get(reply: HttpReply, request: HttpRequest, store: FileStore, location: Pair<String, String>?) {
    if (location == null) {
        throw StoreException(405, "請用 PROPFIND")
    }
    val (mount, relative) = location
    val stat = store.stat(mount, relative) ?: throw StoreException(404, "搵唔到")
    if (stat.directory) {
        throw StoreException(405, "請用 PROPFIND")
    }
    val head = request.method == "HEAD"
    store.openRead(mount, relative).use { input ->
        reply.deliverStream(
            input,
            mimeFor(stat.name),
            davHeaders() + ("Content-Disposition" to contentDisposition(stat.name, download = false)),
            headOnly = head,
            declaredLength = stat.size,
        )
    }
}

private fun put(reply: HttpReply, request: HttpRequest, store: FileStore, location: Pair<String, String>?) {
    if (location == null || location.second.isEmpty()) {
        throw StoreException(400, "路徑唔合法")
    }
    store.writeFile(location.first, location.second, request.body)
    reply.send(201, ByteArray(0), TEXT, davHeaders())
}

private fun delete(reply: HttpReply, store: FileStore, location: Pair<String, String>?) {
    if (location == null || location.second.isEmpty()) {
        throw StoreException(400, "唔可以刪除掛載根目錄")
    }
    store.delete(location.first, location.second)
    reply.send(204, ByteArray(0), TEXT, davHeaders())
}

private fun mkcol(reply: HttpReply, store: FileStore, location: Pair<String, String>?) {
    if (location == null || location.second.isEmpty()) {
        throw StoreException(400, "路徑唔合法")
    }
    store.mkdir(location.first, location.second)
    reply.send(201, ByteArray(0), TEXT, davHeaders())
}

private fun move(
    reply: HttpReply,
    request: HttpRequest,
    store: FileStore,
    location: Pair<String, String>?,
) {
    if (location == null) {
        throw StoreException(400, "路徑唔合法")
    }
    val header = request.headers["destination"] ?: throw StoreException(400, "缺少 Destination")
    val rawPath = destinationPath(header) ?: throw StoreException(400, "Destination 唔合法")
    val decoded = Paths.decodeRequestTarget(rawPath) ?: throw StoreException(400, "路徑唔合法")
    val target = Paths.splitMountPath(decoded.first, "/dav/") ?: throw StoreException(400, "路徑唔合法")
    if (target.first != location.first) {
        throw StoreException(403, "只可以喺同一個掛載入面移動")
    }
    val overwrite = !request.headers["overwrite"].equals("F", ignoreCase = true)
    store.move(location.first, location.second, target.second, overwrite)
    reply.send(201, ByteArray(0), TEXT, davHeaders())
}

private fun destinationPath(header: String): String? {
    val raw = header.trim()
    return when {
        raw.startsWith("/") -> raw.substringBefore('?')
        raw.startsWith("http://") || raw.startsWith("https://") -> {
            try {
                URI(raw).rawPath
            } catch (error: IllegalArgumentException) {
                null
            }
        }
        else -> null
    }
}

private fun discard(request: HttpRequest) {
    val buffer = ByteArray(4096)
    var total = 0
    while (total < 1_048_576) {
        val count = request.body.read(buffer)
        if (count < 0) {
            return
        }
        total += count
    }
}

private fun davHeaders(): List<Pair<String, String>> {
    return listOf(
        "DAV" to "1",
        "Allow" to "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL, MOVE",
        "MS-Author-Via" to "DAV",
    )
}

private fun xmlEsc(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}
