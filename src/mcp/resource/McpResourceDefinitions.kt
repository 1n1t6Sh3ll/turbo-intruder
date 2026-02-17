package mcp.resource

import mcp.McpResourceHandlers

private fun ResourceBuilder.requestDetailParams(handlers: McpResourceHandlers) {
    queryInt("body_limit", default = 100, description = "chars of body to include")
    queryString("export", description = "set to 'file' to write to temp files")
    handle { params ->
        handlers.getRequestDetail(
            runId = params.path("run_id"),
            requestId = params.path("id").toInt(),
            bodyLimit = params.int("body_limit")!!,
            exportFile = params.string("export") == "file"
        )
    }
}

private val acronyms = setOf("api")

private fun formatDocName(topic: String): String =
    topic.split("-").joinToString(" ") { word ->
        if (word in acronyms) word.uppercase()
        else word.replaceFirstChar { it.uppercase() }
    }

fun createResourceDefinitions(handlers: McpResourceHandlers): List<ResourceDefinition> = listOf(

    // === Run Resources ===

    resource("turbo://runs/{run_id}") {
        name = "Status of a specific run"
        description = "Get detailed status of a specific run including running state, result count, and status message"
        handle { params ->
            handlers.getRunStatus(params.path("run_id"))
        }
    },

    resource("turbo://runs/{run_id}/summary") {
        name = "Run results summary"
        description = "Get paginated list of results from a run. Example: turbo://runs/abc123/summary?limit=50"
        queryString("sort_by", default = "id", description = "id|status|length|time|wordcount")
        queryBool("descending", default = true)
        queryInt("limit", default = 100)
        queryInt("offset", default = 0)
        handle { params ->
            handlers.getResults(
                runId = params.path("run_id"),
                sortBy = params.string("sort_by")!!,
                descending = params.bool("descending")!!,
                limit = params.int("limit")!!,
                offset = params.int("offset")!!
            )
        }
    },

    resource("turbo://runs/{run_id}/{id}") {
        name = "Result detail by numeric ID"
        description = "Get full HTTP request/response for result {id} from run {run_id}. Example: turbo://runs/abc123/42"
        requestDetailParams(handlers)
    },

    // Alias for clients that hallucinate /requests/ in the path
    resource("turbo://runs/{run_id}/requests/{id}") {
        name = "Result detail (alias)"
        description = "Alias for turbo://runs/{run_id}/{id} - prefer the shorter form"
        requestDetailParams(handlers)
    },

    // === Organizer Resources ===

    resource("turbo://organizer") {
        name = "List of all Organizer items"
        description = "List all items in Burp's Organizer with their IDs"
        queryString("domain", description = "filter by host")
        queryInt("page", default = 1)
        queryString("searchNotes", description = "filter by notes content (case-insensitive)")
        queryString("searchRequest", description = "filter by request content (case-insensitive)")
        queryString("searchResponse", description = "filter by response content (case-insensitive)")
        handle { params ->
            handlers.listOrganizerItems(
                domain = params.string("domain"),
                page = params.int("page")!!,
                searchNotes = params.string("searchNotes"),
                searchRequest = params.string("searchRequest"),
                searchResponse = params.string("searchResponse")
            )
        }
    },

    resource("turbo://organizer/{id}") {
        name = "Organizer entry by numeric ID"
        description = "Get full request, response, and notes for an Organizer entry. Example: turbo://organizer/42"
        queryInt("body_limit", default = 100)
        handle { params ->
            handlers.getOrganizerItem(
                id = params.path("id").toInt(),
                bodyLimit = params.int("body_limit")!!
            )
        }
    }

) + // === Documentation Resources ===

handlers.docTopics.map { (topic, description) ->
    resource("turbo://docs/$topic") {
        name = formatDocName(topic)
        this.description = description
        mimeType = "text/markdown"
        handle { handlers.getDoc(topic) }
    }
} + listOf( // === Example Script Resources ===

    resource("turbo://examples") {
        name = "Example Scripts"
        description = "List all available example scripts"
        handle { handlers.listExamples() }
    },

    resource("turbo://examples/{name}") {
        name = "Example Script"
        description = "Get the content of an example script by name"
        mimeType = "text/x-python"
        handle { params -> handlers.getExample(params.path("name")) }
    }
)
