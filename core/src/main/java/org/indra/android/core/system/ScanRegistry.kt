package org.indra.android.core.system

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks active scan tasks so the bundle system knows when it's safe to swap.
 */
@Singleton
class ScanRegistry @Inject constructor() {
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount

    fun startScan() { _activeCount.value += 1 }
    fun endScan() { _activeCount.value -= 1 }
    fun hasActiveScan(): Boolean = _activeCount.value > 0
}
