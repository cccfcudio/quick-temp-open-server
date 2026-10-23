package app.openshelf.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.openshelf.MainActivity
import app.openshelf.OpenShelfApp
import app.openshelf.R

class ShareService : Service() {
    private var alive: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        ShareHub.setListener { state ->
            if (alive) {
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            goForeground(getString(R.string.notification_stopping))
            ShareHub.stopAll()
            alive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(getString(R.string.notification_starting))
        when (action) {
            ACTION_START_TUNNEL -> ShareHub.startTunnel()
            ACTION_STOP_TUNNEL -> ShareHub.stopTunnel()
            else -> {
                ShareHub.ensureServer()
                if ((application as OpenShelfApp).prefs.tunnelWanted()) {
                    ShareHub.startTunnel()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        ShareHub.setListener(null)
        ShareHub.releaseForRestart()
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: ShareUiState) {
        val text = when {
            state.publicUrl != null -> state.publicUrl
            state.phase == ServerPhase.RUNNING && state.lanHosts.isNotEmpty() ->
                "http://${state.lanHosts.first()}:${state.port}/"
            state.phase == ServerPhase.RUNNING -> getString(R.string.notification_running)
            else -> getString(R.string.notification_starting)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_shelf)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.openshelf.START"
        const val ACTION_STOP = "app.openshelf.STOP"
        const val ACTION_START_TUNNEL = "app.openshelf.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "app.openshelf.STOP_TUNNEL"
        private const val CHANNEL = "openshelf_share"
        private const val NOTIFICATION_ID = 42
    }
}
