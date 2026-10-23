package app.openshelf.core.store

import app.openshelf.core.model.FileEntry
import app.openshelf.core.model.MountInfo
import java.io.File
import java.io.IOException
import java.io.InputStream

class DirectoryFileStore(
    private val roots: () -> List<Root>,
) : FileStore {
    constructor(roots: List<Root>) : this({ roots })

    data class Root(
        val id: String,
        val displayName: String,
        val directory: File,
    )

    override fun mounts(): List<MountInfo> = roots().map { MountInfo(it.id, it.displayName) }

    override fun list(mountId: String, relativePath: String): List<FileEntry> {
        val directory = resolve(mountId, relativePath)
        if (!directory.exists()) {
            throw StoreException(404, "搵唔到")
        }
        if (!directory.isDirectory) {
            throw StoreException(400, "唔係資料夾")
        }
        val children = directory.listFiles() ?: throw StoreException(403, "無法讀取")
        return children
            .filter { !it.name.startsWith(TEMP_PREFIX) }
            .map { it.toEntry() }
            .sortedWith(ENTRY_ORDER)
    }

    override fun stat(mountId: String, relativePath: String): FileEntry? {
        val relative = clean(relativePath)
        val file = resolve(mountId, relative)
        if (!file.exists()) {
            return null
        }
        if (relative.isEmpty()) {
            return FileEntry(root(mountId).displayName, true, 0L, file.lastModified())
        }
        return file.toEntry()
    }

    override fun openRead(mountId: String, relativePath: String): InputStream {
        val file = resolve(mountId, relativePath)
        if (!file.exists()) {
            throw StoreException(404, "搵唔到")
        }
        if (!file.isFile) {
            throw StoreException(400, "唔係檔案")
        }
        return file.inputStream()
    }

    override fun writeFile(mountId: String, relativePath: String, data: InputStream) {
        val relative = clean(relativePath)
        if (relative.isEmpty()) {
            throw StoreException(400, "路徑唔合法")
        }
        val parentRelative = Paths.parentRelative(relative) ?: throw StoreException(400, "路徑唔合法")
        val parent = resolve(mountId, parentRelative)
        if (!parent.isDirectory) {
            throw StoreException(409, "上層資料夾唔存在")
        }
        val target = resolve(mountId, relative)
        if (target.isDirectory) {
            throw StoreException(409, "同名資料夾已經存在")
        }
        val temporary = File(parent, TEMP_PREFIX + target.name + "-" + System.nanoTime())
        try {
            temporary.outputStream().use { output -> data.copyTo(output) }
            if (target.exists() && !target.delete()) {
                throw StoreException(403, "無法覆寫")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: StoreException) {
            temporary.delete()
            throw error
        } catch (error: IOException) {
            temporary.delete()
            throw StoreException(403, "無法寫入")
        }
    }

    override fun delete(mountId: String, relativePath: String) {
        val relative = clean(relativePath)
        if (relative.isEmpty()) {
            throw StoreException(400, "唔可以刪除掛載根目錄")
        }
        val file = resolve(mountId, relative)
        if (!file.exists()) {
            throw StoreException(404, "搵唔到")
        }
        if (!file.deleteRecursively()) {
            throw StoreException(403, "無法刪除")
        }
    }

    override fun mkdir(mountId: String, relativePath: String) {
        val relative = clean(relativePath)
        if (relative.isEmpty()) {
            throw StoreException(400, "路徑唔合法")
        }
        val parentRelative = Paths.parentRelative(relative) ?: throw StoreException(400, "路徑唔合法")
        val parent = resolve(mountId, parentRelative)
        if (!parent.isDirectory) {
            throw StoreException(409, "上層資料夾唔存在")
        }
        val target = resolve(mountId, relative)
        if (target.exists()) {
            throw StoreException(405, "已經存在")
        }
        if (!target.mkdir()) {
            throw StoreException(403, "無法建立")
        }
    }

    override fun move(mountId: String, fromRelative: String, toRelative: String, overwrite: Boolean) {
        val from = clean(fromRelative)
        val to = clean(toRelative)
        if (from.isEmpty() || to.isEmpty()) {
            throw StoreException(400, "路徑唔合法")
        }
        val source = resolve(mountId, from)
        if (!source.exists()) {
            throw StoreException(404, "搵唔到")
        }
        val parentRelative = Paths.parentRelative(to) ?: throw StoreException(400, "路徑唔合法")
        val parent = resolve(mountId, parentRelative)
        if (!parent.isDirectory) {
            throw StoreException(409, "上層資料夾唔存在")
        }
        val destination = resolve(mountId, to)
        if (destination.exists()) {
            if (!overwrite) {
                throw StoreException(412, "目標已經存在")
            }
            if (!destination.deleteRecursively()) {
                throw StoreException(403, "無法覆寫")
            }
        }
        if (!source.renameTo(destination)) {
            throw StoreException(403, "無法移動")
        }
    }

    private fun root(mountId: String): Root {
        if (!Paths.isMountId(mountId)) {
            throw StoreException(400, "路徑唔合法")
        }
        return roots().firstOrNull { it.id == mountId } ?: throw StoreException(404, "搵唔到掛載")
    }

    private fun clean(relativePath: String): String {
        return Paths.normalizeRelative(relativePath) ?: throw StoreException(400, "路徑唔合法")
    }

    private fun resolve(mountId: String, relativePath: String): File {
        val relative = clean(relativePath)
        val base = root(mountId).directory.canonicalFile
        val target = if (relative.isEmpty()) base else File(base, relative).canonicalFile
        val basePath = base.path
        val targetPath = target.path
        if (targetPath != basePath && !targetPath.startsWith(basePath + File.separator)) {
            throw StoreException(400, "路徑唔合法")
        }
        return target
    }

    private fun File.toEntry(): FileEntry {
        return FileEntry(
            name = name,
            directory = isDirectory,
            size = if (isDirectory) 0L else length(),
            modifiedEpochMs = lastModified(),
        )
    }

    private companion object {
        const val TEMP_PREFIX = ".openshelf-tmp-"
        val ENTRY_ORDER: Comparator<FileEntry> =
            compareBy<FileEntry> { !it.directory }.thenBy { it.name.lowercase() }
    }
}
