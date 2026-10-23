package app.openshelf.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ZipPhase {
    data object Idle : ZipPhase
    data class Running(val done: Int, val total: Int, val name: String) : ZipPhase
    data class Done(val label: String) : ZipPhase
    data class Failed(val message: String) : ZipPhase
}

data class ZipRequest(
    val mountId: String,
    val relative: String,
    val names: List<String>,
    val label: String,
)

object ZipHub {
    private val state = MutableStateFlow<ZipPhase>(ZipPhase.Idle)
    val phase: StateFlow<ZipPhase> = state.asStateFlow()
    private val gate = Any()
    private var request: ZipRequest? = null

    fun current(): ZipRequest? = synchronized(gate) { request }

    fun begin(next: ZipRequest): Boolean = synchronized(gate) {
        if (state.value is ZipPhase.Running) {
            return false
        }
        request = next
        state.value = ZipPhase.Running(0, 0, next.label)
        true
    }

    fun update(done: Int, total: Int, name: String) {
        if (state.value is ZipPhase.Running) {
            state.value = ZipPhase.Running(done, total, name)
        }
    }

    fun succeed(label: String) = synchronized(gate) {
        request = null
        state.value = ZipPhase.Done(label)
    }

    fun fail(message: String) = synchronized(gate) {
        request = null
        state.value = ZipPhase.Failed(message)
    }

    fun acknowledge() {
        if (state.value !is ZipPhase.Running) {
            state.value = ZipPhase.Idle
        }
    }
}
