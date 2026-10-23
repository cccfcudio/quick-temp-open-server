package app.openshelf.core.http

import app.openshelf.core.json.JsonValue
import app.openshelf.core.json.MiniJson
import app.openshelf.core.json.asObject
import app.openshelf.core.json.asString
import app.openshelf.core.model.FileEntry
import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import app.openshelf.core.text.TextFormat

internal sealed interface ApiPath {
    data object Root : ApiPath
    data class Node(val mount: String, val relative: String) : ApiPath
}

internal fun handleJsonApi(
    reply: HttpReply,
    request: HttpRequest,
    store: FileStore,
    port: Int,
    headOnly: Boolean,
) {
    val action = request.path.removePrefix("/api/fs/").substringBefore('/')
    when (action) {
        "mounts" -> {
            if (request.method != "GET" && request.method != "HEAD") {
                throw StoreException(405, "方法唔支援")
            }
            val items = store.mounts().map { mount ->
                MiniJson.obj(
                    "name" to MiniJson.str(mount.displayName),
                    "path" to MiniJson.str("/${mount.id}"),
                    "is_dir" to MiniJson.bool(true),
                    "provider" to MiniJson.str("local"),
                )
            }
            reply.send(200, jsonOk(MiniJson.arr(items)), JSON, headOnly = headOnly)
        }
        "list" -> list(reply, request, store, headOnly)
        "get" -> get(reply, request, store, port, headOnly)
        "put" -> put(reply, request, store)
        "mkdir" -> mutate(reply, request, store, remove = false)
        "remove" -> mutate(reply, request, store, remove = true)
        else -> throw StoreException(404, "搵唔到")
    }
}

internal fun jsonError(status: Int, message: String): ByteArray {
    return MiniJson.stringify(
        MiniJson.obj(
            "code" to MiniJson.num(status.toLong()),
            "message" to MiniJson.str(message),
            "data" to MiniJson.nil,
        ),
    ).toByteArray(Charsets.UTF_8)
}

private fun list(reply: HttpReply, request: HttpRequest, store: FileStore, headOnly: Boolean) {
    if (request.method != "GET" && request.method != "HEAD" && request.method != "POST") {
        throw StoreException(405, "方法唔支援")
    }
    val path = if (request.method == "POST") jsonPath(request) else request.query["path"]
    when (val apiPath = parseApiPath(path)) {
        ApiPath.Root -> {
            val items = store.mounts().map { mount ->
                MiniJson.obj(
                    "name" to MiniJson.str(mount.displayName),
                    "size" to MiniJson.num(0),
                    "is_dir" to MiniJson.bool(true),
                    "modified" to MiniJson.str(""),
                    "path" to MiniJson.str("/${mount.id}"),
                    "type" to MiniJson.num(1),
                )
            }
            reply.send(200, jsonOk(listData(items)), JSON, headOnly = headOnly)
        }
        is ApiPath.Node -> {
            val stat = store.stat(apiPath.mount, apiPath.relative) ?: throw StoreException(404, "搵唔到")
            if (!stat.directory) {
                throw StoreException(400, "唔係資料夾")
            }
            val items = store.list(apiPath.mount, apiPath.relative).map { entry ->
                entryJson(apiPath.mount, apiPath.relative, entry)
            }
            reply.send(200, jsonOk(listData(items)), JSON, headOnly = headOnly)
        }
    }
}

private fun get(reply: HttpReply, request: HttpRequest, store: FileStore, port: Int, headOnly: Boolean) {
    if (request.method == "GET" || request.method == "HEAD") {
        val apiPath = parseApiPath(request.query["path"])
        if (apiPath !is ApiPath.Node || apiPath.relative.isEmpty()) {
            throw StoreException(400, "路徑唔合法")
        }
        val stat = store.stat(apiPath.mount, apiPath.relative) ?: throw StoreException(404, "搵唔到")
        if (stat.directory) {
            throw StoreException(400, "唔係檔案")
        }
        store.openRead(apiPath.mount, apiPath.relative).use { input ->
            reply.sendStream(200, input, stat.size, mimeFor(stat.name), headOnly = headOnly || request.method == "HEAD")
        }
        return
    }
    if (request.method != "POST") {
        throw StoreException(405, "方法唔支援")
    }
    val apiPath = parseApiPath(jsonPath(request))
    if (apiPath !is ApiPath.Node) {
        throw StoreException(400, "路徑唔合法")
    }
    val stat = store.stat(apiPath.mount, apiPath.relative) ?: throw StoreException(404, "搵唔到")
    val raw = externalBase(request, port) + Paths.joinUrl("/raw", apiPath.mount, apiPath.relative)
    val data = MiniJson.obj(
        "name" to MiniJson.str(stat.name),
        "size" to MiniJson.num(stat.size),
        "is_dir" to MiniJson.bool(stat.directory),
        "raw_url" to MiniJson.str(raw),
        "path" to MiniJson.str(displayPath(apiPath)),
    )
    reply.send(200, jsonOk(data), JSON)
}

private fun put(reply: HttpReply, request: HttpRequest, store: FileStore) {
    if (request.method != "PUT") {
        throw StoreException(405, "方法唔支援")
    }
    val apiPath = parseApiPath(request.query["path"])
    if (apiPath !is ApiPath.Node || apiPath.relative.isEmpty()) {
        throw StoreException(400, "路徑唔合法")
    }
    store.writeFile(apiPath.mount, apiPath.relative, request.body)
    reply.send(201, jsonOk(MiniJson.str(displayPath(apiPath))), JSON)
}

private fun mutate(reply: HttpReply, request: HttpRequest, store: FileStore, remove: Boolean) {
    if (request.method != "POST") {
        throw StoreException(405, "方法唔支援")
    }
    val apiPath = parseApiPath(jsonPath(request))
    if (apiPath !is ApiPath.Node || apiPath.relative.isEmpty()) {
        throw StoreException(400, "路徑唔合法")
    }
    if (remove) {
        store.delete(apiPath.mount, apiPath.relative)
    } else {
        store.mkdir(apiPath.mount, apiPath.relative)
    }
    reply.send(200, jsonOk(MiniJson.str(displayPath(apiPath))), JSON)
}

internal fun parseApiPath(raw: String?): ApiPath {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "/") {
        return ApiPath.Root
    }
    val withSlash = if (text.startsWith("/")) text else "/$text"
    val decoded = Paths.decodeRequestTarget(withSlash.substringBefore('?')) ?: throw StoreException(400, "路徑唔合法")
    if (decoded.first == "/") {
        return ApiPath.Root
    }
    val rest = decoded.first.removePrefix("/")
    val mount = rest.substringBefore('/')
    if (!Paths.isMountId(mount)) {
        throw StoreException(400, "路徑唔合法")
    }
    val relative = if (rest.contains('/')) rest.substringAfter('/') else ""
    return ApiPath.Node(mount, relative)
}

private fun jsonPath(request: HttpRequest): String? {
    val body = readSmallBody(request, 1_048_576).toString(Charsets.UTF_8)
    if (body.isBlank()) {
        return null
    }
    val parsed = try {
        MiniJson.parse(body)
    } catch (error: IllegalArgumentException) {
        throw StoreException(400, "JSON 唔合法")
    }
    return parsed.asObject()?.get("path")?.asString()
}

private fun entryJson(mount: String, parent: String, entry: FileEntry): JsonValue {
    val relative = if (parent.isEmpty()) entry.name else "$parent/${entry.name}"
    return MiniJson.obj(
        "name" to MiniJson.str(entry.name),
        "size" to MiniJson.num(entry.size),
        "is_dir" to MiniJson.bool(entry.directory),
        "modified" to MiniJson.str(TextFormat.iso(entry.modifiedEpochMs)),
        "path" to MiniJson.str("/$mount/$relative"),
        "type" to MiniJson.num(if (entry.directory) 1L else 0L),
    )
}

private fun listData(items: List<JsonValue>): JsonValue {
    return MiniJson.obj(
        "content" to MiniJson.arr(items),
        "total" to MiniJson.num(items.size.toLong()),
        "write" to MiniJson.bool(true),
        "provider" to MiniJson.str("local"),
    )
}

private fun jsonOk(data: JsonValue): ByteArray {
    return MiniJson.stringify(
        MiniJson.obj(
            "code" to MiniJson.num(200),
            "message" to MiniJson.str("success"),
            "data" to data,
        ),
    ).toByteArray(Charsets.UTF_8)
}

private fun displayPath(path: ApiPath.Node): String {
    return if (path.relative.isEmpty()) "/${path.mount}" else "/${path.mount}/${path.relative}"
}

private fun externalBase(request: HttpRequest, port: Int): String {
    val host = request.headers["host"] ?: "127.0.0.1:$port"
    val https = request.headers["x-forwarded-proto"]?.contains("https", ignoreCase = true) == true
    return if (https) "https://$host" else "http://$host"
}
