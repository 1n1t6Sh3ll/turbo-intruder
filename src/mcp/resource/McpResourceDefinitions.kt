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
        handle { _, params ->
            handlers.listOrganizerItems(
                domain = params.string("domain"),
                page = params.int("page")!!
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

    resource("turbo://docs") {
        name = "Documentation topics"
        description = "List available documentation topics for scripting reference"
        handle { _, _ ->
            handlers.listDocs()
        }
    },

    resource("turbo://docs/{topic}") {
        name = "Documentation for a specific topic"
        description = "Get documentation content. Topics: api-quickstart, engines, settings, race-conditions, response-processing, decorators, misc"
        mimeType = "text/markdown"
        handle { _, params ->
            handlers.getDoc(params.path("topic"))
        }
    }
)
