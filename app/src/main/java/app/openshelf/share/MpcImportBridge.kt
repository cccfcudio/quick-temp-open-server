package app.openshelf.share

import android.content.Context
import android.net.Uri
import android.os.Environment
import app.openshelf.core.model.StoredMount
import app.openshelf.core.mpc.FsProbe
import app.openshelf.core.mpc.MpcImport
import app.openshelf.core.mpc.MpcPlanRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

object MpcImportBridge {
    const val MAX_BYTES: Int = MpcImport.MAX_CHARS

    fun externalRoot(): String {
        return Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
    }

    fun plan(context: Context, json: String, mounted: List<StoredMount>): List<MpcPlanRow> {
        val root = externalRoot()
        return MpcImport.plan(
            json = json,
            externalRoot = root,
            mountedLocations = mounted.map { it.location },
            probe = ::probe,
            safGranted = { uri -> granted(context, uri, root) },
        )
    }

    fun readText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("empty")
        input.use { stream ->
            return readLimited(stream, MAX_BYTES)
        }
    }

    fun existingAncestor(relative: String?): String? {
        if (relative.isNullOrBlank()) {
            return null
        }
        val root = File(externalRoot())
        var accumulated = ""
        var last: String? = null
        for (part in relative.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") {
                return last
            }
            accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
            val directory = File(root, accumulated)
            val exists = try {
                directory.isDirectory
            } catch (error: SecurityException) {
                false
            }
            if (!exists) {
                break
            }
            last = accumulated
        }
        return last
    }

    private fun probe(path: String): FsProbe {
        return try {
            val file = File(path)
            when {
                file.isDirectory && file.canRead() && file.list() != null -> FsProbe.DIRECTORY
                file.exists() && !file.isDirectory -> FsProbe.NOT_DIRECTORY
                else -> FsProbe.MISSING
            }
        } catch (error: SecurityException) {
            FsProbe.MISSING
        }
    }

    private fun granted(context: Context, uri: String, root: String): Boolean {
        val permissions = try {
            context.contentResolver.persistedUriPermissions
        } catch (error: SecurityException) {
            return false
        }
        return permissions.any { permission ->
            permission.isReadPermission && MpcImport.samePlace(permission.uri.toString(), uri, root)
        }
    }

    private fun readLimited(input: InputStream, limit: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) {
                break
            }
            total += read
            if (total > limit) {
                throw IOException("too large")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}
