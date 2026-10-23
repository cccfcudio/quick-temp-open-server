package app.openshelf.core.http

import app.openshelf.core.auth.AccessGuard
import app.openshelf.core.auth.SessionStore
import app.openshelf.core.auth.Token
import app.openshelf.core.auth.cookieValue
import app.openshelf.core.browse.FileIcon
import app.openshelf.core.browse.FileIcons
import app.openshelf.core.browse.Listing
import app.openshelf.core.browse.ListingOptions
import app.openshelf.core.browse.PreviewSizing
import app.openshelf.core.model.ShareDefaults
import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import app.openshelf.core.zip.ZipEntryPlan
import app.openshelf.core.zip.ZipPack
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShareHttpServer(
    private val store: FileStore,
    private val password: () -> String = { "" },
    private val username: () -> String = { ShareDefaults.USERNAME },
    private val bindHost: String = "0.0.0.0",
    private val requestedPort: Int = ShareDefaults.PORT,
    private val log: (String) -> Unit = {},
    private val maxUploadBytes: Long = ShareDefaults.MAX_UPLOAD_BYTES,
    private val authFailDelayMs: Long = 0L,
    private val preview: JpegPreview? = null,
) {
    var port: Int = -1
        private set

    @Volatile
    private var running: Boolean = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessions = SessionStore()
    private val guard = AccessGuard(password, username, sessions)
    private val workers = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "openshelf-http").apply { isDaemon = true }
    }

    fun start() {
        check(acceptThread == null) { "伺服器已經啟動" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.soTimeout = 500
        socket.bind(InetSocketAddress(bindHost, requestedPort))
        serverSocket = socket
        port = socket.localPort
        running = true
        acceptThread = Thread({ acceptLoop() }, "openshelf-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (error: IOException) {
            log("close ${error.message}")
        }
        workers.shutdown()
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workers.shutdownNow()
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running) {
            try {
                val client = socket.accept()
                workers.execute { handle(client) }
            } catch (error: SocketTimeoutException) {
                continue
            } catch (error: IOException) {
                if (running) {
                    log("accept ${error.message}")
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            val reply = HttpReply(socket.getOutputStream())
            var request: HttpRequest? = null
            try {
                request = readHttpRequest(socket.getInputStream(), maxUploadBytes)
                if (!isPublic(request) && !guard.allow(request.headers) && !previewSession(request)) {
                    if (authFailDelayMs > 0L) {
                        Thread.sleep(authFailDelayMs)
                    }
                    unauthorized(reply, request)
                    return
                }
                val expect = request.headers["expect"]
                if (expect != null && expect.contains("100-continue", ignoreCase = true)) {
                    reply.continue100()
                }
                route(reply, request)
            } catch (error: StoreException) {
                sendError(reply, request, error.status, error.message ?: "請求失敗")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: IOException) {
                log("io ${error.message}")
            } catch (error: Exception) {
                log("error ${error.javaClass.simpleName}: ${error.message}")
                val detail = error.message?.take(180)?.takeIf { it.isNotBlank() } ?: "伺服器錯誤"
                sendError(reply, request, 500, detail)
            }
        }
    }

    private fun isPublic(request: HttpRequest): Boolean {
        if (request.path == "/healthz" && (request.method == "GET" || request.method == "HEAD")) {
            return true
        }
        return request.path == "/login" && (request.method == "GET" || request.method == "HEAD" || request.method == "POST")
    }

    private fun route(reply: HttpReply, request: HttpRequest) {
        val head = request.method == "HEAD"
        when {
            request.path == "/healthz" && (request.method == "GET" || head) ->
                reply.send(200, """{"ok":true}""".toByteArray(Charsets.UTF_8), JSON, headOnly = head)
            request.path == "/login" && (request.method == "GET" || head) ->
                reply.send(200, loginPage(request.query["next"], username = authUsername()).toByteArray(Charsets.UTF_8), HTML, htmlHeaders(), head)
            request.path == "/login" && request.method == "POST" -> login(reply, request)
            request.path == "/" && (request.method == "GET" || head) ->
                reply.send(200, homePage(store).toByteArray(Charsets.UTF_8), HTML, htmlHeaders(), head)
            request.path == "/browse" || request.path.startsWith("/browse/") -> browse(reply, request, head)
            request.path.startsWith("/raw/") -> raw(reply, request, head)
            request.path.startsWith("/thumb/") -> thumb(reply, request, head)
            request.path == "/upload" && request.method == "POST" -> upload(reply, request)
            request.path == "/mkdir" && request.method == "POST" -> mkdirForm(reply, request)
            request.path == "/delete" && request.method == "POST" -> deleteForm(reply, request)
            request.path == "/batch" && request.method == "POST" -> batch(reply, request)
            request.path == "/zip" && request.method == "POST" -> zipSelection(reply, request)
            request.path == "/download" && request.method == "POST" -> downloadSelection(reply, request)
            request.path == "/zip" || request.path.startsWith("/zip/") -> zipFolder(reply, request)
            request.path == "/dav" || request.path.startsWith("/dav/") -> handleWebDav(reply, request, store)
            request.path.startsWith("/api/fs/") -> handleJsonApi(reply, request, store, port, head)
            else -> reply.send(404, "搵唔到".toByteArray(Charsets.UTF_8), TEXT)
        }
    }

    private fun login(reply: HttpReply, request: HttpRequest) {
        val form = Paths.parseQuery(readSmallBody(request, 64 * 1024).toString(Charsets.UTF_8))
        val next = safeNext(form["next"])
        val expected = password()
        val given = form["password"] ?: ""
        if (expected.isNotEmpty() && !Token.constantEquals(expected, given)) {
            reply.send(401, loginPage(next, failed = true, username = authUsername()).toByteArray(Charsets.UTF_8), HTML, listOf(BASIC) + htmlHeaders())
            return
        }
        val token = sessions.issue()
        reply.send(
            302,
            ByteArray(0),
            TEXT,
            listOf(
                "Location" to next,
                "Set-Cookie" to "${ShareDefaults.SESSION_COOKIE}=$token; Path=/; HttpOnly; SameSite=Lax",
            ),
        )
    }

    private fun browse(reply: HttpReply, request: HttpRequest, head: Boolean) {
        if (request.method != "GET" && request.method != "HEAD") {
            throw StoreException(405, "方法唔支援")
        }
        if (request.path == "/browse") {
            reply.send(200, homePage(store).toByteArray(Charsets.UTF_8), HTML, htmlHeaders(), head)
            return
        }
        val location = Paths.splitMountPath(request.path, "/browse/") ?: throw StoreException(404, "搵唔到")
        val stat = store.stat(location.first, location.second) ?: throw StoreException(404, "搵唔到")
        if (!stat.directory) {
            reply.send(302, ByteArray(0), TEXT, listOf("Location" to Paths.joinUrl("/raw", location.first, location.second)))
            return
        }
        val saved = Listing.decodeCookie(cookieValue(request.headers["cookie"] ?: "", Listing.COOKIE))
        val options = Listing.parse(request.query, saved)
        val page = request.query["page"]?.toIntOrNull() ?: 1
        val name = store.mounts().firstOrNull { it.id == location.first }?.displayName ?: location.first
        val token = listingSession(request)
        val html = browsePage(
            name,
            location.first,
            location.second,
            store.list(location.first, location.second),
            options,
            pickAll = request.query["pick"] == "1",
            page = page,
            previewToken = token,
        )
        val headers = if (token == null) {
            listingHeaders(options)
        } else {
            listOf(sessionCookie(token)) + listingHeaders(options)
        }
        reply.send(200, html.toByteArray(Charsets.UTF_8), HTML, headers, head)
    }

    /**
     * Image requests do not repeat the Authorization header from a
     * https://user:pass@host link, and Cloudflare forwards those page loads
     * as plain HTTP. The listing embeds a session token on each thumb URL.
     */
    private fun listingSession(request: HttpRequest): String? {
        if (password().isEmpty()) {
            return null
        }
        val cookie = request.headers["cookie"]
        if (cookie != null) {
            val existing = cookieValue(cookie, ShareDefaults.SESSION_COOKIE)
            if (existing != null && guard.validSession(existing)) {
                return existing
            }
        }
        return guard.issueSession()
    }

    private fun previewSession(request: HttpRequest): Boolean {
        if (request.method != "GET" && request.method != "HEAD") {
            return false
        }
        if (!request.path.startsWith("/thumb/")) {
            return false
        }
        val token = request.query["t"] ?: return false
        return guard.validSession(token)
    }

    private fun sessionCookie(token: String): Pair<String, String> {
        return "Set-Cookie" to "${ShareDefaults.SESSION_COOKIE}=$token; Path=/; HttpOnly; SameSite=Lax"
    }

    private fun batch(reply: HttpReply, request: HttpRequest) {
        val form = Paths.parseQueryAll(readSmallBody(request, 256 * 1024).toString(Charsets.UTF_8))
        val flat = form.mapValues { it.value.last() }
        val mount = flat["mount"] ?: throw StoreException(400, "缺少掛載")
        if (!Paths.isMountId(mount)) {
            throw StoreException(400, "路徑唔合法")
        }
        val directory = Paths.normalizeRelative(flat["back"] ?: "") ?: throw StoreException(400, "路徑唔合法")
        val options = Listing.parse(flat)
        val page = flat["page"]?.toIntOrNull() ?: 1
        val paths = Listing.childrenIn(directory, form["path"] ?: emptyList())
        if (paths.isEmpty()) {
            throw StoreException(400, "未有選擇")
        }
        val action = flat["action"] ?: ""
        when (action) {
            "paths" -> {
                val back = Paths.joinUrl("/browse", mount, directory) + "?" + Listing.queryString(options, page = page)
                reply.send(200, pathsPage(paths, back).toByteArray(Charsets.UTF_8), HTML, htmlHeaders())
            }
            "delete" -> {
                if (flat["confirm"] != "1") {
                    reply.send(
                        200,
                        confirmDeletePage(mount, directory, paths, options, page).toByteArray(Charsets.UTF_8),
                        HTML,
                        htmlHeaders(),
                    )
                    return
                }
                for (path in paths) {
                    store.delete(mount, path)
                }
                val back = Paths.joinUrl("/browse", mount, directory) + "?" + Listing.queryString(options, page = page)
                reply.send(302, ByteArray(0), TEXT, listOf("Location" to back))
            }
            else -> throw StoreException(400, "未知動作")
        }
    }

    private fun zipFolder(reply: HttpReply, request: HttpRequest) {
        if (request.method != "GET") {
            throw StoreException(405, "方法唔支援")
        }
        val location = Paths.splitMountPath(request.path, "/zip/") ?: throw StoreException(404, "搵唔到")
        val stat = store.stat(location.first, location.second) ?: throw StoreException(404, "搵唔到")
        if (!stat.directory) {
            throw StoreException(400, "唔係資料夾")
        }
        val plans = ZipPack.planTree(store, location.first, location.second)
        sendZip(reply, stat.name, plans)
    }

    private fun zipSelection(reply: HttpReply, request: HttpRequest) {
        val picked = selectedPaths(request)
        val plans = ZipPack.planSelection(store, picked.mount, picked.paths)
        val label = store.stat(picked.mount, picked.directory)?.name ?: picked.mount
        sendZip(reply, label, plans)
    }

    private fun downloadSelection(reply: HttpReply, request: HttpRequest) {
        val picked = selectedPaths(request)
        val files = ArrayList<String>()
        val folders = ArrayList<String>()
        for (path in picked.paths) {
            val stat = store.stat(picked.mount, path) ?: throw StoreException(404, "搵唔到")
            if (stat.directory) {
                folders.add(path)
            } else {
                files.add(path)
            }
        }
        if (files.isEmpty()) {
            throw StoreException(400, "資料夾請用下載成個資料夾")
        }
        if (files.size == 1 && folders.isEmpty()) {
            val location = Paths.joinUrl("/raw", picked.mount, files[0]) + "?download=1"
            reply.send(303, ByteArray(0), TEXT, listOf("Location" to location))
            return
        }
        val back = Paths.joinUrl("/browse", picked.mount, picked.directory)
        val html = downloadReadyPage(picked.mount, files, folders, back)
        reply.send(200, html.toByteArray(Charsets.UTF_8), HTML, htmlHeaders())
    }

    private fun sendZip(reply: HttpReply, label: String, plans: List<ZipEntryPlan>) {
        val extra = listOf("Content-Disposition" to contentDisposition(ZipPack.downloadName(label), true))
        val declared = plans.sumOf { it.size }
        if (declared > ZipPack.MAX_BYTES) {
            throw StoreException(413, "總大小超過上限")
        }
        if (declared > ZIP_SPOOL_LIMIT) {
            reply.openChunked(200, "application/zip", extra).use { output ->
                ZipPack.write(store, plans, output)
            }
            return
        }
        val temp = File.createTempFile("openshelf-zip", ".zip")
        try {
            temp.outputStream().use { raw ->
                val unknownFile = plans.any { !it.directory && it.size <= 0L }
                val output: OutputStream = if (unknownFile) {
                    CappedOutputStream(raw, ZIP_SPOOL_LIMIT)
                } else {
                    raw
                }
                ZipPack.write(store, plans, output)
            }
            temp.inputStream().use { input ->
                reply.sendStream(200, input, temp.length(), "application/zip", extra, false)
            }
        } finally {
            temp.delete()
        }
    }

    private data class Selection(val mount: String, val directory: String, val paths: List<String>)

    private fun selectedPaths(request: HttpRequest): Selection {
        val form = Paths.parseQueryAll(readSmallBody(request, 256 * 1024).toString(Charsets.UTF_8))
        val flat = form.mapValues { it.value.last() }
        val mount = flat["mount"] ?: throw StoreException(400, "缺少掛載")
        if (!Paths.isMountId(mount)) {
            throw StoreException(400, "路徑唔合法")
        }
        val directory = Paths.normalizeRelative(flat["back"] ?: "") ?: throw StoreException(400, "路徑唔合法")
        val paths = Listing.childrenIn(directory, form["path"] ?: emptyList())
        if (paths.isEmpty()) {
            throw StoreException(400, "未有選擇")
        }
        return Selection(mount, directory, paths)
    }

    private fun thumb(reply: HttpReply, request: HttpRequest, head: Boolean) {
        if (request.method != "GET" && request.method != "HEAD") {
            throw StoreException(405, "方法唔支援")
        }
        val maker = preview ?: throw StoreException(404, "沒有預覽")
        val location = Paths.splitMountPath(request.path, "/thumb/") ?: throw StoreException(404, "搵唔到")
        val stat = store.stat(location.first, location.second) ?: throw StoreException(404, "搵唔到")
        if (stat.directory) {
            throw StoreException(404, "沒有預覽")
        }
        val icon = FileIcons.of(stat.name, false)
        if (icon != FileIcon.IMAGE && icon != FileIcon.VIDEO) {
            throw StoreException(404, "沒有預覽")
        }
        val bytes = try {
            maker.jpeg(location.first, location.second, PreviewSizing.edge(request.query["edge"]))
        } catch (error: StoreException) {
            throw error
        } catch (error: Exception) {
            null
        }
        if (bytes == null || bytes.isEmpty() || bytes.size > 512 * 1024) {
            throw StoreException(404, "沒有預覽")
        }
        reply.send(200, bytes, "image/jpeg", emptyList(), head)
    }

    private fun raw(reply: HttpReply, request: HttpRequest, head: Boolean) {
        val location = Paths.splitMountPath(request.path, "/raw/") ?: throw StoreException(400, "路徑唔合法")
        when (request.method) {
            "PUT" -> {
                store.writeFile(location.first, location.second, request.body)
                reply.send(201, "已建立".toByteArray(Charsets.UTF_8), TEXT)
            }
            "DELETE" -> {
                store.delete(location.first, location.second)
                reply.send(204, ByteArray(0), TEXT)
            }
            "GET", "HEAD" -> {
                val stat = store.stat(location.first, location.second) ?: throw StoreException(404, "搵唔到")
                if (stat.directory) {
                    throw StoreException(400, "唔係檔案")
                }
                val forceDownload = request.query["download"] == "1"
                val extra = listOf("Content-Disposition" to contentDisposition(stat.name, forceDownload))
                val type = if (forceDownload) "application/octet-stream" else mimeFor(stat.name)
                store.openRead(location.first, location.second).use { input ->
                    reply.deliverStream(
                        input,
                        type,
                        extra,
                        headOnly = head,
                        declaredLength = stat.size,
                        emptyError = if (forceDownload) "呢個檔案係空嘅" else null,
                    )
                }
            }
            else -> throw StoreException(405, "方法唔支援")
        }
    }

    private fun upload(reply: HttpReply, request: HttpRequest) {
        val type = request.headers["content-type"] ?: throw StoreException(400, "缺少 Content-Type")
        if (!type.contains("multipart/form-data", ignoreCase = true)) {
            throw StoreException(400, "請用 multipart")
        }
        val spool = spoolBody(request, maxUploadBytes)
        var parsed: ParsedForm? = null
        try {
            parsed = parseMultipart(spool, type)
            val mount = parsed.fields["mount"] ?: throw StoreException(400, "缺少掛載")
            if (!Paths.isMountId(mount)) {
                throw StoreException(400, "路徑唔合法")
            }
            val directory = Paths.normalizeRelative(parsed.fields["dir"] ?: "") ?: throw StoreException(400, "路徑唔合法")
            val file = parsed.files.firstOrNull() ?: throw StoreException(400, "缺少檔案")
            val relative = if (directory.isEmpty()) file.filename else "$directory/${file.filename}"
            file.file.inputStream().use { input -> store.writeFile(mount, relative, input) }
            reply.send(302, ByteArray(0), TEXT, listOf("Location" to Paths.joinUrl("/browse", mount, directory)))
        } finally {
            spool.delete()
            parsed?.files?.forEach { it.file.delete() }
        }
    }

    private fun mkdirForm(reply: HttpReply, request: HttpRequest) {
        val form = Paths.parseQuery(readSmallBody(request, 64 * 1024).toString(Charsets.UTF_8))
        val mount = form["mount"] ?: throw StoreException(400, "缺少掛載")
        val directory = Paths.normalizeRelative(form["dir"] ?: "") ?: throw StoreException(400, "路徑唔合法")
        val name = Paths.safeFileName(form["name"] ?: "") ?: throw StoreException(400, "名稱唔合法")
        val relative = if (directory.isEmpty()) name else "$directory/$name"
        store.mkdir(mount, relative)
        reply.send(302, ByteArray(0), TEXT, listOf("Location" to Paths.joinUrl("/browse", mount, directory)))
    }

    private fun deleteForm(reply: HttpReply, request: HttpRequest) {
        val form = Paths.parseQuery(readSmallBody(request, 64 * 1024).toString(Charsets.UTF_8))
        val mount = form["mount"] ?: throw StoreException(400, "缺少掛載")
        val path = Paths.normalizeRelative(form["path"] ?: "") ?: throw StoreException(400, "路徑唔合法")
        val back = Paths.normalizeRelative(form["back"] ?: "") ?: ""
        store.delete(mount, path)
        reply.send(302, ByteArray(0), TEXT, listOf("Location" to Paths.joinUrl("/browse", mount, back)))
    }

    private fun unauthorized(reply: HttpReply, request: HttpRequest) {
        val browser = request.method == "GET" &&
            wantsHtml(request.headers) &&
            !request.path.startsWith("/api/") &&
            !request.path.startsWith("/raw/") &&
            !request.path.startsWith("/dav")
        if (browser) {
            reply.send(401, loginPage(request.path, username = authUsername()).toByteArray(Charsets.UTF_8), HTML, listOf(BASIC) + htmlHeaders())
        } else {
            reply.send(401, "需要密碼".toByteArray(Charsets.UTF_8), TEXT, listOf(BASIC))
        }
    }

    private fun sendError(reply: HttpReply, request: HttpRequest?, status: Int, message: String) {
        if (reply.started) {
            return
        }
        val path = request?.path ?: ""
        val html = request != null && acceptsHtml(request.headers) && !path.startsWith("/api/") && !path.startsWith("/dav")
        if (path.startsWith("/api/")) {
            reply.send(status, jsonError(status, message), JSON)
        } else if (html) {
            reply.send(status, errorPage(message).toByteArray(Charsets.UTF_8), HTML, htmlHeaders())
        } else {
            reply.send(status, message.toByteArray(Charsets.UTF_8), TEXT)
        }
    }

    private fun acceptsHtml(headers: Map<String, String>): Boolean {
        val accept = headers["accept"] ?: return false
        return accept.contains("text/html")
    }

    private fun wantsHtml(headers: Map<String, String>): Boolean {
        val accept = headers["accept"] ?: return true
        return accept.contains("text/html") || accept.contains("*/*")
    }

    private fun htmlHeaders(): List<Pair<String, String>> {
        return listOf(
            "Content-Security-Policy" to
                "default-src 'none'; img-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
        )
    }

    private fun listingHeaders(options: ListingOptions): List<Pair<String, String>> {
        val cookie = "${Listing.COOKIE}=${Listing.encodeCookie(options)}; Path=/; HttpOnly; SameSite=Lax"
        return htmlHeaders() + ("Set-Cookie" to cookie)
    }

    private class CappedOutputStream(
        private val output: OutputStream,
        private val max: Long,
    ) : OutputStream() {
        private var written: Long = 0L

        override fun write(value: Int) {
            count(1)
            output.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            count(length.toLong())
            output.write(buffer, offset, length)
        }

        override fun flush() {
            output.flush()
        }

        private fun count(amount: Long) {
            written += amount
            if (written > max) {
                throw StoreException(413, "總大小超過上限")
            }
        }
    }

    private fun authUsername(): String {
        return username().ifBlank { ShareDefaults.USERNAME }
    }

    private companion object {
        val BASIC = "WWW-Authenticate" to "Basic realm=\"Quick Temp Open Server\""
        const val ZIP_SPOOL_LIMIT: Long = 256L * 1024L * 1024L
    }
}
