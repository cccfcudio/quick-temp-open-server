package app.openshelf.share

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import app.openshelf.OpenShelfApp
import app.openshelf.R
import app.openshelf.core.auth.PasswordGenerator
import app.openshelf.core.http.ShareHttpServer
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ServerPhase {
    STOPPED,
    RUNNING,
}

sealed interface TunnelPhase {
    data object Idle : TunnelPhase
    data object Starting : TunnelPhase
    data object Up : TunnelPhase
    data object BinaryMissing : TunnelPhase
    data class Failed(val message: String) : TunnelPhase
}

data class ShareUiState(
    val phase: ServerPhase = ServerPhase.STOPPED,
    val port: Int = 0,
    val lanHosts: List<String> = emptyList(),
    val error: String? = null,
    val tunnelPhase: TunnelPhase = TunnelPhase.Idle,
    val publicUrl: String? = null,
    val tunnelLog: String = "",
)

object ShareHub {
    private lateinit var app: Context
    private lateinit var prefs: ShelfPrefs
    private val main = Handler(Looper.getMainLooper())
    private val stateFlow = MutableStateFlow(ShareUiState())
    private var server: ShareHttpServer? = null
    private var runner: CloudflaredRunner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var listener: ((ShareUiState) -> Unit)? = null
    private var stopTunnelRequested = false

    val state: StateFlow<ShareUiState> = stateFlow.asStateFlow()

    fun attach(application: OpenShelfApp) {
        app = application.applicationContext
        prefs = application.prefs
    }

    fun setListener(callback: ((ShareUiState) -> Unit)?) {
        listener = callback
    }

    fun ensureServer(): String? = synchronized(this) { ensureServerUnlocked() }

    fun refreshLan() {
        synchronized(this) {
            if (server == null) {
                return
            }
            publish(stateFlow.value.copy(lanHosts = NetInfo.lanHosts()))
        }
    }

    fun startTunnel() {
        val origin = synchronized(this) {
            if (prefs.currentPassword().isEmpty()) {
                prefs.setPassword(PasswordGenerator.generate())
            }
            val failure = ensureServerUnlocked()
            if (failure != null) {
                return
            }
            when (stateFlow.value.tunnelPhase) {
                TunnelPhase.Starting, TunnelPhase.Up -> return
                TunnelPhase.Idle, TunnelPhase.BinaryMissing, is TunnelPhase.Failed -> Unit
            }
            if (CloudflaredRunner.binaryFile(app) == null) {
                publish(stateFlow.value.copy(tunnelPhase = TunnelPhase.BinaryMissing, publicUrl = null))
                return
            }
            stopTunnelRequested = false
            prefs.setTunnelWanted(true)
            publish(
                stateFlow.value.copy(
                    tunnelPhase = TunnelPhase.Starting,
                    publicUrl = null,
                    tunnelLog = "",
                    error = null,
                ),
            )
            "http://127.0.0.1:${server?.port ?: return}"
        }
        val cloudflared = CloudflaredRunner(app)
        synchronized(this) {
            runner = cloudflared
        }
        try {
            cloudflared.start(origin, ::onLog, ::onUrl, ::onExit)
        } catch (error: IOException) {
            synchronized(this) {
                runner = null
                publish(
                    stateFlow.value.copy(
                        tunnelPhase = TunnelPhase.Failed(error.message ?: app.getString(R.string.tunnel_failed)),
                    ),
                )
            }
        }
    }

    fun stopTunnel() {
        val current = synchronized(this) {
            stopTunnelRequested = true
            prefs.setTunnelWanted(false)
            val running = runner
            runner = null
            publish(stateFlow.value.copy(tunnelPhase = TunnelPhase.Idle, publicUrl = null, tunnelLog = ""))
            running
        }
        current?.stop()
    }

    fun stopAll() {
        release(clearWanted = true)
    }

    fun releaseForRestart() {
        release(clearWanted = false)
    }

    private fun release(clearWanted: Boolean) {
        val current = synchronized(this) {
            stopTunnelRequested = true
            if (clearWanted) {
                prefs.setTunnelWanted(false)
            }
            val running = runner
            runner = null
            server?.stop()
            server = null
            releaseLocks()
            publish(ShareUiState())
            running
        }
        current?.stop()
    }

    private fun ensureServerUnlocked(): String? {
        if (server != null) {
            return null
        }
        val http = ShareHttpServer(
            store = AndroidFileStore(app) { prefs.currentMounts() },
            password = { prefs.currentPassword() },
            requestedPort = prefs.currentPort(),
            log = { line -> android.util.Log.i(TAG, line) },
            authFailDelayMs = 200L,
            preview = AndroidJpegPreview.get(app),
        )
        try {
            http.start()
        } catch (error: IOException) {
            val message = app.getString(R.string.error_bind, prefs.currentPort())
            publish(ShareUiState(error = message))
            return message
        }
        server = http
        acquireLocks()
        publish(
            stateFlow.value.copy(
                phase = ServerPhase.RUNNING,
                port = http.port,
                lanHosts = NetInfo.lanHosts(),
                error = null,
            ),
        )
        return null
    }

    private fun onLog(line: String) {
        synchronized(this) {
            if (stopTunnelRequested) {
                return
            }
            val url = stateFlow.value.publicUrl
            val log = appendLog(stateFlow.value.tunnelLog, line)
            publish(stateFlow.value.copy(tunnelLog = log, publicUrl = url))
        }
    }

    private fun onUrl(url: String) {
        synchronized(this) {
            if (stopTunnelRequested) {
                return
            }
            publish(stateFlow.value.copy(tunnelPhase = TunnelPhase.Up, publicUrl = url, error = null))
        }
    }

    private fun onExit(code: Int) {
        synchronized(this) {
            if (stopTunnelRequested) {
                return
            }
            runner = null
            publish(
                stateFlow.value.copy(
                    tunnelPhase = TunnelPhase.Failed(app.getString(R.string.tunnel_exit, code)),
                    publicUrl = null,
                ),
            )
        }
    }

    private fun publish(next: ShareUiState) {
        stateFlow.value = next
        val callback = listener
        if (callback != null) {
            main.post { callback(next) }
        }
    }

    private fun appendLog(current: String, line: String): String {
        val next = if (current.isEmpty()) line else "$current\n$line"
        return if (next.length > 4000) next.takeLast(4000) else next
    }

    private fun acquireLocks() {
        try {
            acquireLocksUnsafe()
        } catch (error: RuntimeException) {
            android.util.Log.w(TAG, "wake lock", error)
        }
    }

    private fun acquireLocksUnsafe() {
        if (wakeLock == null) {
            val power = app.getSystemService(PowerManager::class.java)
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openshelf:share")
            lock.setReferenceCounted(false)
            lock.acquire(12L * 60L * 60L * 1000L)
            wakeLock = lock
        }
        if (wifiLock == null) {
            val wifi = app.getSystemService(WifiManager::class.java) ?: return
            val lock = if (android.os.Build.VERSION.SDK_INT >= 29) {
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "openshelf")
            } else {
                @Suppress("DEPRECATION")
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "openshelf")
            }
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
        wifiLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wifiLock = null
    }

    private const val TAG = "QuickTemp"
}
