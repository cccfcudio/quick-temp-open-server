package app.openshelf.core.zip

import app.openshelf.core.store.FileStore
import app.openshelf.core.store.Paths
import app.openshelf.core.store.StoreException
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ZipEntryPlan(
    val mountId: String,
    val relative: String,
    val archiveName: String,
    val size: Long,
    val directory: Boolean,
)

object ZipPack {
    const val MAX_ENTRIES: Int = 4000
    const val MAX_BYTES: Long = 4L * 1024L * 1024L * 1024L

    fun planTree(
        store: FileStore,
        mountId: String,
        relative: String,
        maxEntries: Int = MAX_ENTRIES,
        maxBytes: Long = MAX_BYTES,
    ): List<ZipEntryPlan> {
        val path = Paths.normalizeRelative(relative) ?: throw StoreException(400, "路徑唔合法")
        val stat = store.stat(mountId, path) ?: throw StoreException(404, "搵唔到")
        if (!stat.directory) {
            throw StoreException(400, "唔係資料夾")
        }
        val budget = Budget(maxEntries, maxBytes)
        fillDirectory(store, mountId, path, sanitizeSegment(stat.name), budget, maxBytes)
        return budget.plans
    }

    fun planSelection(
        store: FileStore,
        mountId: String,
        paths: List<String>,
        maxEntries: Int = MAX_ENTRIES,
        maxBytes: Long = MAX_BYTES,
    ): List<ZipEntryPlan> {
        if (paths.isEmpty()) {
            throw StoreException(400, "未有選擇")
        }
        val budget = Budget(maxEntries, maxBytes)
        for (raw in paths) {
            val path = Paths.normalizeRelative(raw) ?: throw StoreException(400, "路徑唔合法")
            val stat = store.stat(mountId, path) ?: throw StoreException(404, "搵唔到")
            if (stat.directory) {
                fillDirectory(store, mountId, path, sanitizeSegment(stat.name), budget, maxBytes)
            } else {
                budget.add(ZipEntryPlan(mountId, path, sanitizeSegment(stat.name), budgetBytes(stat.size, maxBytes), false))
            }
        }
        return budget.plans
    }

    fun write(
        store: FileStore,
        plans: List<ZipEntryPlan>,
        output: OutputStream,
        maxBytes: Long = MAX_BYTES,
        progress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ) {
        if (plans.isEmpty()) {
            throw StoreException(400, "未有選擇")
        }
        val zip = ZipOutputStream(NonClosingOutputStream(output))
        val buffer = ByteArray(8192)
        var actual = 0L
        var aborted = false
        try {
            plans.forEachIndexed { index, plan ->
                progress(index + 1, plans.size, plan.archiveName)
                val entry = ZipEntry(plan.archiveName)
                if (plan.directory) {
                    entry.method = ZipEntry.STORED
                    entry.size = 0L
                    entry.compressedSize = 0L
                    entry.crc = 0L
                    zip.putNextEntry(entry)
                    zip.closeEntry()
                } else {
                    entry.method = ZipEntry.DEFLATED
                    zip.putNextEntry(entry)
                    store.openRead(plan.mountId, plan.relative).use { input ->
                        var zeros = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) {
                                break
                            }
                            if (count == 0) {
                                if (++zeros > 8) {
                                    throw StoreException(403, "無法讀取")
                                }
                                continue
                            }
                            zeros = 0
                            actual += count.toLong()
                            if (actual > maxBytes) {
                                aborted = true
                                throw StoreException(413, "總大小超過上限")
                            }
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: StoreException) {
            aborted = true
            throw error
        } finally {
            try {
                zip.close()
            } catch (error: IOException) {
                if (!aborted) {
                    throw error
                }
            }
        }
    }

    fun downloadName(label: String): String {
        val safe = buildString {
            var count = 0
            for (ch in label) {
                if (count >= 60) {
                    break
                }
                val keep = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == ' '
                append(if (keep) ch else '_')
                count += 1
            }
        }.trim().trim('.')
        val base = safe.ifBlank { "quick-temp-open-server" }
        return "$base.zip"
    }

    fun sanitizeSegment(name: String): String {
        val cleaned = buildString(name.length) {
            for (ch in name) {
                if (ch == '/' || ch == '\\' || ch.code < 32) {
                    append('_')
                } else {
                    append(ch)
                }
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return "_"
        }
        return cleaned.take(180)
    }

    /** A reported size above the cap is treated as unknown so one bad length cannot veto the pack. */
    fun budgetBytes(size: Long, maxBytes: Long): Long {
        if (size <= 0L || size > maxBytes) {
            return 0L
        }
        return size
    }

    private fun fillDirectory(
        store: FileStore,
        mountId: String,
        rootRelative: String,
        rootArchive: String,
        budget: Budget,
        maxBytes: Long,
    ) {
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(rootRelative to rootArchive)
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            val (relative, archive) = pending.removeAt(0)
            if (!seen.add(relative)) {
                continue
            }
            budget.add(ZipEntryPlan(mountId, relative, "$archive/", 0L, true))
            val children = store.list(mountId, relative)
            for (child in children) {
                val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                val childArchive = "$archive/${sanitizeSegment(child.name)}"
                if (child.directory) {
                    pending.add(childRelative to childArchive)
                } else {
                    budget.add(
                        ZipEntryPlan(mountId, childRelative, childArchive, budgetBytes(child.size, maxBytes), false),
                    )
                }
            }
        }
    }

    private class Budget(private val maxEntries: Int, private val maxBytes: Long) {
        val plans = ArrayList<ZipEntryPlan>()
        private var bytes: Long = 0L

        fun add(plan: ZipEntryPlan) {
            if (plans.size >= maxEntries) {
                throw StoreException(413, "檔案太多，無法打包")
            }
            val next = bytes + plan.size
            if (next > maxBytes) {
                throw StoreException(413, "總大小超過上限")
            }
            plans.add(plan)
            bytes = next
        }
    }

    private class NonClosingOutputStream(private val output: OutputStream) : OutputStream() {
        override fun write(value: Int) {
            output.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            output.write(buffer, offset, length)
        }

        override fun flush() {
            output.flush()
        }

        override fun close() {
            output.flush()
        }
    }
}
