package app.openshelf.core.zip

import app.openshelf.core.store.DirectoryFileStore
import app.openshelf.core.store.StoreException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZipPackTest {
    @Test
    fun packsANestedFolderAndASelection() {
        val root = Files.createTempDirectory("openshelf-zip").toFile()
        try {
            File(root, "box/sub").mkdirs()
            File(root, "box/sub/a.txt").writeText("alpha")
            File(root, "box/b.txt").writeText("beta")
            File(root, "box/empty").mkdirs()
            File(root, "secret.txt").writeText("hidden")
            val store = DirectoryFileStore(listOf(DirectoryFileStore.Root("share", "分享夾", root)))

            val plans = ZipPack.planTree(store, "share", "box")
            val out = ByteArrayOutputStream()
            var ticks = 0
            ZipPack.write(store, plans, out) { _, _, _ -> ticks += 1 }
            assertEquals(plans.size, ticks)
            val packed = readZip(out.toByteArray())
            assertEquals("alpha", packed["box/sub/a.txt"])
            assertEquals("beta", packed["box/b.txt"])
            assertTrue(packed.containsKey("box/empty/"))
            assertTrue(packed.keys.none { it.contains("secret") || it.contains("..") })

            val selected = ZipPack.planSelection(store, "share", listOf("box/b.txt", "secret.txt"))
            val picked = ByteArrayOutputStream()
            ZipPack.write(store, selected, picked)
            val names = readZip(picked.toByteArray())
            assertEquals("beta", names["b.txt"])
            assertEquals("hidden", names["secret.txt"])
            assertEquals(2, names.keys.count { !it.endsWith("/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyDirectoryStillHasAMarkerAndLimitsFailClosed() {
        val root = Files.createTempDirectory("openshelf-zip-limit").toFile()
        try {
            File(root, "empty").mkdirs()
            File(root, "one.txt").writeText("1234567890")
            File(root, "two.txt").writeText("1234567890")
            File(root, "three.txt").writeText("1234567890")
            val store = DirectoryFileStore(listOf(DirectoryFileStore.Root("share", "分享夾", root)))
            val empty = ZipPack.planTree(store, "share", "empty")
            assertEquals(listOf("empty/"), empty.map { it.archiveName })
            assertTrue(empty.single().directory)

            try {
                ZipPack.planTree(store, "share", "", maxEntries = 2)
                fail("應拒絕太多項目")
            } catch (error: StoreException) {
                assertEquals(413, error.status)
                assertEquals("檔案太多，無法打包", error.message)
            }

            try {
                ZipPack.planSelection(store, "share", listOf("../secret.txt"))
                fail("應拒絕越界路徑")
            } catch (error: StoreException) {
                assertEquals(400, error.status)
            }

            val plans = ZipPack.planTree(store, "share", "", maxBytes = 10_000)
            try {
                ZipPack.write(store, plans, ByteArrayOutputStream(), maxBytes = 4)
                fail("應拒絕超出實際讀取上限")
            } catch (error: StoreException) {
                assertEquals(413, error.status)
            }
            assertEquals(0L, ZipPack.budgetBytes(5L * 1024 * 1024 * 1024, ZipPack.MAX_BYTES))
            assertEquals(5L * 1024 * 1024, ZipPack.budgetBytes(5L * 1024 * 1024, ZipPack.MAX_BYTES))
            assertEquals(0L, ZipPack.budgetBytes(0, ZipPack.MAX_BYTES))
            assertEquals(0L, ZipPack.budgetBytes(-1, ZipPack.MAX_BYTES))
            assertEquals("分享夾.zip", ZipPack.downloadName("分享夾"))
            assertEquals("a_b.zip", ZipPack.downloadName("a/b"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun readZip(body: ByteArray): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        ZipInputStream(body.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                found[entry.name] = if (entry.isDirectory || entry.name.endsWith("/")) {
                    ""
                } else {
                    readAll(zip).toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return found
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64)
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
