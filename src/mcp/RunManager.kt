package mcp

import java.util.concurrent.ConcurrentHashMap

class RunManager {
    // Global: all runs, accessible by ID from any session
    private val runs = ConcurrentHashMap<String, ActiveRun>()

    // Per-session: current run pointer (run ID)
    private val currentRunBySession = ConcurrentHashMap<String, String>()

    // Legacy: global current run for backward compatibility
    @Volatile
    var currentRun: ActiveRun? = null
        private set

    // Session-aware methods

    fun startRun(sessionId: String): ActiveRun {
        deleteAllRuns(sessionId)
        val run = ActiveRun(sessionId)
        runs[run.id] = run
        currentRunBySession[sessionId] = run.id
        currentRun = run  // Legacy compatibility
        return run
    }

    fun startConcurrentRun(sessionId: String): ActiveRun {
        val run = ActiveRun(sessionId)
        runs[run.id] = run
        currentRunBySession[sessionId] = run.id
        currentRun = run  // Legacy compatibility
        return run
    }

    fun getRun(sessionId: String, runId: String?): ActiveRun? {
        return if (runId == null || runId == "current") {
            currentRunBySession[sessionId]?.let { runs[it] }
        } else {
            runs[runId]  // Global access by explicit ID
        }
    }

    fun getAllRuns(sessionId: String): List<ActiveRun> {
        return runs.values.filter { it.ownerSessionId == sessionId }
    }

    fun stopRun(sessionId: String, runId: String?): String {
        val run = getRun(sessionId, runId) ?: return if (runId == null) "no_current_run" else "not_found"
        run.handler.abort()
        return "stopped"
    }

    fun deleteRun(sessionId: String, runId: String?): String {
        val run = getRun(sessionId, runId) ?: return if (runId == null) "no_current_run" else "not_found"
        run.handler.abort()
        runs.remove(run.id)
        // Clear from owner's current if it was their current
        if (currentRunBySession[run.ownerSessionId] == run.id) {
            currentRunBySession.remove(run.ownerSessionId)
        }
        if (currentRun?.id == run.id) {
            currentRun = null
        }
        return "deleted"
    }

    fun deleteAllRuns(sessionId: String): Int {
        val sessionRuns = runs.values.filter { it.ownerSessionId == sessionId }
        val count = sessionRuns.size
        sessionRuns.forEach { run ->
            run.handler.abort()
            runs.remove(run.id)
        }
        currentRunBySession.remove(sessionId)
        if (currentRun?.ownerSessionId == sessionId) {
            currentRun = null
        }
        return count
    }

    // Legacy session-less methods for backward compatibility

    fun startRun(): ActiveRun {
        deleteAllRuns()
        val run = ActiveRun("legacy")
        runs[run.id] = run
        currentRun = run
        return run
    }

    fun startConcurrentRun(): ActiveRun {
        val run = ActiveRun("legacy")
        runs[run.id] = run
        currentRun = run
        return run
    }

    fun getRun(runId: String?): ActiveRun? {
        return if (runId == null || runId == "current") currentRun else runs[runId]
    }

    fun getAllRuns(): List<ActiveRun> {
        return runs.values.toList()
    }

    fun stopRun(runId: String?): String {
        val run = getRun(runId) ?: return if (runId == null) "no_current_run" else "not_found"
        run.handler.abort()
        return "stopped"
    }

    fun deleteRun(runId: String?): String {
        val run = getRun(runId) ?: return if (runId == null) "no_current_run" else "not_found"
        run.handler.abort()
        runs.remove(run.id)
        if (currentRun?.id == run.id) {
            currentRun = null
        }
        return "deleted"
    }

    fun deleteAllRuns(): Int {
        val count = runs.size
        runs.values.forEach { it.handler.abort() }
        runs.clear()
        currentRunBySession.clear()
        currentRun = null
        return count
    }
}
