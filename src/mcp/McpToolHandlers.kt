package mcp

import burp.evalJython
import kotlin.concurrent.thread

class McpToolHandlers(private val manager: RunManager) {

    fun startRun(
        script: String,
        baseRequest: String,
        endpoint: String,
        baseInput: String
    ): Map<String, Any?> {
        val run = manager.startRun()
        launchRun(run, script, baseRequest, endpoint, baseInput)
        return mapOf("status" to "started")
    }

    fun startConcurrentRun(
        script: String,
        baseRequest: String,
        endpoint: String,
        baseInput: String
    ): Map<String, Any?> {
        val run = manager.startConcurrentRun()
        launchRun(run, script, baseRequest, endpoint, baseInput)
        return mapOf(
            "status" to "started",
            "run_id" to run.id
        )
    }

    fun stopRun(runId: String?): Map<String, String> {
        return mapOf("status" to manager.stopRun(runId))
    }

    fun deleteRun(runId: String?): Map<String, String> {
        return mapOf("status" to manager.deleteRun(runId))
    }

    fun deleteAllRuns(): Map<String, Int> {
        return mapOf("deleted_count" to manager.deleteAllRuns())
    }

    private fun launchRun(
        run: ActiveRun,
        script: String,
        baseRequest: String,
        endpoint: String,
        baseInput: String
    ) {
        val host = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore(":")
            .substringBefore("/")

        thread {
            evalJython(
                code = script,
                baseRequest = baseRequest,
                rawRequest = baseRequest.toByteArray(Charsets.ISO_8859_1),
                endpoint = endpoint,
                host = host,
                baseInput = baseInput,
                store = run.store,
                handler = run.handler,
                reqs = null,
                requestTable = null
            )
        }
    }
}
