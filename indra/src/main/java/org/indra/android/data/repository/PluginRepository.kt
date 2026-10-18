package org.indra.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.indra.android.data.model.Exploit
import org.indra.android.data.model.Session
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRepository @Inject constructor() {

    private val _exploits = MutableStateFlow<List<Exploit>>(emptyList())
    val exploits: Flow<List<Exploit>> = _exploits.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: Flow<List<Session>> = _sessions.asStateFlow()

    fun updateExploits(exploits: List<Exploit>) {
        _exploits.value = exploits
    }

    fun updateSessions(sessions: List<Session>) {
        _sessions.value = sessions
    }
}
