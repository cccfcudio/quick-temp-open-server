package app.openshelf.core.store

import app.openshelf.core.model.FileEntry
import app.openshelf.core.model.MountInfo
import java.io.InputStream

interface FileStore {
    fun mounts(): List<MountInfo>

    fun list(mountId: String, relativePath: String): List<FileEntry>

    fun stat(mountId: String, relativePath: String): FileEntry?

    fun openRead(mountId: String, relativePath: String): InputStream

    fun writeFile(mountId: String, relativePath: String, data: InputStream)

    fun delete(mountId: String, relativePath: String)

    fun mkdir(mountId: String, relativePath: String)

    fun move(mountId: String, fromRelative: String, toRelative: String, overwrite: Boolean)
}

class StoreException(val status: Int, message: String) : Exception(message)
