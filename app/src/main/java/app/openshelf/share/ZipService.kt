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
import app.openshelf.core.store.StoreException
import app.openshelf.core.zip.ZipEntryPlan
import app.openshelf.core.zip.ZipPack
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ZipService : Service() {
    private val working = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.zip_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = ZipHub.current()
        if (request == null || !working.compareAndSet(false, true)) {
            if (request == null && ZipHub.phase.value is ZipPhase.Running) {
                ZipHub.fail(getString(R.string.zip_failed))
            }
            if (!working.get()) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        try {
            goForeground(getString(R.string.zip_preparing), 0, 0)
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.zip_failed)
            ZipHub.fail(message)
            working.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        thread(name = "openshelf-zip") {
            try {
                val saved = pack(request)
                ZipHub.succeed(saved)
                notifyDone(getString(R.string.zip_saved, saved))
            } catch (error: StoreException) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.zip_failed)
                ZipHub.fail(message)
                notifyDone(message)
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.zip_failed)
                ZipHub.fail(message)
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

    private fun pack(request: ZipRequest): String {
        val store = AndroidFileStore(this) { (application as OpenShelfApp).prefs.currentMounts() }
        val plans = if (request.names.isEmpty()) {
            ZipPack.planTree(store, request.mountId, request.relative)
        } else {
            val paths = request.names.map { name ->
                if (request.relative.isEmpty()) name else "${request.relative}/$name"
            }
            ZipPack.planSelection(store, request.mountId, paths)
        }
        val fileName = uniqueName(request.label)
        if (Build.VERSION.SDK_INT >= 29) {
            writePublicDownload(store, plans, fileName)
        } else {
            writeLegacyDownload(store, plans, fileName)
        }
        return fileName
    }

    @RequiresApi(29)
    private fun writePublicDownload(
        store: AndroidFileStore,
        plans: List<ZipEntryPlan>,
        fileName: String,
    ) {
        val resolver = contentResolver
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        values.put(MediaStore.Downloads.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(getString(R.string.zip_failed))
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException(getString(R.string.zip_failed))
            writePlans(store, plans, stream)
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun writeLegacyDownload(
        store: AndroidFileStore,
        plans: List<ZipEntryPlan>,
        fileName: String,
    ) {
        val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        directory.mkdirs()
        val file = File(directory, fileName)
        try {
            file.outputStream().use { stream -> writePlans(store, plans, stream) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun writePlans(
        store: AndroidFileStore,
        plans: List<ZipEntryPlan>,
        stream: OutputStream,
    ) {
        stream.use { output ->
            ZipPack.write(store, plans, output) { done, total, name ->
                ZipHub.update(done, total, name)
                val text = getString(R.string.zip_running, done, total)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(text, done, total, ongoing = true))
            }
        }
    }

    private fun uniqueName(label: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = ZipPack.downloadName(label).removeSuffix(".zip")
        return "quick-temp-open-server-$stamp-$base.zip"
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
            7,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shelf)
            .setContentTitle(getString(R.string.zip_notification_title))
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
        private const val CHANNEL = "openshelf_zip"
        private const val NOTIFICATION_ID = 43
        private const val DONE_ID = 44
    }
}
