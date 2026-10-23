package app.openshelf.core.driver

import app.openshelf.core.model.DriverKind

object DriverCatalog {
    data class Upcoming(
        val kind: DriverKind,
        val title: String,
    )

    val upcoming: List<Upcoming> = listOf(
        Upcoming(DriverKind.S3, "S3"),
        Upcoming(DriverKind.WEBDAV_REMOTE, "遠端 WebDAV"),
        Upcoming(DriverKind.ALIYUN_DRIVE, "阿里雲盤"),
        Upcoming(DriverKind.ONEDRIVE, "OneDrive"),
    )

    fun unsupported(kind: DriverKind): Nothing = when (kind) {
        DriverKind.LOCAL_FOLDER -> error("本機資料夾由掛載處理")
        DriverKind.S3,
        DriverKind.WEBDAV_REMOTE,
        DriverKind.ALIYUN_DRIVE,
        DriverKind.ONEDRIVE,
        -> throw NotImplementedError("TODO: ${kind.name} driver is not implemented in v1")
    }
}
