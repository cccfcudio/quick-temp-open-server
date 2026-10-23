package app.openshelf.core.browse

import app.openshelf.core.model.FileEntry
import app.openshelf.core.store.Paths

enum class SortKey {
    NAME,
    SIZE,
    MODIFIED,
}

enum class SortDirection {
    ASC,
    DESC,
}

enum class FileKind {
    IMAGE,
    VIDEO,
    DOCUMENT,
    OTHER,
}

data class ListingOptions(
    val sort: SortKey = SortKey.NAME,
    val direction: SortDirection = SortDirection.ASC,
    val kind: FileKind? = null,
    val query: String = "",
    val pageSize: Int = 50,
)

data class ListingWindow<T>(
    val items: List<T>,
    val page: Int,
    val pages: Int,
    val total: Int,
)

object Listing {
    const val COOKIE: String = "openshelf_list"
    private const val MAX_BATCH: Int = 200

    fun kindOf(name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> FileKind.IMAGE
            "mp4", "mov", "mkv", "webm", "avi", "3gp" -> FileKind.VIDEO
            "pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "rtf" ->
                FileKind.DOCUMENT
            else -> FileKind.OTHER
        }
    }

    fun arrange(entries: List<FileEntry>, options: ListingOptions): List<FileEntry> {
        val needle = options.query.trim()
        return entries
            .filter { entry -> matches(entry, options.kind, needle) }
            .sortedWith { left, right -> compare(left, right, options) }
    }

    fun parse(params: Map<String, String>, fallback: ListingOptions = ListingOptions()): ListingOptions {
        val sort = when (params["sort"]) {
            null -> fallback.sort
            "name" -> SortKey.NAME
            "size" -> SortKey.SIZE
            "modified" -> SortKey.MODIFIED
            else -> fallback.sort
        }
        val direction = when (params["order"]) {
            null -> fallback.direction
            "asc" -> SortDirection.ASC
            "desc" -> SortDirection.DESC
            else -> fallback.direction
        }
        val kind = when (params["kind"]) {
            null -> fallback.kind
            "all" -> null
            "image" -> FileKind.IMAGE
            "video" -> FileKind.VIDEO
            "doc" -> FileKind.DOCUMENT
            "other" -> FileKind.OTHER
            else -> fallback.kind
        }
        val query = params["q"] ?: fallback.query
        val pageSize = when (val raw = params["ps"]) {
            null -> normalizePageSize(fallback.pageSize)
            else -> raw.toIntOrNull()?.let { normalizePageSize(it) } ?: normalizePageSize(fallback.pageSize)
        }
        return ListingOptions(sort, direction, kind, query.take(200), pageSize)
    }

    fun normalizePageSize(value: Int): Int = when (value) {
        20, 100 -> value
        else -> 50
    }

    fun <T> window(items: List<T>, page: Int, pageSize: Int): ListingWindow<T> {
        val size = normalizePageSize(pageSize)
        val total = items.size
        val pages = if (total == 0) 1 else (total + size - 1) / size
        val current = page.coerceIn(1, pages)
        val from = (current - 1) * size
        val to = minOf(total, from + size)
        val slice = if (from >= total) emptyList() else items.subList(from, to).toList()
        return ListingWindow(slice, current, pages, total)
    }

    fun encodeCookie(options: ListingOptions): String {
        return listOf(
            wire(options.sort),
            wire(options.direction),
            wire(options.kind),
            normalizePageSize(options.pageSize).toString(),
            Paths.percentEncode(options.query.take(200)),
        ).joinToString(".")
    }

    fun decodeCookie(raw: String?): ListingOptions {
        if (raw.isNullOrBlank()) {
            return ListingOptions()
        }
        val modern = raw.split('.', limit = 5)
        if (modern.size >= 5 && isPageSizeToken(modern[3])) {
            val query = Paths.percentDecode(modern[4]) ?: return ListingOptions()
            return parse(
                mapOf(
                    "sort" to modern[0],
                    "order" to modern[1],
                    "kind" to modern[2],
                    "ps" to modern[3],
                    "q" to query,
                ),
            )
        }
        val parts = raw.split('.', limit = 4)
        if (parts.size < 4) {
            return ListingOptions()
        }
        val query = Paths.percentDecode(parts[3]) ?: return ListingOptions()
        return parse(
            mapOf(
                "sort" to parts[0],
                "order" to parts[1],
                "kind" to parts[2],
                "q" to query,
            ),
        )
    }

    fun queryString(options: ListingOptions, pickAll: Boolean = false, page: Int = 1): String {
        val index = if (page < 1) 1 else page
        val query = buildString {
            append("sort=").append(wire(options.sort))
            append("&order=").append(wire(options.direction))
            append("&kind=").append(wire(options.kind))
            append("&q=").append(Paths.percentEncode(options.query))
            append("&ps=").append(normalizePageSize(options.pageSize))
            append("&page=").append(index)
            if (pickAll) {
                append("&pick=1")
            }
        }
        return query
    }

    fun childrenIn(directory: String, rawPaths: List<String>): List<String> {
        val parent = Paths.normalizeRelative(directory) ?: return emptyList()
        val out = ArrayList<String>()
        for (raw in rawPaths) {
            if (out.size >= MAX_BATCH) {
                break
            }
            val path = Paths.normalizeRelative(raw) ?: continue
            val pathParent = Paths.parentRelative(path) ?: continue
            if (pathParent != parent) {
                continue
            }
            val name = Paths.fileName(path)
            if (Paths.safeFileName(name) == null) {
                continue
            }
            if (path !in out) {
                out.add(path)
            }
        }
        return out
    }

    private fun matches(entry: FileEntry, kind: FileKind?, needle: String): Boolean {
        if (needle.isNotEmpty() && !entry.name.contains(needle, ignoreCase = true)) {
            return false
        }
        if (kind == null) {
            return true
        }
        return !entry.directory && kindOf(entry.name) == kind
    }

    private fun compare(left: FileEntry, right: FileEntry, options: ListingOptions): Int {
        if (left.directory != right.directory) {
            return if (left.directory) -1 else 1
        }
        val primary = when (options.sort) {
            SortKey.NAME -> left.name.lowercase().compareTo(right.name.lowercase())
            SortKey.SIZE -> left.size.compareTo(right.size)
            SortKey.MODIFIED -> left.modifiedEpochMs.compareTo(right.modifiedEpochMs)
        }
        val directed = if (options.direction == SortDirection.ASC) primary else -primary
        if (directed != 0) {
            return directed
        }
        return left.name.lowercase().compareTo(right.name.lowercase())
    }

    private fun wire(sort: SortKey): String = when (sort) {
        SortKey.NAME -> "name"
        SortKey.SIZE -> "size"
        SortKey.MODIFIED -> "modified"
    }

    private fun wire(direction: SortDirection): String = when (direction) {
        SortDirection.ASC -> "asc"
        SortDirection.DESC -> "desc"
    }

    private fun wire(kind: FileKind?): String = when (kind) {
        null -> "all"
        FileKind.IMAGE -> "image"
        FileKind.VIDEO -> "video"
        FileKind.DOCUMENT -> "doc"
        FileKind.OTHER -> "other"
    }

    private fun isPageSizeToken(raw: String): Boolean = raw == "20" || raw == "50" || raw == "100"
}
