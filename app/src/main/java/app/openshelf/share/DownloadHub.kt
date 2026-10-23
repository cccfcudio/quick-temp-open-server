package app.openshelf.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DownloadPhase {
    data object Idle : DownloadPhase
    data class Running(val done: Int, val total: Int, val name: String) : DownloadPhase
    data class Done(val message: String) : DownloadPhase
    data class Failed(val message: String) : DownloadPhase
}

data class DownloadRequest(
    val mountId: String,
    val relative: String,
    val names: List<String>,
)

object DownloadHub {
    private val state = MutableStateFlow<DownloadPhase>(DownloadPhase.Idle)
    val phase: StateFlow<DownloadPhase> = state.asStateFlow()
    private val gate = Any()
    private var request: DownloadRequest? = null

    fun current(): DownloadRequest? = synchronized(gate) { request }

    fun begin(next: DownloadRequest): Boolean = synchronized(gate) {
        if (state.value is DownloadPhase.Running) {
            return false
        }
        request = next
        val first = next.names.firstOrNull().orEmpty()
        state.value = DownloadPhase.Running(0, next.names.size, first)
        true
    }

    fun update(done: Int, total: Int, name: String) {
        if (state.value is DownloadPhase.Running) {
            state.value = DownloadPhase.Running(done, total, name)
        }
    }

    fun succeed(message: String) = synchronized(gate) {
        request = null
        state.value = DownloadPhase.Done(message)
    }

    fun fail(message: String) = synchronized(gate) {
        request = null
        state.value = DownloadPhase.Failed(message)
    }

    fun acknowledge() {
        if (state.value !is DownloadPhase.Running) {
            state.value = DownloadPhase.Idle
        }
    }
}
