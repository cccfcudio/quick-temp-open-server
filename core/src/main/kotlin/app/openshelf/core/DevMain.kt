package app.openshelf.core

import app.openshelf.core.http.JvmJpegPreview
import app.openshelf.core.http.ShareHttpServer
import app.openshelf.core.store.DirectoryFileStore
import java.io.File

fun main(args: Array<String>) {
    val directory = File(args.getOrElse(0) { "share-data" }).apply { mkdirs() }
    val port = args.getOrElse(1) { "28765" }.toInt()
    val password = args.getOrElse(2) { "" }
    val store = DirectoryFileStore(
        listOf(DirectoryFileStore.Root("share", "分享夾", directory)),
    )
    val server = ShareHttpServer(
        store = store,
        password = { password },
        requestedPort = port,
        log = { line -> println(line) },
        preview = JvmJpegPreview(store),
    )
    server.start()
    println("quick-temp-open-server http://127.0.0.1:${server.port}/  webdav http://127.0.0.1:${server.port}/dav/share/")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
