package app.openshelf.core.http

import app.openshelf.core.driver.DriverCatalog
import app.openshelf.core.json.MiniJson
import app.openshelf.core.json.asObject
import app.openshelf.core.json.asString
import app.openshelf.core.model.DriverKind
import app.openshelf.core.model.FileEntry
import app.openshelf.core.model.MountInfo
import app.openshelf.core.store.DirectoryFileStore
import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import app.openshelf.core.tunnel.CloudflaredCommand
import app.openshelf.core.tunnel.TunnelLogParser
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import javax.imageio.ImageIO
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareHttpServerTest {
    @Test
    fun happyPathListsUploadsAndDownloads() {
        withServer(password = "secret") { server, root ->
            File(root, "說明.txt").writeText("你好")
            val encoded = Paths.percentEncode("說明.txt")
            val denied = exchange(server.port, request("GET", "/raw/share/$encoded"))
            assertEquals(401, denied.status)

            val fetched = exchange(server.port, request("GET", "/raw/share/$encoded", auth = "secret"))
            assertEquals(200, fetched.status)
            assertEquals("你好", fetched.body.toString(Charsets.UTF_8))

            val put = exchange(
                server.port,
                request("PUT", "/dav/share/hello.txt", auth = "secret", body = "hello".toByteArray()),
            )
            assertEquals(201, put.status)
            assertEquals("hello", File(root, "hello.txt").readText())

            val propfind = exchange(
                server.port,
                request("PROPFIND", "/dav/share/", auth = "secret", extra = listOf("Depth" to "1"), body = ByteArray(0)),
            )
            assertEquals(207, propfind.status)
            val xml = propfind.body.toString(Charsets.UTF_8)
            assertTrue(xml.contains("hello.txt"))
            assertTrue(xml.contains("說明.txt"))

            val boundary = "openshelfbound"
            val payload = "雲端唔使".toByteArray(Charsets.UTF_8)
            val multipart = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"mount\"\r\n\r\n")
                append("share\r\n")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"dir\"\r\n\r\n")
                append("\r\n")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"筆記.txt\"\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
            }.toByteArray(Charsets.UTF_8) + payload + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val uploaded = exchange(
                server.port,
                request(
                    "POST",
                    "/upload",
                    auth = "secret",
                    extra = listOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                    body = multipart,
                ),
            )
            assertEquals(302, uploaded.status)
            assertEquals("雲端唔使", File(root, "筆記.txt").readText())

            val listed = exchange(
                server.port,
                request(
                    "POST",
                    "/api/fs/list",
                    auth = "secret",
                    extra = listOf("Content-Type" to "application/json"),
                    body = """{"path":"/share"}""".toByteArray(Charsets.UTF_8),
                ),
            )
            assertEquals(200, listed.status)
            val json = listed.body.toString(Charsets.UTF_8)
            assertTrue(json.contains("筆記.txt"))
            assertTrue(json.contains("\"code\":200"))

            val removed = exchange(
                server.port,
                request(
                    "POST",
                    "/api/fs/remove",
                    auth = "secret",
                    extra = listOf("Content-Type" to "application/json"),
                    body = """{"path":"/share/hello.txt"}""".toByteArray(Charsets.UTF_8),
                ),
            )
            assertEquals(200, removed.status)
            assertFalse(File(root, "hello.txt").exists())
        }
    }

    @Test
    fun cookieLoginThenBrowse() {
        withServer(password = "secret") { server, _ ->
            val login = exchange(
                server.port,
                request("POST", "/login", body = "password=secret&next=%2F".toByteArray(Charsets.UTF_8)),
            )
            assertEquals(302, login.status)
            val cookie = login.headers["set-cookie"] ?: error("沒有 cookie")
            assertTrue(cookie.contains("openshelf_session="))
            val home = exchange(server.port, request("GET", "/", extra = listOf("Cookie" to cookie.substringBefore(';'))))
            assertEquals(200, home.status)
            assertTrue(home.body.toString(Charsets.UTF_8).contains("快速臨時開放伺服器"))
        }
    }

    @Test
    fun bearerAndTokenHeaderWork() {
        withServer(password = "secret") { server, _ ->
            val bearer = exchange(server.port, request("GET", "/healthz"))
            assertEquals(200, bearer.status)
            val page = exchange(
                server.port,
                request("GET", "/", extra = listOf("Authorization" to "Bearer secret")),
            )
            assertEquals(200, page.status)
            val header = exchange(
                server.port,
                request("GET", "/", extra = listOf("X-QuickTemp-Token" to "secret")),
            )
            assertEquals(200, header.status)
            val wrong = exchange(
                server.port,
                request("GET", "/", extra = listOf("Authorization" to "Bearer nope")),
            )
            assertEquals(401, wrong.status)
        }
    }

    @Test
    fun traversalAndSymlinkStayInsideTheMount() {
        val outside = Files.createTempDirectory("openshelf-outside").toFile()
        try {
            File(outside, "secret.txt").writeText("nope")
            withServer { server, root ->
                val escaped = exchange(server.port, request("GET", "/raw/share/%2e%2e/%2e%2e/secret.txt"))
                assertEquals(400, escaped.status)
                val link = File(root, "link.txt")
                Files.createSymbolicLink(link.toPath(), File(outside, "secret.txt").toPath())
                val followed = exchange(server.port, request("GET", "/raw/share/link.txt"))
                assertEquals(400, followed.status)
                assertFalse(followed.body.toString(Charsets.UTF_8).contains("nope"))
            }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun mkdirIsSingleLevel() {
        withServer { server, root ->
            val missingParent = exchange(server.port, request("MKCOL", "/dav/share/a/b"))
            assertEquals(409, missingParent.status)
            val created = exchange(server.port, request("MKCOL", "/dav/share/a"))
            assertEquals(201, created.status)
            assertTrue(File(root, "a").isDirectory)
        }
    }

    @Test
    fun jsonGetReportsForwardedHttps() {
        withServer { server, root ->
            File(root, "a.txt").writeText("x")
            val response = exchange(
                server.port,
                request(
                    "POST",
                    "/api/fs/get",
                    extra = listOf(
                        "Content-Type" to "application/json",
                        "Host" to "demo.trycloudflare.com",
                        "X-Forwarded-Proto" to "https",
                    ),
                    body = """{"path":"/share/a.txt"}""".toByteArray(Charsets.UTF_8),
                ),
            )
            assertEquals(200, response.status)
            val raw = MiniJson.parse(response.body.toString(Charsets.UTF_8))
                .asObject()?.get("data")?.asObject()?.get("raw_url")?.asString()
            assertEquals("https://demo.trycloudflare.com/raw/share/a.txt", raw)
        }
    }

    @Test
    fun miniJsonRoundTrip() {
        val parsed = MiniJson.parse("""{"name":"說明","ok":true,"n":1,"tags":["a",null]}""")
        val name = parsed.asObject()?.get("name")?.asString()
        assertEquals("說明", name)
        assertTrue(MiniJson.stringify(parsed).contains("說明"))
    }

    @Test
    fun pathsRejectDotDot() {
        assertEquals(null, Paths.normalizeRelative("a/../../b"))
        assertEquals("a/b", Paths.normalizeRelative("/a//b/"))
        assertNotNull(Paths.decodeRequestTarget("/raw/share/%E8%AA%AA%E6%98%8E.txt"))
    }

    @Test
    fun tunnelParserAndCommand() {
        val line = "INF |  https://lime-eagle-12.trycloudflare.com  |"
        assertEquals("https://lime-eagle-12.trycloudflare.com", TunnelLogParser.findUrl(line))
        val args = CloudflaredCommand.arguments("http://127.0.0.1:28765")
        assertTrue(args.contains("--no-autoupdate"))
        assertTrue(args.contains("http2"))
        assertEquals("http://127.0.0.1:28765", args.last())
    }

    @Test
    fun browseSortsFiltersAndDeletesASelection() {
        withServer(password = "secret") { server, root ->
            File(root, "small.txt").writeText("ab")
            File(root, "big.jpg").writeBytes(ByteArray(100))
            File(root, "clip.mp4").writeBytes(ByteArray(20))
            File(root, "notes.txt").writeText("note")
            File(root, "album").mkdirs()

            val sorted = exchange(
                server.port,
                request("GET", "/browse/share/?sort=size&order=desc", auth = "secret"),
            )
            assertEquals(200, sorted.status)
            val html = sorted.body.toString(Charsets.UTF_8)
            val album = html.indexOf("album/")
            val big = html.indexOf("big.jpg")
            val clip = html.indexOf("clip.mp4")
            val notes = html.indexOf("notes.txt")
            val small = html.indexOf("small.txt")
            assertTrue(album in 0 until big)
            assertTrue(big < clip && clip < notes && notes < small)
            val cookie = sorted.headers["set-cookie"] ?: error("沒有清單 cookie")
            assertTrue(cookie.startsWith("openshelf_list=size.desc."))

            val remembered = exchange(
                server.port,
                request("GET", "/browse/share/", auth = "secret", extra = listOf("Cookie" to cookie.substringBefore(';'))),
            )
            val again = remembered.body.toString(Charsets.UTF_8)
            assertTrue(again.indexOf("big.jpg") < again.indexOf("small.txt"))

            val images = exchange(
                server.port,
                request("GET", "/browse/share/?kind=image", auth = "secret"),
            ).body.toString(Charsets.UTF_8)
            assertTrue(images.contains("big.jpg"))
            assertFalse(images.contains("notes.txt"))
            assertFalse(images.contains("album/"))

            val form = "mount=share&back=&sort=name&order=asc&kind=all&q=&action=delete&path=small.txt&path=notes.txt"
            val asked = exchange(
                server.port,
                request("POST", "/batch", auth = "secret", body = form.toByteArray(Charsets.UTF_8)),
            )
            assertEquals(200, asked.status)
            assertTrue(asked.body.toString(Charsets.UTF_8).contains("確認刪除"))
            assertTrue(File(root, "small.txt").exists())

            val removed = exchange(
                server.port,
                request(
                    "POST",
                    "/batch",
                    auth = "secret",
                    body = "$form&confirm=1".toByteArray(Charsets.UTF_8),
                ),
            )
            assertEquals(302, removed.status)
            assertFalse(File(root, "small.txt").exists())
            assertFalse(File(root, "notes.txt").exists())
            assertTrue(File(root, "big.jpg").exists())

            val outside = exchange(
                server.port,
                request(
                    "POST",
                    "/batch",
                    auth = "secret",
                    body = "mount=share&back=&action=delete&confirm=1&path=../secret.txt".toByteArray(Charsets.UTF_8),
                ),
            )
            assertEquals(400, outside.status)
        }
    }

    @Test
    fun zipDownloadAndPageSize() {
        withServer(password = "secret") { server, root ->
            val sub = File(root, "subdir")
            sub.mkdirs()
            File(sub, "note.txt").writeText("hello-zip")
            File(root, "a.txt").writeText("alpha")
            File(root, "b.txt").writeText("beta")
            File(root, "secret.txt").writeText("nope")
            for (index in 0 until 25) {
                File(root, "n%02d.txt".format(index)).writeText("x")
            }

            val denied = exchange(server.port, request("GET", "/zip/share/subdir"))
            assertEquals(401, denied.status)

            val folder = exchange(server.port, request("GET", "/zip/share/subdir", auth = "secret"))
            assertEquals(200, folder.status)
            assertTrue(folder.headers["content-type"]?.contains("application/zip") == true)
            assertTrue((folder.headers["content-length"]?.toLongOrNull() ?: 0L) > 0L)
            val disposition = folder.headers["content-disposition"] ?: ""
            assertTrue(disposition.contains("subdir.zip"))
            val packed = zipEntries(folder.body)
            assertEquals("hello-zip", packed["subdir/note.txt"])
            assertFalse(packed.containsKey("subdir/../secret.txt"))

            val selected = exchange(
                server.port,
                request(
                    "POST",
                    "/zip",
                    auth = "secret",
                    body = "mount=share&back=&path=a.txt&path=b.txt&path=../secret.txt".toByteArray(Charsets.UTF_8),
                ),
            )
            assertEquals(200, selected.status)
            val chosen = zipEntries(selected.body)
            assertEquals("alpha", chosen["a.txt"])
            assertEquals("beta", chosen["b.txt"])
            assertFalse(chosen.keys.any { it.contains("secret") })

            val page1 = exchange(
                server.port,
                request("GET", "/browse/share/?ps=20&page=1&sort=name&order=asc", auth = "secret"),
            ).body.toString(Charsets.UTF_8)
            assertTrue(page1.contains("n00.txt</a>"))
            assertTrue(page1.contains("n16.txt</a>"))
            assertFalse(page1.contains("n20.txt</a>"))
            assertTrue(page1.contains("class=\"ico\""))
            assertTrue(page1.contains("下載成個資料夾"))
            assertTrue(page1.contains("打包 ZIP"))
            assertTrue(page1.contains("下載所選"))
            assertTrue(page1.contains("data-progress=\"開始下載…\""))
            assertTrue(page1.contains("formaction=\"/zip\""))
            assertTrue(page1.contains("formaction=\"/download\""))
            assertTrue(page1.contains("id=\"progress\""))

            val page2 = exchange(
                server.port,
                request("GET", "/browse/share/?ps=20&page=2&sort=name&order=asc", auth = "secret"),
            ).body.toString(Charsets.UTF_8)
            assertTrue(page2.contains("n20.txt</a>"))
            assertFalse(page2.contains("n00.txt</a>"))
            val cookie = exchange(
                server.port,
                request("GET", "/browse/share/?ps=20", auth = "secret"),
            ).headers["set-cookie"] ?: ""
            assertTrue(cookie.contains("openshelf_list=") && cookie.contains(".20."))
        }
    }

    @Test
    fun wrongMetadataStillDownloadsAndPacks() {
        val photo = "jpeg-bytes".toByteArray()
        val hidden = "hidden-bytes".toByteArray()
        val store = ReportingStore(
            mapOf(
                "Default_20251219_221552.jpg" to (5L * 1024 * 1024 * 1024 to photo),
                "zero-but-real.jpg" to (0L to hidden),
                "empty.jpg" to (0L to ByteArray(0)),
            ),
        )
        val server = ShareHttpServer(store = store, bindHost = "127.0.0.1", requestedPort = 0)
        server.start()
        try {
            val huge = exchange(server.port, request("GET", "/raw/share/Default_20251219_221552.jpg?download=1"))
            assertEquals(200, huge.status)
            assertEquals(photo.size.toLong(), huge.headers["content-length"]?.toLongOrNull())
            assertEquals("jpeg-bytes", huge.body.toString(Charsets.UTF_8))
            assertTrue(huge.headers["content-type"]?.contains("application/octet-stream") == true)
            assertTrue(huge.headers["content-disposition"]?.contains("attachment") == true)
            assertFalse((huge.headers["content-length"]?.toLongOrNull() ?: 0L) > 1024L)

            val inline = exchange(server.port, request("GET", "/raw/share/zero-but-real.jpg"))
            assertEquals(200, inline.status)
            assertEquals("hidden-bytes", inline.body.toString(Charsets.UTF_8))
            assertTrue(inline.headers["content-type"]?.startsWith("image/jpeg") == true)

            val zero = exchange(server.port, request("GET", "/raw/share/zero-but-real.jpg?download=1"))
            assertEquals(200, zero.status)
            assertEquals(hidden.size.toLong(), zero.headers["content-length"]?.toLongOrNull())
            assertEquals("hidden-bytes", zero.body.toString(Charsets.UTF_8))

            val empty = exchange(
                server.port,
                request("GET", "/raw/share/empty.jpg?download=1", extra = listOf("Accept" to "text/html")),
            )
            assertEquals(400, empty.status)
            val emptyPage = empty.body.toString(Charsets.UTF_8)
            assertTrue(emptyPage.contains("呢個檔案係空嘅"))
            assertTrue(emptyPage.contains("<h1>未能完成</h1>"))

            val packed = exchange(
                server.port,
                request(
                    "POST",
                    "/zip",
                    body = "mount=share&back=&path=Default_20251219_221552.jpg&path=zero-but-real.jpg".toByteArray(),
                ),
            )
            assertEquals(200, packed.status)
            assertTrue((packed.headers["content-length"]?.toLongOrNull() ?: 0L) > 0L)
            val entries = zipEntries(packed.body)
            assertEquals("jpeg-bytes", entries["Default_20251219_221552.jpg"])
            assertEquals("hidden-bytes", entries["zero-but-real.jpg"])

            val folder = exchange(server.port, request("GET", "/zip/share"))
            assertEquals(200, folder.status)
            assertEquals("jpeg-bytes", zipEntries(folder.body)["分享夾/Default_20251219_221552.jpg"])

            val one = exchange(
                server.port,
                request("POST", "/download", body = "mount=share&back=&path=zero-but-real.jpg".toByteArray()),
            )
            assertEquals(303, one.status)
            val location = one.headers["location"] ?: error("沒有 Location")
            val followed = exchange(server.port, request("GET", location))
            assertEquals("hidden-bytes", followed.body.toString(Charsets.UTF_8))

            val many = exchange(
                server.port,
                request(
                    "POST",
                    "/download",
                    body = "mount=share&back=&path=Default_20251219_221552.jpg&path=zero-but-real.jpg".toByteArray(),
                ),
            )
            assertEquals(200, many.status)
            val page = many.body.toString(Charsets.UTF_8)
            assertTrue(page.contains("下載 2 個檔案"))
            assertTrue(page.contains("zero-but-real.jpg"))
            assertTrue(page.contains("開始下載…"))

            val folderOnly = exchange(
                server.port,
                request(
                    "POST",
                    "/download",
                    extra = listOf("Accept" to "text/html"),
                    body = "mount=share&back=&path=Album".toByteArray(),
                ),
            )
            assertEquals(400, folderOnly.status)
            assertTrue(folderOnly.body.toString(Charsets.UTF_8).contains("資料夾請用下載成個資料夾"))

            val none = exchange(
                server.port,
                request(
                    "POST",
                    "/zip",
                    extra = listOf("Accept" to "text/html"),
                    body = "mount=share&back=".toByteArray(),
                ),
            )
            assertEquals(400, none.status)
            val nonePage = none.body.toString(Charsets.UTF_8)
            assertTrue(nonePage.contains("未有選擇"))
            assertTrue(nonePage.contains("<h1>未能完成</h1>"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun thumbnailsAreSmallJpegsAndListingPointsAtThem() {
        withServer(password = "secret", preview = { store -> JvmJpegPreview(store) }) { server, root ->
            val picture = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
            picture.setRGB(0, 0, 0x336699)
            ImageIO.write(picture, "png", File(root, "shot.png"))
            File(root, "clip.mp4").writeBytes(byteArrayOf(0, 1, 2, 3))
            File(root, "notes.txt").writeText("plain")

            val denied = exchange(server.port, request("GET", "/thumb/share/shot.png?edge=96"))
            assertEquals(401, denied.status)

            val page = exchange(
                server.port,
                request("GET", "/browse/share/", auth = "secret"),
            ).body.toString(Charsets.UTF_8)
            assertTrue(page.contains("/thumb/share/shot.png?edge=96&amp;t="))
            assertFalse(page.contains("loading=\"lazy\""))
            assertTrue(page.contains("/thumb/share/clip.mp4?edge=96&amp;t="))
            assertTrue(page.contains("class=\"badge\">▶</span>"))
            assertTrue(page.contains("<img class=\"preview\""))
            assertFalse(page.contains("/thumb/share/notes.txt"))
            assertTrue(page.contains("notes.txt"))
            val token = Regex("edge=96&amp;t=([0-9a-f]+)").find(page)?.groupValues?.get(1)
                ?: error("thumb URL has no session token")
            val forged = exchange(
                server.port,
                request("GET", "/thumb/share/shot.png?edge=96&t=deadbeef"),
            )
            assertEquals(401, forged.status)
            val fromPage = exchange(
                server.port,
                request("GET", "/thumb/share/shot.png?edge=96&t=$token"),
            )
            assertEquals(200, fromPage.status)
            assertTrue(fromPage.headers["content-type"]?.startsWith("image/jpeg") == true)
            assertTrue(fromPage.body.size > 2 && fromPage.body[0] == 0xFF.toByte() && fromPage.body[1] == 0xD8.toByte())
            val textWithToken = exchange(
                server.port,
                request("GET", "/thumb/share/notes.txt?t=$token"),
            )
            assertEquals(404, textWithToken.status)

            val thumb = exchange(
                server.port,
                request("GET", "/thumb/share/shot.png?edge=96", auth = "secret"),
            )
            assertEquals(200, thumb.status)
            assertTrue(thumb.headers["content-type"]?.startsWith("image/jpeg") == true)
            assertTrue(thumb.body.size > 2 && thumb.body[0] == 0xFF.toByte() && thumb.body[1] == 0xD8.toByte())
            assertTrue(thumb.body.size < 64 * 1024)

            val video = exchange(
                server.port,
                request("GET", "/thumb/share/clip.mp4", auth = "secret"),
            )
            assertEquals(404, video.status)

            val text = exchange(
                server.port,
                request("GET", "/thumb/share/notes.txt", auth = "secret"),
            )
            assertEquals(404, text.status)

            val listing = exchange(
                server.port,
                request("GET", "/browse/share/", auth = "secret"),
            )
            assertTrue(listing.headers["content-security-policy"]?.contains("img-src 'self'") == true)
        }
    }

    @Test
    fun otherDriversAreStubs() {
        var thrown = false
        try {
            DriverCatalog.unsupported(DriverKind.S3)
        } catch (error: NotImplementedError) {
            thrown = true
            assertTrue(error.message?.contains("TODO") == true)
        }
        assertTrue(thrown)
        assertEquals(4, DriverCatalog.upcoming.size)
    }

    private fun withServer(
        password: String = "",
        preview: ((FileStore) -> JpegPreview)? = null,
        block: (ShareHttpServer, File) -> Unit,
    ) {
        val root = Files.createTempDirectory("openshelf-root").toFile()
        val store = DirectoryFileStore(listOf(DirectoryFileStore.Root("share", "分享夾", root)))
        val server = ShareHttpServer(
            store = store,
            password = { password },
            bindHost = "127.0.0.1",
            requestedPort = 0,
            preview = preview?.invoke(store),
        )
        server.start()
        try {
            block(server, root)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun request(
        method: String,
        path: String,
        auth: String? = null,
        extra: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): ByteArray {
        val headers = ArrayList<Pair<String, String>>()
        if (extra.none { it.first.equals("Host", ignoreCase = true) }) {
            headers.add("Host" to "127.0.0.1")
        }
        if (auth != null) {
            val token = Base64.getEncoder().encodeToString("openshelf:$auth".toByteArray(Charsets.UTF_8))
            headers.add("Authorization" to "Basic $token")
        }
        headers.addAll(extra)
        if (body != null && headers.none { it.first.equals("Content-Length", ignoreCase = true) }) {
            headers.add("Content-Length" to body.size.toString())
        }
        val text = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            for ((name, value) in headers) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        val head = text.toByteArray(Charsets.US_ASCII)
        return if (body == null) head else head + body
    }

    private fun exchange(port: Int, raw: ByteArray): RawHttp {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 5000
            socket.getOutputStream().write(raw)
            socket.shutdownOutput()
            val input = socket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) {
                    break
                }
                headerBytes.write(value)
                val current = headerBytes.toByteArray()
                if (current.size >= 4 &&
                    current[current.size - 4] == '\r'.code.toByte() &&
                    current[current.size - 3] == '\n'.code.toByte() &&
                    current[current.size - 2] == '\r'.code.toByte() &&
                    current[current.size - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
            val lines = headerText.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toInt() ?: 0
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) {
                    continue
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            val body = if (chunked) readChunks(input) else readCounted(input, headers["content-length"]?.toIntOrNull() ?: 0)
            return RawHttp(status, headers, body)
        }
    }

    private fun readCounted(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) {
                break
            }
            offset += count
        }
        return if (offset == length) body else body.copyOf(offset)
    }

    private fun readChunks(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) {
                        break
                    }
                }
                break
            }
            val chunk = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(chunk, offset, size - offset)
                if (count < 0) {
                    break
                }
                offset += count
            }
            out.write(chunk, 0, offset)
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 8192) {
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
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun zipEntries(body: ByteArray): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        ZipInputStream(body.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && !entry.name.endsWith("/")) {
                    found[entry.name] = readStream(zip).toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return found
    }

    private fun readStream(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

private class ReportingStore(
    private val files: Map<String, Pair<Long, ByteArray>>,
) : FileStore {
    override fun mounts(): List<MountInfo> = listOf(MountInfo("share", "分享夾"))

    override fun list(mountId: String, relativePath: String): List<FileEntry> {
        if (mountId != "share") {
            throw StoreException(404, "搵唔到")
        }
        if (relativePath == "Album") {
            return emptyList()
        }
        requireRoot(mountId, relativePath)
        val folders = listOf(FileEntry("Album", true, 0L, 0L))
        val docs = files.map { (name, value) -> FileEntry(name, false, value.first, 0L) }
        return folders + docs
    }

    override fun stat(mountId: String, relativePath: String): FileEntry? {
        if (mountId != "share") {
            return null
        }
        if (relativePath.isEmpty()) {
            return FileEntry("分享夾", true, 0L, 0L)
        }
        if (relativePath == "Album") {
            return FileEntry("Album", true, 0L, 0L)
        }
        val file = files[relativePath] ?: return null
        return FileEntry(relativePath, false, file.first, 0L)
    }

    override fun openRead(mountId: String, relativePath: String): InputStream {
        val file = files[relativePath] ?: throw StoreException(404, "搵唔到")
        return ByteArrayInputStream(file.second)
    }

    override fun writeFile(mountId: String, relativePath: String, data: InputStream) {
        throw StoreException(405, "唔支援")
    }

    override fun delete(mountId: String, relativePath: String) {
        throw StoreException(405, "唔支援")
    }

    override fun mkdir(mountId: String, relativePath: String) {
        throw StoreException(405, "唔支援")
    }

    override fun move(mountId: String, fromRelative: String, toRelative: String, overwrite: Boolean) {
        throw StoreException(405, "唔支援")
    }

    private fun requireRoot(mountId: String, relativePath: String) {
        if (mountId != "share" || relativePath.isNotEmpty()) {
            throw StoreException(404, "搵唔到")
        }
    }
}

private data class RawHttp(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)
