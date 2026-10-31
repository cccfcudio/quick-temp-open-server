package app.openshelf.core.mpc

import app.openshelf.core.model.StoredMountKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpcImportTest {
    @Test
    fun safeFolderNameMatchesCameraRules() {
        assertEquals("Worksite_1", MpcFolder.safeFolderName("Worksite 1", "work1"))
        assertEquals("工地_一", MpcFolder.safeFolderName("工地 一", "id"))
        assertEquals("A_B", MpcFolder.safeFolderName("A B!", "id"))
        assertEquals("hello-world", MpcFolder.safeFolderName("hello-world", "id"))
        assertEquals("profile_abcd1234", MpcFolder.safeFolderName("!!!", "abcd1234zzzz"))
        assertEquals("___", MpcFolder.safeFolderName("   ", ""))
        assertEquals("profile_", MpcFolder.safeFolderName("", ""))
    }

    @Test
    fun sampleExportResolvesVisibleSafAndLegacy() {
        val readable = setOf("/storage/emulated/0/DCIM/MultiProfileCam/Legacy")
        val rows = plan(SAMPLE, readable = readable)
        assertEquals(3, rows.size)

        val work = rows[0]
        assertEquals("work1", work.profileId)
        assertEquals("Worksite 1", work.name)
        assertEquals(MpcRowStatus.NEEDS_PERMISSION, work.status)
        assertEquals("/storage/emulated/0/Pictures/MultiProfileCam/Worksite_1", work.preview)
        assertEquals("Pictures/MultiProfileCam/Worksite_1", work.pickerRelative)
        assertNull(work.location)

        val family = rows[1]
        assertEquals(MpcRowStatus.NEEDS_PERMISSION, family.status)
        assertEquals("/storage/emulated/0/Pictures/Family", family.preview)
        assertEquals("Pictures/Family", family.pickerRelative)
        assertTrue(family.pickerUri!!.startsWith("content://"))
        assertNull(family.kind)

        val legacy = rows[2]
        assertEquals(MpcRowStatus.READY, legacy.status)
        assertEquals("/storage/emulated/0/DCIM/MultiProfileCam/Legacy", legacy.location)
        assertEquals(StoredMountKind.APP_DIRECTORY, legacy.kind)
    }

    @Test
    fun emptyVisibleBecomesReadyWhenTheDirectoryCanBeListed() {
        val path = "/storage/emulated/0/Pictures/MultiProfileCam/Worksite_1"
        val rows = plan(SAMPLE, readable = setOf(path))
        assertEquals(MpcRowStatus.READY, rows[0].status)
        assertEquals(path, rows[0].location)
        assertEquals(StoredMountKind.APP_DIRECTORY, rows[0].kind)
    }

    @Test
    fun autoSyncUsesDcim() {
        val rows = plan("""[{"id":"a","name":"Night Shift","storagePath":"","storageTier":"AUTO_SYNC"}]""")
        assertEquals("/storage/emulated/0/DCIM/MultiProfileCam/Night_Shift", rows[0].preview)
        assertEquals(MpcRowStatus.NEEDS_PERMISSION, rows[0].status)
    }

    @Test
    fun privateEmptyPathIsNotPublicDocuments() {
        val rows = plan("""[{"id":"secret99","name":"Secret","storagePath":"","storageTier":"PRIVATE"}]""")
        val row = rows.single()
        assertEquals(MpcRowStatus.PRIVATE, row.status)
        assertNull(row.preview)
        assertNull(row.location)
        assertNull(row.pickerRelative)
        assertFalse(row.toString().contains("/Documents/MultiProfileCam"))
        assertTrue(MpcImport.selectable(row.status))
    }

    @Test
    fun missingTierIsTreatedAsPrivate() {
        val rows = plan("""[{"id":"p","name":"Plain","storagePath":""}]""")
        assertEquals(MpcRowStatus.PRIVATE, rows.single().status)
        assertNull(rows.single().preview)
    }

    @Test
    fun readablePrivateAppDirCanMount() {
        val path = "/storage/emulated/0/Android/data/com.cccfcudio.multiprofilecam/files/Pictures/MultiProfileCam/Secret"
        val rows = plan(
            """[{"id":"secret99","name":"Secret","storagePath":"","storageTier":"PRIVATE"}]""",
            readable = setOf(path),
        )
        assertEquals(MpcRowStatus.READY, rows.single().status)
        assertEquals(path, rows.single().location)
        assertEquals(StoredMountKind.APP_DIRECTORY, rows.single().kind)
    }

    @Test
    fun grantedContentUriMountsAsSafTree() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FFamily"
        val rows = plan(SAMPLE, granted = setOf(uri))
        val family = rows[1]
        assertEquals(MpcRowStatus.READY, family.status)
        assertEquals(uri, family.location)
        assertEquals(StoredMountKind.SAF_TREE, family.kind)
    }

    @Test
    fun treePrimaryStyleBecomesFilesystemPath() {
        val json = """[{"id":"t","name":"Tree","storagePath":"/tree/primary:DCIM/MultiProfileCam/Legacy","storageTier":"PRIVATE"}]"""
        val path = "/storage/emulated/0/DCIM/MultiProfileCam/Legacy"
        val rows = plan(json, readable = setOf(path))
        assertEquals(MpcRowStatus.READY, rows.single().status)
        assertEquals(path, rows.single().location)
    }

    @Test
    fun fileUriAndTraversal() {
        val ok = plan(
            """[{"id":"f","name":"File","storagePath":"file:///storage/emulated/0/Pictures/MultiProfileCam/Worksite_1"}]""",
            readable = setOf("/storage/emulated/0/Pictures/MultiProfileCam/Worksite_1"),
        )
        assertEquals(MpcRowStatus.READY, ok.single().status)

        val escaped = plan(
            """[{"id":"bad","name":"Bad","storagePath":"/tree/primary:Pictures/../../etc"}]""",
        )
        assertEquals(MpcRowStatus.UNUSABLE, escaped.single().status)
    }

    @Test
    fun dedupesSameFolderAndPrefersReadableCopy() {
        val path = "/storage/emulated/0/Pictures/MultiProfileCam/Worksite_1"
        val json = """
            [
              {"id":"a","name":"Needs grant","storagePath":"content://com.android.externalstorage.documents/tree/primary%3APictures%2FMultiProfileCam%2FWorksite_1","storageTier":"PRIVATE"},
              {"id":"b","name":"Readable","storagePath":"$path","storageTier":"VISIBLE"}
            ]
        """.trimIndent()
        val rows = plan(json, readable = setOf(path))
        assertEquals(MpcRowStatus.DUPLICATE, rows[0].status)
        assertEquals("Readable", rows[0].duplicateOf)
        assertFalse(MpcImport.selectable(rows[0].status))
        assertEquals(MpcRowStatus.READY, rows[1].status)
        assertEquals(path, rows[1].location)
    }

    @Test
    fun skipsFoldersAlreadyMountedFromEitherForm() {
        val path = "/storage/emulated/0/DCIM/MultiProfileCam/Legacy"
        val mounted = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FMultiProfileCam%2FLegacy"
        val rows = plan(
            """[{"id":"legacy","name":"Legacy Path","storagePath":"$path","storageTier":"AUTO_SYNC"}]""",
            mounted = listOf(mounted),
            readable = setOf(path),
        )
        assertEquals(MpcRowStatus.ALREADY_MOUNTED, rows.single().status)
        assertNull(rows.single().location)
        assertTrue(MpcImport.samePlace(path, mounted, ROOT))
    }

    @Test
    fun identicalEmptyTierPathsCollapse() {
        val json = """
            [
              {"id":"1","name":"Worksite 1","storagePath":"","storageTier":"VISIBLE"},
              {"id":"2","name":"Worksite 1","storagePath":"","storageTier":"VISIBLE"}
            ]
        """.trimIndent()
        val rows = plan(json)
        assertEquals(MpcRowStatus.NEEDS_PERMISSION, rows[0].status)
        assertEquals(MpcRowStatus.DUPLICATE, rows[1].status)
        assertEquals("Worksite 1", rows[1].duplicateOf)
    }

    @Test
    fun filePathAndUnknownTierAreUnusable() {
        val file = plan(
            """[{"id":"f","name":"Not dir","storagePath":"/storage/emulated/0/note.txt"}]""",
            files = setOf("/storage/emulated/0/note.txt"),
        )
        assertEquals(MpcRowStatus.UNUSABLE, file.single().status)

        val tier = plan("""[{"id":"x","name":"X","storagePath":"","storageTier":"CUSTOM"}]""")
        assertEquals(MpcRowStatus.UNUSABLE, tier.single().status)
    }

    @Test
    fun ignoresCameraFieldsAndAcceptsOneObject() {
        val rows = plan(
            """
            {"id":"only","name":"Only","aspectRatio":1,"videoResolution":{"width":1920,"height":1080},"storagePath":"","storageTier":"VISIBLE","lastUsed":1730000000000}
            """.trimIndent(),
        )
        assertEquals("only", rows.single().profileId)
        assertEquals("/storage/emulated/0/Pictures/MultiProfileCam/Only", rows.single().preview)
    }

    @Test
    fun rejectsMalformedJson() {
        val error = runCatching { plan("{") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val shape = runCatching { plan("{\"other\":true}") }.exceptionOrNull()
        assertTrue(shape is IllegalArgumentException)
        val empty = plan("[]")
        assertTrue(empty.isEmpty())
    }

    private fun plan(
        json: String,
        mounted: List<String> = emptyList(),
        readable: Set<String> = emptySet(),
        files: Set<String> = emptySet(),
        granted: Set<String> = emptySet(),
    ): List<MpcPlanRow> {
        return MpcImport.plan(
            json = json,
            externalRoot = ROOT,
            mountedLocations = mounted,
            probe = { path ->
                when {
                    path in readable -> FsProbe.DIRECTORY
                    path in files -> FsProbe.NOT_DIRECTORY
                    else -> FsProbe.MISSING
                }
            },
            safGranted = { uri -> granted.any { MpcImport.samePlace(it, uri, ROOT) } },
        )
    }

    private companion object {
        const val ROOT = "/storage/emulated/0"
        val SAMPLE = """
            [
              {
                "id": "work1",
                "name": "Worksite 1",
                "aspectRatio": 1,
                "videoResolution": {"width": 1920, "height": 1080},
                "watermarkText": "Site",
                "watermarkEnabled": true,
                "includeDate": true,
                "enableGps": true,
                "storagePath": "",
                "storageTier": "VISIBLE",
                "showToasts": true,
                "lastUsed": 1730000000000
              },
              {
                "id": "family",
                "name": "Family",
                "aspectRatio": 1,
                "videoResolution": {"width": 1920, "height": 1080},
                "watermarkText": "",
                "watermarkEnabled": false,
                "includeDate": true,
                "enableGps": true,
                "storagePath": "content://com.android.externalstorage.documents/tree/primary%3APictures%2FFamily",
                "storageTier": "PRIVATE",
                "showToasts": true,
                "lastUsed": 1730000001000
              },
              {
                "id": "legacy",
                "name": "Legacy Path",
                "aspectRatio": 1,
                "videoResolution": {"width": 1280, "height": 720},
                "watermarkText": "",
                "watermarkEnabled": false,
                "includeDate": true,
                "enableGps": true,
                "storagePath": "/storage/emulated/0/DCIM/MultiProfileCam/Legacy",
                "storageTier": "AUTO_SYNC",
                "showToasts": true,
                "lastUsed": 1730000002000
              }
            ]
        """.trimIndent()
    }
}
