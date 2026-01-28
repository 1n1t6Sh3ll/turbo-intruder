package mcp.resource

import mcp.McpResourceHandlers

fun createResourceDefinitions(handlers: McpResourceHandlers): List<ResourceDefinition> = listOf(

    // === Run Resources ===

    resource("turbo://runs") {
        name = "List of all runs"
        description = "List all runs with their status and result counts"
        handle { sessionId, _ ->
            handlers.listRuns(sessionId)
        }
    },

    resource("turbo://runs/{run_id}") {
        name = "Status of a specific run"
        description = "Get detailed status of a specific run including running state, result count, and status message. Use 'current' for the most recent run"
        handle { sessionId, params ->
            handlers.getRunStatus(sessionId, params.path("run_id"))
        }
    },

    resource("turbo://runs/{run_id}/summary") {
        name = "Run results summary"
        description = "Get paginated list of results from a run. Example: turbo://runs/abc123/summary?limit=50"
        queryString("sort_by", default = "id", description = "id|status|length|time|wordcount")
        queryBool("descending", default = true)
        queryInt("limit", default = 100)
        queryInt("offset", default = 0)
        handle { sessionId, params ->
            handlers.getResults(
                sessionId = sessionId,
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
        queryInt("body_limit", default = 100, description = "chars of body to include")
        queryString("export", description = "set to 'file' to write to temp files")
        handle { sessionId, params ->
            handlers.getRequestDetail(
                sessionId = sessionId,
                runId = params.path("run_id"),
                requestId = params.path("id").toInt(),
                bodyLimit = params.int("body_limit")!!,
                exportFile = params.string("export") == "file"
            )
        }
    },

    // Alias for clients that hallucinate /requests/ in the path
    resource("turbo://runs/{run_id}/requests/{id}") {
        name = "Result detail (alias)"
        description = "Alias for turbo://runs/{run_id}/{id} - prefer the shorter form"
        queryInt("body_limit", default = 100, description = "chars of body to include")
        queryString("export", description = "set to 'file' to write to temp files")
        handle { sessionId, params ->
            handlers.getRequestDetail(
                sessionId = sessionId,
                runId = params.path("run_id"),
                requestId = params.path("id").toInt(),
                bodyLimit = params.int("body_limit")!!,
                exportFile = params.string("export") == "file"
            )
        }
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
        handle { _, params ->
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
        handle { _, params ->
            handlers.getOrganizerItem(
                id = params.path("id").toInt(),
                bodyLimit = params.int("body_limit")!!
            )
        }
    },

    // === Documentation Resources ===

    resource("turbo://docs/api-quickstart") {
        name = "API Quickstart"
        description = "Quick reference for scripting"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("api-quickstart") }
    },

    resource("turbo://docs/engines") {
        name = "Engine Types"
        description = "Engine types (THREADED, BURP, BURP2)"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("engines") }
    },

    resource("turbo://docs/settings") {
        name = "Settings Reference"
        description = "Complete parameter reference"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("settings") }
    },

    resource("turbo://docs/race-conditions") {
        name = "Race Conditions"
        description = "Race condition testing with gates"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("race-conditions") }
    },

    resource("turbo://docs/response-processing") {
        name = "Response Processing"
        description = "Handling and filtering responses"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("response-processing") }
    },

    resource("turbo://docs/decorators") {
        name = "Decorators"
        description = "Response decorator reference"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("decorators") }
    },

    resource("turbo://docs/misc") {
        name = "Misc Utilities"
        description = "Wordlists and utilities"
        mimeType = "text/markdown"
        handle { _, _ -> handlers.getDoc("misc") }
    }
)
