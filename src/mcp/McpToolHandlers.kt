package mcp

import burp.Utils
import burp.evalJython
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.concurrent.thread

class McpToolHandlers(
    private val manager: RunManager,
    private val organizerProvider: OrganizerProvider = BurpOrganizerProvider(),
    private val collaboratorProvider: CollaboratorProvider? = null
) {

    fun setOrganizerNotes(id: Int, notes: String): Map<String, String> {
        val success = organizerProvider.setNotes(id, notes)
        return if (success) {
            mapOf("status" to "success")
        } else {
            mapOf("error" to "not_found")
        }
    }

    fun generateCollaboratorPayload(metadata: String): Map<String, Any?> {
        val provider = collaboratorProvider
            ?: return mapOf("error" to "Collaborator requires Burp Suite connection")
        val payload = provider.generatePayload(metadata)
        return mapOf("payload" to payload)
    }

    fun getCollaboratorInteractions(payloads: List<String>?): Map<String, Any> {
        val provider = collaboratorProvider
            ?: return mapOf("error" to "Collaborator requires Burp Suite connection", "interactions" to emptyList<Any>())
        val interactions = provider.getInteractions(payloads)
        return mapOf(
            "interactions" to interactions.map { interaction ->
                mapOf(
                    "payload" to interaction.payload,
                    "metadata" to interaction.metadata,
                    "type" to interaction.type,
                    "timestamp" to interaction.timestamp,
                    "client_ip" to interaction.clientIp,
                    "details" to interaction.details
                )
            }
        )
    }

    fun saveToOrganizer(runId: String, items: String): Map<String, Any> {
        val run = manager.getRun(runId)
            ?: return mapOf("saved" to emptyList<Int>(), "errors" to listOf(mapOf("error" to "No run found")))

        val mapper = ObjectMapper()
        val itemList = mapper.readTree(items)

        val saved = mutableListOf<Int>()
        val errors = mutableListOf<Map<String, Any>>()

        val scriptSection = if (run.handler.code.isNotBlank()) {
            "\n\n--- Script ---\n${run.handler.code}"
        } else ""

        for (item in itemList) {
            val requestId = item.get("request_id").asInt()
            val notes = item.get("notes").asText()
            val request = run.store.getRequest(requestId)
            if (request == null) {
                errors.add(mapOf("request_id" to requestId, "error" to "Request not found"))
                continue
            }
            organizerProvider.sendToOrganizer(request, notes + scriptSection)
            saved.add(requestId)
        }

        return mapOf("saved" to saved, "errors" to errors)
    }

    fun startRun(
        script: String,
        baseRequest: String,
        endpoint: String,
        baseInput: String,
        timeoutMs: Long = 60000,
        normalizeLineEndings: Boolean = true
    ): Map<String, Any?> {
        val normalized = normalizeScriptLineEndings(script, normalizeLineEndings)
        val run = manager.startRun()
        launchRun(run, normalized.script, baseRequest, endpoint, baseInput)

        // Wait for completion or timeout
        val startTime = System.currentTimeMillis()
        while (!run.handler.hasFinished()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                val result = mutableMapOf<String, Any?>(
                    "status" to "timeout",
                    "run_id" to run.id,
                    "result_count" to run.store.count()
                )
                normalized.warning?.let { result["warning"] = it }
                return result
            }
            Thread.sleep(50)
        }

        // Get results sorted by anomaly rank descending
        val failed = run.handler.hasError()
        val results = run.store.getResults(burp.SortField.ANOMALY_RANK, true, 100, 0)
        val result = mutableMapOf<String, Any?>(
            "status" to if (failed) "failed" else "completed",
            "run_id" to run.id,
            "result_count" to run.store.count(),
            "results" to results.map { it.toSummaryMap() }
        )
        if (failed) {
            result["error_message"] = run.handler.statusString()
        }
        normalized.warning?.let { result["warning"] = it }
        return result
    }

    fun startRunAsync(
        script: String,
        baseRequest: String,
        endpoint: String,
        baseInput: String,
        normalizeLineEndings: Boolean = true
    ): Map<String, Any?> {
        val normalized = normalizeScriptLineEndings(script, normalizeLineEndings)
        val run = manager.startRun()
        launchRun(run, normalized.script, baseRequest, endpoint, baseInput)
        val result = mutableMapOf<String, Any?>("status" to "started", "run_id" to run.id)
        normalized.warning?.let { result["warning"] = it }
        return result
    }

    fun stopRun(runId: String): Map<String, String> {
        return mapOf("status" to manager.stopRun(runId))
    }

    fun deleteRun(runId: String): Map<String, String> {
        return mapOf("status" to manager.deleteRun(runId))
    }

    fun searchResponses(runId: String, query: String): Map<String, Any> {
        val run = manager.getRun(runId)
            ?: return mapOf("error" to "No run found")

        val matches = run.store.getAllRquests()
            .filter { it.response?.contains(query) == true || it.label.contains(query) }
            .map { it.id }

        return mapOf(
            "matches" to matches,
            "match_count" to matches.size
        )
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

        val normalizedRequest = Utils.normalizeLineEndings(baseRequest)

        thread {
            try {
                evalJython(
                    code = script,
                    baseRequest = normalizedRequest,
                    rawRequest = normalizedRequest.toByteArray(Charsets.ISO_8859_1),
                    endpoint = endpoint,
                    host = host,
                    baseInput = baseInput,
                    store = run.store,
                    handler = run.handler,
                    reqs = null,
                    requestTable = null
                )
            } catch (e: Exception) {
                System.err.println("Run ${run.id} failed: ${e.message}")
            } finally {
                run.handler.markScriptCompleted()
            }
        }
    }
}
