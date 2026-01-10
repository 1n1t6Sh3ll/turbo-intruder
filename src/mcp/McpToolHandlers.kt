package mcp

import burp.Utils
import burp.evalJython
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.concurrent.thread

class McpToolHandlers(
    private val manager: RunManager,
    private val organizerProvider: OrganizerProvider = BurpOrganizerProvider()
) {

    fun getOrganizerItems(ids: String): Map<String, Any?> {
        val idSet = ids.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        val items = organizerProvider.getItemsByIds(idSet)
        return mapOf(
            "items" to items.map { item ->
                mapOf(
                    "id" to item.id,
                    "request" to item.request,
                    "response" to item.response,
                    "notes" to item.notes
                )
            }
        )
    }

    fun setOrganizerNotes(id: Int, notes: String): Map<String, String> {
        val success = organizerProvider.setNotes(id, notes)
        return if (success) {
            mapOf("status" to "success")
        } else {
            mapOf("error" to "not_found")
        }
    }

    fun listOrganizerItems(): Map<String, Any> {
        val items = organizerProvider.getItems()
        return mapOf(
            "count" to items.size,
            "items" to items.map { mapOf("id" to it.id) }
        )
    }

    fun saveToOrganizer(runId: String?, items: String): Map<String, Any> {
        val run = if (runId != null) {
            manager.getRun(runId)
        } else {
            manager.currentRun
        } ?: return mapOf("saved" to emptyList<Int>(), "errors" to listOf(mapOf("error" to "No run found")))

        val mapper = ObjectMapper()
        val itemList = mapper.readTree(items)

        val saved = mutableListOf<Int>()
        val errors = mutableListOf<Map<String, Any>>()

        for (item in itemList) {
            val requestId = item.get("request_id").asInt()
            val notes = item.get("notes").asText()
            val request = run.store.getRequest(requestId)
            if (request == null) {
                errors.add(mapOf("request_id" to requestId, "error" to "Request not found"))
                continue
            }
            organizerProvider.sendToOrganizer(request, notes)
            saved.add(requestId)
        }

        return mapOf("saved" to saved, "errors" to errors)
    }

    fun startRun(
        script: String,
        baseRequest: String,
        endpoint: String,
        baseInput: String
    ): Map<String, Any?> {
        val run = manager.startRun()
        launchRun(run, script, baseRequest, endpoint, baseInput)
        return mapOf("status" to "started", "run_id" to run.id)
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
            }
        }
    }
}
