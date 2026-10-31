package app.openshelf.core.mpc

import app.openshelf.core.json.JsonValue
import app.openshelf.core.json.MiniJson
import app.openshelf.core.json.asObject
import app.openshelf.core.json.asString
import app.openshelf.core.model.StoredMountKind

/**
 * MultiProfileCam 匯出嘅 Profile 陣列（gson.toJson）。
 * 空 storagePath + PRIVATE 唔會當成公開 Documents：現代 Android 多數係相機 App 自己嘅私有目錄。
 */
object MpcFolder {
    const val PACKAGE_NAME: String = "com.cccfcudio.multiprofilecam"
    private val UNSAFE = Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]")

    fun safeFolderName(profileName: String, profileId: String): String {
        val cleaned = profileName.replace(" ", "_").replace(UNSAFE, "")
        return cleaned.ifBlank { "profile_" + profileId.take(8) }
    }
}

enum class MpcTier {
    PRIVATE,
    VISIBLE,
    AUTO_SYNC,
    UNSPECIFIED,
    UNRECOGNIZED,
}

enum class FsProbe {
    DIRECTORY,
    NOT_DIRECTORY,
    MISSING,
}

enum class MpcRowStatus {
    READY,
    NEEDS_PERMISSION,
    PRIVATE,
    DUPLICATE,
    ALREADY_MOUNTED,
    UNUSABLE,
}

data class MpcPlanRow(
    val profileId: String,
    val name: String,
    val status: MpcRowStatus,
    val preview: String?,
    val duplicateOf: String?,
    val location: String?,
    val kind: StoredMountKind?,
    val pickerRelative: String?,
    val pickerUri: String?,
)

object MpcImport {
    const val MAX_CHARS: Int = 2_000_000

    fun selectable(status: MpcRowStatus): Boolean = when (status) {
        MpcRowStatus.READY,
        MpcRowStatus.NEEDS_PERMISSION,
        MpcRowStatus.PRIVATE,
        -> true
        MpcRowStatus.DUPLICATE,
        MpcRowStatus.ALREADY_MOUNTED,
        MpcRowStatus.UNUSABLE,
        -> false
    }

    fun plan(
        json: String,
        externalRoot: String,
        mountedLocations: Collection<String>,
        probe: (String) -> FsProbe,
        safGranted: (String) -> Boolean,
    ): List<MpcPlanRow> {
        val parsed = parse(json)
        val root = normalizeFs(externalRoot)
        val candidates = parsed.map { profile ->
            resolve(profile, root, probe, safGranted)
        }
        return assign(candidates, mountedKeys(mountedLocations, root))
    }

    fun samePlace(left: String, right: String, externalRoot: String): Boolean {
        val root = normalizeFs(externalRoot)
        return locationKey(left, root) == locationKey(right, root)
    }

    private data class ParsedProfile(
        val sourceId: String,
        val rowId: String,
        val name: String,
        val storagePath: String,
        val tier: MpcTier,
    )

    private data class Candidate(
        val rowId: String,
        val name: String,
        val status: MpcRowStatus,
        val preview: String?,
        val placeKey: String?,
        val location: String?,
        val kind: StoredMountKind?,
        val pickerRelative: String?,
        val pickerUri: String?,
    )

    private fun parse(json: String): List<ParsedProfile> {
        val text = json.trim().removePrefix("\uFEFF")
        if (text.length > MAX_CHARS) {
            throw IllegalArgumentException("JSON 太大")
        }
        val rootValue = MiniJson.parse(text)
        val items = when (rootValue) {
            is JsonValue.Arr -> rootValue.values
            is JsonValue.Obj -> {
                val map = rootValue.values
                if (map.containsKey("id") || map.containsKey("name") || map.containsKey("storagePath")) {
                    listOf(rootValue)
                } else {
                    throw IllegalArgumentException("JSON 唔係設定檔陣列")
                }
            }
            else -> throw IllegalArgumentException("JSON 唔係設定檔陣列")
        }
        val used = HashSet<String>()
        val profiles = ArrayList<ParsedProfile>()
        items.forEachIndexed { index, item ->
            val obj = item.asObject() ?: return@forEachIndexed
            val sourceId = obj["id"]?.asString()?.trim().orEmpty().ifBlank { "profile_$index" }
            var rowId = sourceId
            var suffix = 2
            while (!used.add(rowId)) {
                rowId = "$sourceId#$suffix"
                suffix += 1
            }
            val name = obj["name"]?.asString()?.trim().orEmpty().ifBlank { sourceId }
            val storagePath = obj["storagePath"]?.asString()?.trim().orEmpty()
            profiles.add(
                ParsedProfile(
                    sourceId = sourceId,
                    rowId = rowId,
                    name = name,
                    storagePath = storagePath,
                    tier = parseTier(obj["storageTier"]),
                ),
            )
        }
        return profiles
    }

    private fun parseTier(value: JsonValue?): MpcTier {
        if (value == null || value is JsonValue.Null) {
            return MpcTier.UNSPECIFIED
        }
        val raw = value.asString()?.trim()
        if (raw == null) {
            return MpcTier.UNRECOGNIZED
        }
        if (raw.isEmpty()) {
            return MpcTier.UNSPECIFIED
        }
        return when (raw.uppercase()) {
            MpcTier.PRIVATE.name -> MpcTier.PRIVATE
            MpcTier.VISIBLE.name -> MpcTier.VISIBLE
            MpcTier.AUTO_SYNC.name -> MpcTier.AUTO_SYNC
            else -> MpcTier.UNRECOGNIZED
        }
    }

    private fun resolve(
        profile: ParsedProfile,
        root: String,
        probe: (String) -> FsProbe,
        safGranted: (String) -> Boolean,
    ): Candidate {
        val path = profile.storagePath.trim()
        return when {
            path.isEmpty() -> resolveTier(profile, root, probe)
            path.startsWith("content://", ignoreCase = true) -> resolveSaf(profile, path, root, safGranted)
            path.startsWith("file:", ignoreCase = true) -> {
                val filePath = fileUriToPath(path)
                if (filePath == null) {
                    unusable(profile, path)
                } else {
                    resolveFilesystem(profile, filePath, root, probe, pickerUri = null)
                }
            }
            isTreeStyle(path) -> {
                val relative = primaryRelative(path)
                if (relative == null || root.isEmpty()) {
                    unusable(profile, path)
                } else {
                    resolveFilesystem(profile, join(root, relative), root, probe, pickerUri = null)
                }
            }
            path.startsWith("/") -> resolveFilesystem(profile, normalizeFs(path), root, probe, pickerUri = null)
            else -> unusable(profile, path)
        }
    }

    private fun resolveTier(profile: ParsedProfile, root: String, probe: (String) -> FsProbe): Candidate {
        val safe = MpcFolder.safeFolderName(profile.name, profile.sourceId)
        return when (profile.tier) {
            MpcTier.VISIBLE -> publicRelative(profile, root, "Pictures/MultiProfileCam/$safe", probe)
            MpcTier.AUTO_SYNC -> publicRelative(profile, root, "DCIM/MultiProfileCam/$safe", probe)
            MpcTier.PRIVATE,
            MpcTier.UNSPECIFIED,
            -> privateStorage(profile, root, safe, probe)
            MpcTier.UNRECOGNIZED -> unusable(profile, "")
        }
    }

    private fun publicRelative(
        profile: ParsedProfile,
        root: String,
        relative: String,
        probe: (String) -> FsProbe,
    ): Candidate {
        val clean = sanitizeRelative(relative)
        if (clean == null || root.isEmpty()) {
            return unusable(profile, relative)
        }
        return resolveFilesystem(profile, join(root, clean), root, probe, pickerUri = null)
    }

    private fun privateStorage(
        profile: ParsedProfile,
        root: String,
        safe: String,
        probe: (String) -> FsProbe,
    ): Candidate {
        if (root.isNotEmpty()) {
            val base = "Android/data/${MpcFolder.PACKAGE_NAME}/files"
            val probes = listOf(
                "$base/Pictures/MultiProfileCam/$safe",
                "$base/Documents/MultiProfileCam/$safe",
                "$base/DCIM/MultiProfileCam/$safe",
            )
            for (relative in probes) {
                val absolute = join(root, relative)
                if (probe(absolute) == FsProbe.DIRECTORY) {
                    return readyFilesystem(profile, absolute)
                }
            }
        }
        return Candidate(
            rowId = profile.rowId,
            name = profile.name,
            status = MpcRowStatus.PRIVATE,
            preview = null,
            placeKey = null,
            location = null,
            kind = null,
            pickerRelative = null,
            pickerUri = null,
        )
    }

    private fun resolveSaf(
        profile: ParsedProfile,
        uri: String,
        root: String,
        safGranted: (String) -> Boolean,
    ): Candidate {
        val relative = primaryRelative(uri)
        val preview = if (relative != null && root.isNotEmpty()) join(root, relative) else uri
        val key = placeKeyFor(uri, root)
        if (safGranted(uri)) {
            return Candidate(
                rowId = profile.rowId,
                name = profile.name,
                status = MpcRowStatus.READY,
                preview = preview,
                placeKey = key,
                location = uri,
                kind = StoredMountKind.SAF_TREE,
                pickerRelative = null,
                pickerUri = null,
            )
        }
        return Candidate(
            rowId = profile.rowId,
            name = profile.name,
            status = MpcRowStatus.NEEDS_PERMISSION,
            preview = preview,
            placeKey = key,
            location = null,
            kind = null,
            pickerRelative = relative,
            pickerUri = uri,
        )
    }

    private fun resolveFilesystem(
        profile: ParsedProfile,
        absolute: String,
        root: String,
        probe: (String) -> FsProbe,
        pickerUri: String?,
    ): Candidate {
        val path = normalizeFs(absolute)
        if (path.isEmpty() || !path.startsWith("/")) {
            return unusable(profile, absolute)
        }
        return when (probe(path)) {
            FsProbe.DIRECTORY -> readyFilesystem(profile, path)
            FsProbe.NOT_DIRECTORY -> unusable(profile, path)
            FsProbe.MISSING -> Candidate(
                rowId = profile.rowId,
                name = profile.name,
                status = MpcRowStatus.NEEDS_PERMISSION,
                preview = path,
                placeKey = "fs:$path",
                location = null,
                kind = null,
                pickerRelative = relativeUnder(root, path),
                pickerUri = pickerUri,
            )
        }
    }

    private fun readyFilesystem(profile: ParsedProfile, path: String): Candidate {
        return Candidate(
            rowId = profile.rowId,
            name = profile.name,
            status = MpcRowStatus.READY,
            preview = path,
            placeKey = "fs:$path",
            location = path,
            kind = StoredMountKind.APP_DIRECTORY,
            pickerRelative = null,
            pickerUri = null,
        )
    }

    private fun unusable(profile: ParsedProfile, preview: String): Candidate {
        return Candidate(
            rowId = profile.rowId,
            name = profile.name,
            status = MpcRowStatus.UNUSABLE,
            preview = preview.ifBlank { null },
            placeKey = null,
            location = null,
            kind = null,
            pickerRelative = null,
            pickerUri = null,
        )
    }

    private fun assign(candidates: List<Candidate>, mounted: Set<String>): List<MpcPlanRow> {
        val groups = LinkedHashMap<String, MutableList<Candidate>>()
        for (candidate in candidates) {
            val key = candidate.placeKey ?: continue
            groups.getOrPut(key) { ArrayList() }.add(candidate)
        }
        val representative = HashMap<String, String>()
        for ((key, group) in groups) {
            val best = group.firstOrNull { it.status == MpcRowStatus.READY } ?: group.first()
            representative[key] = best.rowId
        }
        return candidates.map { candidate ->
            val key = candidate.placeKey
            val status = when {
                key != null && key in mounted -> MpcRowStatus.ALREADY_MOUNTED
                key != null && representative[key] != candidate.rowId -> MpcRowStatus.DUPLICATE
                else -> candidate.status
            }
            val duplicateOf = if (status == MpcRowStatus.DUPLICATE && key != null) {
                val winner = representative[key]
                candidates.firstOrNull { it.rowId == winner }?.name
            } else {
                null
            }
            val mountable = status == MpcRowStatus.READY
            MpcPlanRow(
                profileId = candidate.rowId,
                name = candidate.name,
                status = status,
                preview = candidate.preview,
                duplicateOf = duplicateOf,
                location = if (mountable) candidate.location else null,
                kind = if (mountable) candidate.kind else null,
                pickerRelative = if (status == MpcRowStatus.NEEDS_PERMISSION) candidate.pickerRelative else null,
                pickerUri = if (status == MpcRowStatus.NEEDS_PERMISSION) candidate.pickerUri else null,
            )
        }
    }

    private fun mountedKeys(locations: Collection<String>, root: String): Set<String> {
        return locations.map { locationKey(it, root) }.toSet()
    }

    private fun placeKeyFor(location: String, root: String): String = locationKey(location, root)

    private fun locationKey(location: String, root: String): String {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) {
            return "empty"
        }
        val tree = isContentUri(trimmed) || isTreeStyle(trimmed)
        if (tree) {
            val relative = primaryRelative(trimmed)
            if (relative != null && root.isNotEmpty()) {
                return "fs:" + join(root, relative)
            }
        }
        if (isContentUri(trimmed)) {
            return "uri:" + percentDecode(trimmed).trimEnd('/')
        }
        if (trimmed.startsWith("file:", ignoreCase = true)) {
            val path = fileUriToPath(trimmed)
            if (path != null) {
                return "fs:$path"
            }
        }
        val absolute = if (trimmed.startsWith("/")) normalizeFs(trimmed) else join(root, trimmed)
        return "fs:$absolute"
    }

    private fun isContentUri(raw: String): Boolean = raw.startsWith("content://", ignoreCase = true)

    private fun isTreeStyle(raw: String): Boolean {
        val decoded = percentDecode(raw.trim())
        return decoded.contains("/tree/") || decoded.startsWith("primary:")
    }

    private fun primaryRelative(raw: String): String? {
        val decoded = percentDecode(raw.trim())
        val documentId = when {
            decoded.contains("/tree/") -> decoded.substringAfter("/tree/")
                .substringBefore("/document/")
                .substringBefore('?')
                .substringBefore('#')
            decoded.startsWith("primary:") -> decoded.substringBefore('?').substringBefore('#')
            else -> return null
        }
        if (!documentId.startsWith("primary:")) {
            return null
        }
        return sanitizeRelative(documentId.removePrefix("primary:"))
    }

    private fun sanitizeRelative(relative: String): String? {
        val parts = relative.split('/', '\\')
        if (parts.any { it == ".." }) {
            return null
        }
        val cleaned = parts.filter { it.isNotEmpty() && it != "." }
        if (cleaned.isEmpty()) {
            return null
        }
        return cleaned.joinToString("/")
    }

    private fun relativeUnder(root: String, absolute: String): String? {
        if (root.isEmpty()) {
            return null
        }
        val base = root.trimEnd('/')
        val path = normalizeFs(absolute)
        val prefix = "$base/"
        if (!path.startsWith(prefix)) {
            return null
        }
        return sanitizeRelative(path.removePrefix(prefix))
    }

    private fun fileUriToPath(raw: String): String? {
        val decoded = percentDecode(raw.trim())
        if (!decoded.startsWith("file:", ignoreCase = true)) {
            return null
        }
        val rest = decoded.substring(5)
        val path = if (rest.startsWith("//")) {
            val hostAndPath = rest.substring(2)
            val slash = hostAndPath.indexOf('/')
            if (slash < 0) {
                return null
            }
            hostAndPath.substring(slash)
        } else {
            rest
        }
        if (!path.startsWith("/")) {
            return null
        }
        return normalizeFs(path)
    }

    private fun join(root: String, relative: String): String {
        return normalizeFs(root.trimEnd('/') + "/" + relative.trim('/'))
    }

    private fun normalizeFs(path: String): String {
        val slash = path.replace('\\', '/')
        val absolute = slash.startsWith("/")
        val out = ArrayList<String>()
        for (part in slash.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) {
                    out.removeAt(out.lastIndex)
                }
                else -> out.add(part)
            }
        }
        val joined = out.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    private fun percentDecode(raw: String): String {
        val bytes = ArrayList<Byte>()
        val out = StringBuilder()
        fun flush() {
            if (bytes.isEmpty()) {
                return
            }
            out.append(bytes.toByteArray().toString(Charsets.UTF_8))
            bytes.clear()
        }
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%' && index + 2 < raw.length) {
                val code = raw.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.add(code.toByte())
                    index += 3
                    continue
                }
            }
            flush()
            out.append(ch)
            index += 1
        }
        flush()
        return out.toString()
    }
}
