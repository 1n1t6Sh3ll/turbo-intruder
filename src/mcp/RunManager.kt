package mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RunManager(private val maxCompletedRuns: Int = 100) {
    private val runs = ConcurrentHashMap<String, ActiveRun>()
    private val evictedIds = ConcurrentHashMap.newKeySet<String>()
    private val sequenceCounter = AtomicLong(0)

    fun startRun(): ActiveRun {
        evictCompletedRuns()
        val run = ActiveRun(sequenceCounter.getAndIncrement())
        runs[run.id] = run
        return run
    }

    fun getRun(runId: String): ActiveRun? {
        return runs[runId]
    }

    fun isEvicted(runId: String): Boolean {
        return runId in evictedIds
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

    private fun evictCompletedRuns() {
        val completed = runs.values
            .filter { it.handler.hasFinished() }
            .sortedBy { it.sequenceNumber }

        val excess = completed.size - maxCompletedRuns
        if (excess > 0) {
            completed.take(excess).forEach { run ->
                runs.remove(run.id)
                evictedIds.add(run.id)
            }
        }
    }
}
