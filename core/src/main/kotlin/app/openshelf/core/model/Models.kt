package app.openshelf.core.model

data class MountInfo(
    val id: String,
    val displayName: String,
)

data class FileEntry(
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modifiedEpochMs: Long,
)

enum class StoredMountKind {
    SAF_TREE,
    APP_DIRECTORY,
}

data class StoredMount(
    val id: String,
    val displayName: String,
    val location: String,
    val kind: StoredMountKind,
)

enum class DriverKind {
    LOCAL_FOLDER,
    S3,
    WEBDAV_REMOTE,
    ALIYUN_DRIVE,
    ONEDRIVE,
}

object ShareDefaults {
    const val PORT: Int = 28765
    const val MAX_UPLOAD_BYTES: Long = 2L * 1024L * 1024L * 1024L
    const val USERNAME: String = "quick"
    const val SESSION_COOKIE: String = "openshelf_session"
}
