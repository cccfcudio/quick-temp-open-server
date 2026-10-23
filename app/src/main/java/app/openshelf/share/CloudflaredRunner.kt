package app.openshelf.share

import android.content.Context
import app.openshelf.core.tunnel.CloudflaredCommand
import app.openshelf.core.tunnel.TunnelLogParser
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudflaredRunner(private val context: Context) {
    private var process: Process? = null

    fun start(
        originUrl: String,
        onLog: (String) -> Unit,
        onUrl: (String) -> Unit,
        onExit: (Int) -> Unit,
    ) {
        val binary = binaryFile(context) ?: throw IOException("cloudflared missing")
        val home = File(context.filesDir, "cloudflared").apply { mkdirs() }
        val command = ArrayList<String>()
        command.add(binary.absolutePath)
        command.addAll(CloudflaredCommand.arguments(originUrl))
        val started = ProcessBuilder(command)
            .directory(home)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
        process = started
        Thread {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        onLog(line)
                        val url = TunnelLogParser.findUrl(line)
                        if (url != null) {
                            onUrl(url)
                        }
                    }
                }
            } catch (error: IOException) {
                onLog(error.message ?: "讀取隧道輸出失敗")
            } finally {
                val code = try {
                    started.waitFor()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                onExit(code)
            }
        }.apply {
            isDaemon = true
            name = "openshelf-cloudflared"
            start()
        }
    }

    fun stop() {
        val current = process ?: return
        process = null
        current.destroy()
        if (!current.waitFor(2, TimeUnit.SECONDS)) {
            current.destroyForcibly()
        }
    }

    companion object {
        fun binaryFile(context: Context): File? {
            val file = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
            return if (file.isFile) file else null
        }
    }
}
