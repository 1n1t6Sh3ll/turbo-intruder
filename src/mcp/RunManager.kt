package mcp

import java.util.concurrent.ConcurrentHashMap

class RunManager {
    private val runs = ConcurrentHashMap<String, ActiveRun>()
    @Volatile
    var currentRun: ActiveRun? = null
        private set

    fun startRun(): ActiveRun {
        deleteAllRuns()
        val run = ActiveRun()
        runs[run.id] = run
        currentRun = run
        return run
    }

    fun startConcurrentRun(): ActiveRun {
        val run = ActiveRun()
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
        currentRun = null
        return count
    }
}
