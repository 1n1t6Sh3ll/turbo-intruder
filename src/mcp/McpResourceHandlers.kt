package mcp

import burp.SortField
import java.io.File
import kotlin.io.path.createTempDirectory

class McpResourceHandlers(
    private val manager: RunManager,
    private val organizerProvider: OrganizerProvider? = null,
    private val desyncMode: () -> Boolean = { false }
) {

    private val docTopics = mapOf(
        "api-quickstart" to "Quick reference for scripting",
        "engines" to "Engine types (THREADED, BURP, BURP2)",
        "settings" to "Complete parameter reference",
        "race-conditions" to "Race condition testing with gates",
        "response-processing" to "Handling and filtering responses",
        "decorators" to "Response decorator reference",
        "misc" to "Wordlists and utilities"
    )

    fun listRuns(sessionId: String): Map<String, Any> {
        val runs = manager.getAllRuns(sessionId).map { run ->
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

    fun getRunStatus(sessionId: String, runId: String?): Map<String, Any?> {
        val run = manager.getRun(sessionId, runId)
            ?: return mapOf("error" to if (runId == null || runId == "current") "no_current_run" else "not_found")

        val baseStatus = mapOf(
            "run_id" to run.id,
            "running" to run.handler.isRunning(),
            "finished" to run.handler.hasFinished(),
            "status_message" to run.handler.statusString(),
            "result_count" to run.store.count(),
            "created_at" to run.createdAt
        )

        // Include summary when run is finished
        if (run.handler.hasFinished()) {
            val results = run.store.getResults(SortField.ANOMALY_RANK, true, 20, 0)
            val summary = results.map { req ->
                mapOf(
                    "id" to req.id,
                    "status" to req.code,
                    "length" to req.length,
                    "time" to req.time,
                    "wordcount" to req.wordcount,
                    "words" to req.words,
                    "label" to req.label,
                    "anomaly_rank" to req.anomalyRank
                )
            }
            return baseStatus + mapOf("summary" to summary)
        }

        return baseStatus
    }

    fun getResults(
        sessionId: String,
        runId: String?,
        sortBy: String,
        descending: Boolean,
        limit: Int,
        offset: Int
    ): Map<String, Any?> {
        val run = manager.getRun(sessionId, runId)
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
                    "label" to req.label,
                    "anomaly_rank" to req.anomalyRank
                )
            },
            "total_count" to run.store.count(),
            "status_codes" to run.store.getUniqueStatusCodes()
        )
    }

    fun getRequestDetail(
        sessionId: String,
        runId: String?,
        requestId: Int,
        bodyLimit: Int = 100,
        exportFile: Boolean = false
    ): Map<String, Any?> {
        val run = manager.getRun(sessionId, runId)
            ?: return mapOf("error" to if (runId == null || runId == "current") "no_current_run" else "not_found")

        val request = run.store.getRequest(requestId)
            ?: return mapOf("error" to "request_not_found")

        if (exportFile) {
            val tempDir = createTempDirectory("turbo-${run.id}-").toFile()
            val requestFile = File(tempDir, "request-$requestId.txt")
            val responseFile = File(tempDir, "response-$requestId.txt")

            requestFile.writeText(request.getRequest())
            request.response?.let { responseFile.writeText(it) }

            return mapOf(
                "request_file" to requestFile.absolutePath,
                "response_file" to responseFile.absolutePath,
                "status" to request.code,
                "length" to request.length,
                "time" to request.time,
                "words" to request.words
            )
        }

        val response = request.response
        val (headers, body) = splitResponse(response)
        val truncatedBody = TruncatedHttpBody(body, bodyLimit)

        return mapOf(
            "request" to request.getRequest(),
            "response_headers" to filterHeaders(headers),
            "status" to request.code,
            "length" to request.length,
            "time" to request.time,
            "words" to request.words
        ) + truncatedBody.toResponseFields()
    }

    private fun splitResponse(response: String?): Pair<String, String> {
        if (response == null) return Pair("", "")

        val separatorIndex = response.indexOf("\r\n\r\n")
        return if (separatorIndex != -1) {
            Pair(
                response.substring(0, separatorIndex),
                response.substring(separatorIndex + 4)
            )
        } else {
            Pair(response, "")
        }
    }

    private fun filterHeaders(headers: String): String {
        if (!desyncMode()) return headers
        return headers.split("\r\n")
            .filterNot { it.startsWith("Connection:", ignoreCase = true) }
            .joinToString("\r\n")
    }

    // Organizer resources

    fun listOrganizerItems(domain: String? = null, page: Int = 1): Map<String, Any> {
        val allItems = organizerProvider?.getItems() ?: emptyList()
        val filteredItems = if (domain != null) {
            allItems.filter { it.host == domain }
        } else {
            allItems
        }

        // Apply sorting and pagination only when filtering
        return if (domain != null) {
            // Sort by timestamp descending (nulls last), then by ID descending as tiebreaker
            val sortedItems = filteredItems.sortedWith(
                compareByDescending<OrganizerItemData> { it.timeRequestSent }
                    .thenByDescending { it.id }
            )

            val pageSize = 10
            val totalPages = (sortedItems.size + pageSize - 1) / pageSize
            val startIndex = (page - 1) * pageSize
            val pagedItems = sortedItems.drop(startIndex).take(pageSize)

            mapOf(
                "count" to sortedItems.size,
                "page" to page,
                "page_size" to pageSize,
                "total_pages" to totalPages,
                "items" to pagedItems.map { mapOf("id" to it.id) }
            )
        } else {
            mapOf(
                "count" to filteredItems.size,
                "items" to filteredItems.map { mapOf("id" to it.id) }
            )
        }
    }

    fun getOrganizerItem(id: Int, bodyLimit: Int = 100): Map<String, Any?> {
        val items = organizerProvider?.getItemsByIds(setOf(id)) ?: emptyList()
        val item = items.firstOrNull()
            ?: return mapOf("error" to "not_found")

        val (headers, body) = splitResponse(item.response)
        val truncatedBody = TruncatedHttpBody(body, bodyLimit)

        return mapOf(
            "id" to item.id,
            "request" to item.request,
            "response_headers" to filterHeaders(headers),
            "notes" to item.notes,
            "host" to item.host,
            "port" to item.port,
            "secure" to item.secure
        ) + truncatedBody.toResponseFields()
    }

    fun getOrganizerItems(ids: Set<Int>, bodyLimit: Int = 100): Map<String, Any?> {
        val items = organizerProvider?.getItemsByIds(ids) ?: emptyList()

        return mapOf(
            "items" to items.map { item ->
                val (headers, body) = splitResponse(item.response)
                val truncatedBody = TruncatedHttpBody(body, bodyLimit)

                mapOf(
                    "id" to item.id,
                    "request" to item.request,
                    "response_headers" to filterHeaders(headers),
                    "notes" to item.notes,
                    "host" to item.host,
                    "port" to item.port,
                    "secure" to item.secure
                ) + truncatedBody.toResponseFields()
            }
        )
    }

    // Documentation resources

    fun listDocs(): Map<String, Any> {
        return mapOf(
            "topics" to docTopics.map { (name, description) ->
                mapOf(
                    "name" to name,
                    "uri" to "turbo://docs/$name",
                    "description" to description
                )
            }
        )
    }

    fun getDoc(topic: String): Map<String, Any?> {
        if (!docTopics.containsKey(topic)) {
            return mapOf("error" to "unknown_topic", "available_topics" to docTopics.keys.toList())
        }

        val content = loadDocContent(topic)
            ?: return mapOf("error" to "doc_not_found")

        return mapOf(
            "topic" to topic,
            "content" to content
        )
    }

    private fun loadDocContent(topic: String): String? {
        // Try loading from classpath resources first (when running as jar)
        // docs folder is added as resource root, so files are at /$topic.md
        val resourcePath = "/$topic.md"
        javaClass.getResourceAsStream(resourcePath)?.use { stream ->
            return stream.bufferedReader().readText()
        }

        // Fall back to file system (for development)
        val possiblePaths = listOf(
            "docs/$topic.md",
            "../docs/$topic.md",
            "../../docs/$topic.md"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                return file.readText()
            }
        }

        return null
    }

    // URI parsing utilities

    fun parseRunId(uri: String): String? {
        val match = Regex("turbo://runs/([^/\\?]+)").find(uri)
        return match?.groupValues?.get(1)
    }

    fun parseRequestId(uri: String): Int? {
        val match = Regex("turbo://runs/[^/]+/(\\d+)").find(uri)
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

    fun parseDocTopic(uri: String): String? {
        val match = Regex("turbo://docs/([^/\\?]+)").find(uri)
        return match?.groupValues?.get(1)
    }

    fun parseOrganizerId(uri: String): Int? {
        val match = Regex("turbo://organizer/(\\d+)").find(uri)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun parseOrganizerIds(uri: String): Set<Int> {
        val match = Regex("turbo://organizer/([\\d,]+)").find(uri)
            ?: return emptySet()
        return match.groupValues[1]
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

}
