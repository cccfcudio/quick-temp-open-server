package app.openshelf.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import app.openshelf.MainActivity
import app.openshelf.OpenShelfApp
import app.openshelf.R
import app.openshelf.core.http.mimeFor
import app.openshelf.core.store.StoreException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DownloadService : Service() {
    private val working = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.download_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = DownloadHub.current()
        if (request == null || !working.compareAndSet(false, true)) {
            if (request == null && DownloadHub.phase.value is DownloadPhase.Running) {
                DownloadHub.fail(getString(R.string.download_failed))
            }
            if (!working.get()) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        try {
            goForeground(getString(R.string.download_preparing), 0, request.names.size)
        } catch (error: Exception) {
            DownloadHub.fail(blankToFailed(error.message))
            working.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        thread(name = "openshelf-download") {
            try {
                val saved = copyAll(request)
                DownloadHub.succeed(saved)
                notifyDone(saved)
            } catch (error: StoreException) {
                val message = blankToFailed(error.message)
                DownloadHub.fail(message)
                notifyDone(message)
            } catch (error: Exception) {
                val message = blankToFailed(error.message)
                DownloadHub.fail(message)
                notifyDone(message)
            } finally {
                working.set(false)
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (error: Exception) {
                    // The service may already have left the foreground.
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun copyAll(request: DownloadRequest): String {
        val store = AndroidFileStore(this) { (application as OpenShelfApp).prefs.currentMounts() }
        val files = ArrayList<String>()
        var folders = 0
        for (name in request.names) {
            val relative = childPath(request.relative, name)
            val stat = store.stat(request.mountId, relative)
            if (stat == null) {
                continue
            }
            if (stat.directory) {
                folders += 1
            } else {
                files.add(name)
            }
        }
        if (files.isEmpty()) {
            val message = if (folders > 0) {
                getString(R.string.download_folder_hint)
            } else {
                getString(R.string.download_failed)
            }
            throw StoreException(400, message)
        }
        val saved = ArrayList<String>()
        var failure: String? = null
        files.forEachIndexed { index, name ->
            DownloadHub.update(index + 1, files.size, name)
            val text = getString(R.string.download_running, index + 1, files.size)
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text, index + 1, files.size, ongoing = true))
            try {
                val relative = childPath(request.relative, name)
                val fileName = uniqueName(name, index)
                if (Build.VERSION.SDK_INT >= 29) {
                    writePublic(store, request.mountId, relative, fileName)
                } else {
                    writeLegacy(store, request.mountId, relative, fileName)
                }
                saved.add(fileName)
            } catch (error: Exception) {
                failure = blankToFailed(error.message)
            }
        }
        if (saved.isEmpty()) {
            throw IOException(failure ?: getString(R.string.download_failed))
        }
        val summary = if (saved.size == 1) {
            getString(R.string.download_saved, saved[0])
        } else {
            getString(R.string.download_saved_count, saved.size)
        }
        if (folders > 0 || failure != null) {
            return summary + " " + getString(R.string.download_partial)
        }
        return summary
    }

    @RequiresApi(29)
    private fun writePublic(store: AndroidFileStore, mountId: String, relative: String, fileName: String) {
        val resolver = contentResolver
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        values.put(MediaStore.Downloads.MIME_TYPE, mimeFor(fileName).substringBefore(';'))
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Quick Temp Open Server")
        values.put(MediaStore.Downloads.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(getString(R.string.download_failed))
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException(getString(R.string.download_failed))
            stream.use { output -> copyFile(store, mountId, relative, output) }
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun writeLegacy(store: AndroidFileStore, mountId: String, relative: String, fileName: String) {
        val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        directory.mkdirs()
        val file = File(directory, fileName)
        try {
            file.outputStream().use { output -> copyFile(store, mountId, relative, output) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun copyFile(store: AndroidFileStore, mountId: String, relative: String, output: OutputStream) {
        store.openRead(mountId, relative).use { input ->
            val buffer = ByteArray(8192)
            var zeros = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    return
                }
                if (count == 0) {
                    if (++zeros > 8) {
                        throw IOException(getString(R.string.download_failed))
                    }
                    continue
                }
                zeros = 0
                output.write(buffer, 0, count)
            }
        }
    }

    private fun childPath(directory: String, name: String): String {
        return if (directory.isEmpty()) name else "$directory/$name"
    }

    private fun uniqueName(name: String, index: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = buildString(name.length) {
            for (ch in name) {
                append(if (ch == '/' || ch == '\\' || ch.code < 32) '_' else ch)
            }
        }.trim().take(80).ifBlank { "file" }
        return "quick-temp-open-server-$stamp-$index-$safe"
    }

    private fun blankToFailed(message: String?): String {
        return message?.takeIf { it.isNotBlank() } ?: getString(R.string.download_failed)
    }

    private fun goForeground(text: String, done: Int, total: Int) {
        val notification = buildNotification(text, done, total, ongoing = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(DONE_ID, buildNotification(text, 0, 0, ongoing = false))
    }

    private fun buildNotification(text: String, done: Int, total: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            9,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shelf)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing && total > 0) {
            builder.setProgress(total, done, false)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "openshelf_download"
        private const val NOTIFICATION_ID = 45
        private const val DONE_ID = 46
    }
}
