package app.openshelf.share

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.openshelf.core.http.mimeFor
import app.openshelf.core.model.FileEntry
import app.openshelf.core.model.MountInfo
import app.openshelf.core.model.StoredMount
import app.openshelf.core.model.StoredMountKind
import app.openshelf.core.store.DirectoryFileStore
import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import app.openshelf.core.text.TextFormat
import java.io.File
import java.io.IOException
import java.io.InputStream

internal sealed interface PreviewTarget {
    data class Disk(val file: File) : PreviewTarget
    data class Document(val uri: Uri) : PreviewTarget
}

class AndroidFileStore(
    private val context: Context,
    private val storedMounts: () -> List<StoredMount>,
) : FileStore {
    private val lock = Any()

    override fun mounts(): List<MountInfo> = storedMounts().map { MountInfo(it.id, it.displayName) }

    override fun list(mountId: String, relativePath: String): List<FileEntry> = synchronized(lock) {
        val mount = mount(mountId)
        when (mount.kind) {
            StoredMountKind.APP_DIRECTORY -> files().list(mountId, relativePath)
            StoredMountKind.SAF_TREE -> saf {
                val document = resolve(mount, relativePath) ?: throw StoreException(404, "搵唔到")
                if (!document.isDirectory) {
                    throw StoreException(400, "唔係資料夾")
                }
                document.listFiles()
                    .mapNotNull { it.toEntry() }
                    .sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name.lowercase() })
            }
        }
    }

    override fun stat(mountId: String, relativePath: String): FileEntry? = synchronized(lock) {
        val mount = mount(mountId)
        val relative = clean(relativePath)
        when (mount.kind) {
            StoredMountKind.APP_DIRECTORY -> files().stat(mountId, relative)
            StoredMountKind.SAF_TREE -> saf {
                val document = resolve(mount, relative) ?: return@saf null
                if (relative.isEmpty()) {
                    FileEntry(mount.displayName, true, 0L, document.lastModified())
                } else {
                    document.toEntry()
                }
            }
        }
    }

    internal fun previewTarget(mountId: String, relativePath: String): PreviewTarget? {
        return try {
            val mount = mount(mountId)
            val relative = clean(relativePath)
            if (relative.isEmpty()) {
                return null
            }
            when (mount.kind) {
                StoredMountKind.APP_DIRECTORY -> {
                    val file = File(mount.location, relative)
                    if (file.isFile) PreviewTarget.Disk(file) else null
                }
                StoredMountKind.SAF_TREE -> {
                    val document = resolve(mount, relative) ?: return null
                    if (document.isFile) PreviewTarget.Document(document.uri) else null
                }
            }
        } catch (error: StoreException) {
            null
        } catch (error: SecurityException) {
            null
        } catch (error: IOException) {
            null
        }
    }

    override fun openRead(mountId: String, relativePath: String): InputStream = synchronized(lock) {
        val mount = mount(mountId)
        when (mount.kind) {
            StoredMountKind.APP_DIRECTORY -> files().openRead(mountId, relativePath)
            StoredMountKind.SAF_TREE -> saf {
                val document = resolve(mount, relativePath) ?: throw StoreException(404, "搵唔到")
                if (!document.isFile) {
                    throw StoreException(400, "唔係檔案")
                }
                context.contentResolver.openInputStream(document.uri) ?: throw StoreException(403, "無法讀取")
            }
        }
    }

    override fun writeFile(mountId: String, relativePath: String, data: InputStream) {
        synchronized(lock) {
            val mount = mount(mountId)
            when (mount.kind) {
                StoredMountKind.APP_DIRECTORY -> files().writeFile(mountId, relativePath, data)
                StoredMountKind.SAF_TREE -> saf {
                    val relative = clean(relativePath)
                    if (relative.isEmpty()) {
                        throw StoreException(400, "路徑唔合法")
                    }
                    val parentRelative = Paths.parentRelative(relative) ?: throw StoreException(400, "路徑唔合法")
                    val parent = resolve(mount, parentRelative) ?: throw StoreException(409, "上層資料夾唔存在")
                    if (!parent.isDirectory) {
                        throw StoreException(409, "上層資料夾唔存在")
                    }
                    val name = Paths.fileName(relative)
                    parent.findFile(name)?.delete()
                    val created = parent.createFile(mimeFor(name).substringBefore(';'), name)
                        ?: throw StoreException(403, "無法寫入")
                    val output = context.contentResolver.openOutputStream(created.uri, "wt")
                        ?: throw StoreException(403, "無法寫入")
                    output.use { data.copyTo(it) }
                }
            }
        }
    }

    override fun delete(mountId: String, relativePath: String) = synchronized(lock) {
        val mount = mount(mountId)
        when (mount.kind) {
            StoredMountKind.APP_DIRECTORY -> files().delete(mountId, relativePath)
            StoredMountKind.SAF_TREE -> saf {
                val relative = clean(relativePath)
                if (relative.isEmpty()) {
                    throw StoreException(400, "唔可以刪除掛載根目錄")
                }
                val document = resolve(mount, relative) ?: throw StoreException(404, "搵唔到")
                if (!document.delete()) {
                    throw StoreException(403, "無法刪除")
                }
            }
        }
    }

    override fun mkdir(mountId: String, relativePath: String) {
        synchronized(lock) {
            val mount = mount(mountId)
            when (mount.kind) {
                StoredMountKind.APP_DIRECTORY -> files().mkdir(mountId, relativePath)
                StoredMountKind.SAF_TREE -> saf {
                    val relative = clean(relativePath)
                    if (relative.isEmpty()) {
                        throw StoreException(400, "路徑唔合法")
                    }
                    val parentRelative = Paths.parentRelative(relative) ?: throw StoreException(400, "路徑唔合法")
                    val parent = resolve(mount, parentRelative) ?: throw StoreException(409, "上層資料夾唔存在")
                    val name = Paths.fileName(relative)
                    if (parent.findFile(name) != null) {
                        throw StoreException(405, "已經存在")
                    }
                    parent.createDirectory(name) ?: throw StoreException(403, "無法建立")
                }
            }
        }
    }

    override fun move(
        mountId: String,
        fromRelative: String,
        toRelative: String,
        overwrite: Boolean,
    ) = synchronized(lock) {
        val mount = mount(mountId)
        when (mount.kind) {
            StoredMountKind.APP_DIRECTORY -> files().move(mountId, fromRelative, toRelative, overwrite)
            StoredMountKind.SAF_TREE -> saf { moveSaf(mount, fromRelative, toRelative, overwrite) }
        }
    }

    private fun moveSaf(mount: StoredMount, fromRelative: String, toRelative: String, overwrite: Boolean) {
        val from = clean(fromRelative)
        val to = clean(toRelative)
        if (from.isEmpty() || to.isEmpty()) {
            throw StoreException(400, "路徑唔合法")
        }
        val source = resolve(mount, from) ?: throw StoreException(404, "搵唔到")
        val parentRelative = Paths.parentRelative(to) ?: throw StoreException(400, "路徑唔合法")
        val parent = resolve(mount, parentRelative) ?: throw StoreException(409, "上層資料夾唔存在")
        val name = Paths.fileName(to)
        val sourceParent = source.parentFile
        if (sourceParent != null && sourceParent.uri == parent.uri) {
            parent.findFile(name)?.let { existing ->
                if (!overwrite) {
                    throw StoreException(412, "目標已經存在")
                }
                existing.delete()
            }
            if (!source.renameTo(name)) {
                throw StoreException(403, "無法移動")
            }
            return
        }
        if (source.isDirectory) {
            throw StoreException(501, "暫不支援搬移資料夾去另一層")
        }
        parent.findFile(name)?.let { existing ->
            if (!overwrite) {
                throw StoreException(412, "目標已經存在")
            }
            existing.delete()
        }
        val created = parent.createFile(mimeFor(name).substringBefore(';'), name)
            ?: throw StoreException(403, "無法寫入")
        val input = context.contentResolver.openInputStream(source.uri) ?: throw StoreException(403, "無法讀取")
        input.use { stream ->
            val output = context.contentResolver.openOutputStream(created.uri, "wt")
                ?: throw StoreException(403, "無法寫入")
            output.use { stream.copyTo(it) }
        }
        if (!source.delete()) {
            throw StoreException(403, "無法刪除來源")
        }
    }

    private fun files(): DirectoryFileStore {
        val roots = storedMounts().filter { it.kind == StoredMountKind.APP_DIRECTORY }.map { mount ->
            DirectoryFileStore.Root(mount.id, mount.displayName, File(mount.location))
        }
        return DirectoryFileStore(roots)
    }

    private fun mount(mountId: String): StoredMount {
        if (!Paths.isMountId(mountId)) {
            throw StoreException(400, "路徑唔合法")
        }
        return storedMounts().firstOrNull { it.id == mountId } ?: throw StoreException(404, "搵唔到掛載")
    }

    private fun clean(relativePath: String): String {
        return Paths.normalizeRelative(relativePath) ?: throw StoreException(400, "路徑唔合法")
    }

    private fun resolve(mount: StoredMount, relativePath: String): DocumentFile? {
        val relative = clean(relativePath)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(mount.location)) ?: return null
        if (relative.isEmpty()) {
            return root
        }
        var current = root
        for (segment in relative.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun DocumentFile.byteSize(): Long {
        if (isDirectory) {
            return 0L
        }
        val metadata = length()
        if (metadata > 0L) {
            return metadata
        }
        val stat = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (error: SecurityException) {
            0L
        } catch (error: IOException) {
            0L
        }
        return TextFormat.chooseByteSize(metadata, stat)
    }

    private fun DocumentFile.toEntry(): FileEntry? {
        val entryName = name ?: return null
        return FileEntry(
            name = entryName,
            directory = isDirectory,
            size = byteSize(),
            modifiedEpochMs = lastModified(),
        )
    }

    private inline fun <T> saf(block: () -> T): T {
        try {
            return block()
        } catch (error: StoreException) {
            throw error
        } catch (error: SecurityException) {
            throw StoreException(403, "沒有權限")
        } catch (error: IOException) {
            throw StoreException(403, "無法讀寫")
        }
    }
}
