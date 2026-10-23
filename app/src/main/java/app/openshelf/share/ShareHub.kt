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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
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
    data class Unreachable(val message: String) : TunnelPhase
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
    /** True once Try Cloudflare was started for this server session. */
    private var tunnelOwnsSession = false
    private var healthGeneration = 0
    private val healthExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openshelf-tunnel-health").apply { isDaemon = true }
    }

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
                TunnelPhase.Starting, TunnelPhase.Up, is TunnelPhase.Unreachable -> return
                TunnelPhase.Idle, TunnelPhase.BinaryMissing, is TunnelPhase.Failed -> Unit
            }
            if (CloudflaredRunner.binaryFile(app) == null) {
                publish(stateFlow.value.copy(tunnelPhase = TunnelPhase.BinaryMissing, publicUrl = null))
                return
            }
            stopTunnelRequested = false
            tunnelOwnsSession = true
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
        val bound = synchronized(this) { tunnelOwnsSession }
        if (bound) {
            // Session was started with Cloudflare → stopping CF also stops the local server.
            release(clearWanted = true, error = null)
            return
        }
        val current = synchronized(this) {
            stopTunnelRequested = true
            healthGeneration += 1
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

    private fun release(clearWanted: Boolean, error: String? = null) {
        val current = synchronized(this) {
            stopTunnelRequested = true
            healthGeneration += 1
            tunnelOwnsSession = false
            if (clearWanted) {
                prefs.setTunnelWanted(false)
            }
            val running = runner
            runner = null
            server?.stop()
            server = null
            releaseLocks()
            publish(ShareUiState(error = error))
            running
        }
        current?.stop()
    }

    /** Cloudflare died or became unreachable after a CF-bound session → stop everything. */
    private fun stopBoundSession(reason: String) {
        val bound = synchronized(this) { tunnelOwnsSession }
        if (bound) {
            release(
                clearWanted = true,
                error = app.getString(R.string.tunnel_stopped_all, reason),
            )
        } else {
            synchronized(this) {
                healthGeneration += 1
                runner = null
                publish(
                    stateFlow.value.copy(
                        tunnelPhase = TunnelPhase.Failed(reason),
                    ),
                )
            }
        }
    }

    private fun ensureServerUnlocked(): String? {
        if (server != null) {
            return null
        }
        val http = ShareHttpServer(
            store = AndroidFileStore(app) { prefs.currentMounts() },
            password = { prefs.currentPassword() },
            username = { prefs.currentUsername() },
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
            scheduleHealthCheckLocked(delayMs = HEALTH_INTERVAL_MS)
        }
    }

    private fun onExit(code: Int) {
        synchronized(this) {
            if (stopTunnelRequested) {
                return
            }
        }
        stopBoundSession(app.getString(R.string.tunnel_exit, code))
    }

    private fun scheduleHealthCheckLocked(delayMs: Long) {
        val generation = healthGeneration
        main.postDelayed({ runHealthCheck(generation) }, delayMs)
    }

    private fun runHealthCheck(generation: Int) {
        val snapshot = synchronized(this) {
            if (generation != healthGeneration || stopTunnelRequested) {
                return
            }
            val phase = stateFlow.value.tunnelPhase
            if (phase !is TunnelPhase.Up && phase !is TunnelPhase.Unreachable) {
                return
            }
            val url = stateFlow.value.publicUrl ?: return
            val alive = runner?.isAlive() == true
            Triple(url, alive, generation)
        }
        healthExecutor.execute {
            val (url, processAlive, gen) = snapshot
            val reason = when {
                !processAlive -> app.getString(R.string.tunnel_health_process_dead)
                !probePublicUrl(url) -> app.getString(R.string.tunnel_health_http_fail)
                else -> null
            }
            if (reason != null) {
                val stillActive = synchronized(this) {
                    gen == healthGeneration &&
                        !stopTunnelRequested &&
                        (stateFlow.value.tunnelPhase is TunnelPhase.Up ||
                            stateFlow.value.tunnelPhase is TunnelPhase.Unreachable)
                }
                if (stillActive) {
                    stopBoundSession(reason)
                }
                return@execute
            }
            synchronized(this) {
                if (gen != healthGeneration || stopTunnelRequested) {
                    return@synchronized
                }
                val phase = stateFlow.value.tunnelPhase
                if (phase !is TunnelPhase.Up && phase !is TunnelPhase.Unreachable) {
                    return@synchronized
                }
                if (phase is TunnelPhase.Unreachable) {
                    publish(
                        stateFlow.value.copy(
                            tunnelPhase = TunnelPhase.Up,
                            error = null,
                        ),
                    )
                }
                scheduleHealthCheckLocked(HEALTH_INTERVAL_MS)
            }
        }
    }

    private fun probePublicUrl(publicUrl: String): Boolean {
        val base = publicUrl.trimEnd('/')
        val targets = listOf("$base/healthz", base)
        for (target in targets) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(target).openConnection() as HttpURLConnection).apply {
                    connectTimeout = HEALTH_TIMEOUT_MS
                    readTimeout = HEALTH_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    useCaches = false
                }
                val code = connection.responseCode
                // Any HTTP response means the Quick Tunnel edge still reaches us
                // (401/302/404 still prove reachability; only I/O failure means dead).
                if (code in 100..599) {
                    return true
                }
            } catch (error: Exception) {
                android.util.Log.i(TAG, "tunnel probe fail $target: ${error.message}")
            } finally {
                connection?.disconnect()
            }
        }
        return false
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
    private const val HEALTH_INTERVAL_MS = 60_000L
    private const val HEALTH_TIMEOUT_MS = 12_000
}
