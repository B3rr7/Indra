package org.indra.android.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Where an engine install is, as one state every screen can render. */
sealed interface EngineInstallState {
    /** Nothing requested yet, or the last run was dismissed. */
    data object Idle : EngineInstallState

    /** [label] says what is being installed; [progress] is null until the installer reports one. */
    data class Installing(
        val label: String,
        val progress: Float? = null,
    ) : EngineInstallState

    /** The install finished; a screen should re-check its capability now. */
    data object Done : EngineInstallState

    /** The install did not finish; [message] is what the daemon or `apk` said. */
    data class Failed(
        val message: String,
    ) : EngineInstallState
}

/**
 * Single-flight + state for one engine install.
 *
 * Two screens can need the same fix at the same time (Handshakes and Terminal both want the
 * rootfs, Wifite and Packet Capture both want `iw`), and two concurrent `apk add` runs would
 * fight over the same rootfs lock. The second caller therefore **joins**: it does not start a
 * second job, it observes the same [state] flow. `run` returns whether this call did the work,
 * which is what the test pins.
 *
 * Pure apart from the coroutines it is given, so it is unit-tested with a lambda that blocks on a
 * deferred instead of with a device.
 */
class EngineInstallStateMachine {
    private val _state = MutableStateFlow<EngineInstallState>(EngineInstallState.Idle)
    val state: StateFlow<EngineInstallState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)

    /** True when the last run finished and a screen has not consumed the result yet. */
    fun reset() {
        if (!running.get()) _state.value = EngineInstallState.Idle
    }

    /**
     * Runs [work] unless an install is already in flight.
     *
     * [work] reports progress through the lambda it is handed; a failure message from the
     * [Result] is the state's `Failed.message` (with a fallback, because `Throwable.message` is
     * null for some exceptions and an empty error line is worse than a generic one).
     *
     * @return true when this call performed the work, false when it joined an existing run.
     */
    suspend fun run(
        label: String,
        work: suspend (report: (Float?) -> Unit) -> Result<Unit>,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            _state.value = EngineInstallState.Installing(label)
            val report: (Float?) -> Unit = { progress -> _state.value = EngineInstallState.Installing(label, progress) }
            work(report).fold(
                onSuccess = { _state.value = EngineInstallState.Done },
                onFailure = { _state.value = EngineInstallState.Failed(it.message ?: "$label failed.") },
            )
            true
        } finally {
            running.set(false)
        }
    }
}
