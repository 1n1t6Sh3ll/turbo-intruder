package mcp

import java.util.concurrent.ConcurrentHashMap

class RunManager {
    private val runs = ConcurrentHashMap<String, ActiveRun>()

    fun startRun(): ActiveRun {
        val run = ActiveRun()
        runs[run.id] = run
        return run
    }

    fun getRun(runId: String): ActiveRun? {
        return runs[runId]
    }

    fun stopRun(runId: String): String {
        val run = runs[runId] ?: return "not_found"
        run.handler.abort()
        return "stopped"
    }

    fun deleteRun(runId: String): String {
        val run = runs.remove(runId) ?: return "not_found"
        run.handler.abort()
        return "deleted"
    }
}
