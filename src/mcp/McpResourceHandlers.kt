package mcp

import burp.SortField

class McpResourceHandlers(private val manager: RunManager) {

    fun listRuns(): Map<String, Any> {
        val runs = manager.getAllRuns().map { run ->
            mapOf(
                "run_id" to run.id,
                "running" to run.handler.isRunning(),
                "finished" to run.handler.hasFinished(),
                "result_count" to run.store.count(),
                "created_at" to run.createdAt
            )
        }
        return mapOf("runs" to runs)
    }

    fun getRunStatus(runId: String?): Map<String, Any?> {
        val run = manager.getRun(runId)
            ?: return mapOf("error" to if (runId == null || runId == "current") "no_current_run" else "not_found")

        return mapOf(
            "run_id" to run.id,
            "running" to run.handler.isRunning(),
            "finished" to run.handler.hasFinished(),
            "status_message" to run.handler.statusString(),
            "result_count" to run.store.count(),
            "created_at" to run.createdAt
        )
    }

    fun getResults(
        runId: String?,
        sortBy: String,
        descending: Boolean,
        limit: Int,
        offset: Int
    ): Map<String, Any?> {
        val run = manager.getRun(runId)
            ?: return mapOf("error" to if (runId == null || runId == "current") "no_current_run" else "not_found")

        val sortField = try {
            SortField.valueOf(sortBy.uppercase())
        } catch (e: IllegalArgumentException) {
            SortField.ID
        }

        val results = run.store.getResults(sortField, descending, limit, offset)

        return mapOf(
            "results" to results.map { req ->
                mapOf(
                    "id" to req.id,
                    "status" to req.code,
                    "length" to req.length,
                    "time" to req.time,
                    "wordcount" to req.wordcount,
                    "words" to req.words,
                    "label" to req.label
                )
            },
            "total_count" to run.store.count()
        )
    }

    fun getRequestDetail(runId: String?, requestId: Int): Map<String, Any?> {
        val run = manager.getRun(runId)
            ?: return mapOf("error" to if (runId == null || runId == "current") "no_current_run" else "not_found")

        val request = run.store.getRequest(requestId)
            ?: return mapOf("error" to "request_not_found")

        return mapOf(
            "request" to request.getRequest(),
            "response" to request.response,
            "status" to request.code,
            "length" to request.length,
            "time" to request.time,
            "words" to request.words
        )
    }

    // URI parsing utilities

    fun parseRunId(uri: String): String? {
        val match = Regex("turbo://runs/([^/\\?]+)").find(uri)
        return match?.groupValues?.get(1)
    }

    fun parseRequestId(uri: String): Int? {
        val match = Regex("turbo://runs/[^/]+/requests/(\\d+)").find(uri)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun parseQueryParams(uri: String): Map<String, String> {
        val queryStart = uri.indexOf('?')
        if (queryStart == -1) return emptyMap()

        return uri.substring(queryStart + 1)
            .split('&')
            .mapNotNull { param ->
                val parts = param.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    fun handleResourceRead(uri: String): Map<String, Any?> {
        return when {
            uri == "turbo://runs" -> listRuns()
            uri.matches(Regex("turbo://runs/[^/]+/requests/\\d+.*")) -> {
                val runId = parseRunId(uri)
                val requestId = parseRequestId(uri) ?: return mapOf("error" to "invalid_request_id")
                getRequestDetail(runId, requestId)
            }
            uri.matches(Regex("turbo://runs/[^/]+/results.*")) -> {
                val runId = parseRunId(uri)
                val params = parseQueryParams(uri)
                getResults(
                    runId = runId,
                    sortBy = params["sort_by"] ?: "id",
                    descending = params["descending"] != "false",
                    limit = params["limit"]?.toIntOrNull() ?: 100,
                    offset = params["offset"]?.toIntOrNull() ?: 0
                )
            }
            uri.matches(Regex("turbo://runs/[^/]+.*")) -> {
                val runId = parseRunId(uri)
                getRunStatus(runId)
            }
            else -> mapOf("error" to "unknown_resource")
        }
    }
}
