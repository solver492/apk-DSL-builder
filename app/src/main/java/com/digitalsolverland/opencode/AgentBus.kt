package com.digitalsolverland.opencode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class AgentStatus { STOPPED, STARTING, RUNNING, ERROR }

/** Bus partagé Service <-> UI (logs + statut). */
object AgentBus {
    val logs = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow(AgentStatus.STOPPED)

    private const val MAX_LINES = 1500

    fun log(line: String) {
        logs.update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear() {
        logs.value = emptyList()
    }
}
