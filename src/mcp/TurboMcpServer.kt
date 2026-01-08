package mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector

class TurboMcpServer(private val port: Int = 31337) {

    private val manager = RunManager()
    val toolHandlers = McpToolHandlers(manager)
    val resourceHandlers = McpResourceHandlers(manager)

    private var server: McpSyncServer? = null
    private var jettyServer: Server? = null
    private val objectMapper = ObjectMapper()

    fun start() {
        // Create Jetty server on specified port
        val jetty = Server()
        val connector = ServerConnector(jetty)
        connector.host = "127.0.0.1"
        connector.port = port
        jetty.addConnector(connector)

        // Create the MCP SSE transport provider
        val transportProvider = HttpServletSseServerTransportProvider.builder()
            .objectMapper(objectMapper)
            .messageEndpoint("/mcp/message")
            .build()

        // Set up servlet context
        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"
        context.addServlet(ServletHolder(transportProvider), "/*")
        jetty.handler = context

        // Start Jetty
        jetty.start()
        jettyServer = jetty

        server = McpServer.sync(transportProvider)
            .serverInfo("turbo-intruder", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(true, true)
                .logging()
                .build())
            .tools(buildToolSpecifications())
            .resources(buildResourceSpecifications())
            .build()
    }

    fun stop() {
        server?.close()
        server = null
        jettyServer?.stop()
        jettyServer = null
    }

    private fun buildToolSpecifications(): List<McpServerFeatures.SyncToolSpecification> {
        return listOf(
            buildStartRunTool(),
            buildStartConcurrentRunTool(),
            buildStopRunTool(),
            buildDeleteRunTool(),
            buildDeleteAllRunsTool()
        )
    }

    private fun buildStartRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool(
            "start_run",
            "Start a new Turbo Intruder attack run. This clears any previous runs and starts fresh. Use for single-run scenarios.",
            """
            {
                "type": "object",
                "properties": {
                    "script": {
                        "type": "string",
                        "description": "Python script code that controls the attack"
                    },
                    "base_request": {
                        "type": "string",
                        "description": "The base HTTP request template with injection points marked as %s"
                    },
                    "endpoint": {
                        "type": "string",
                        "description": "Target endpoint URL (e.g., https://example.com)"
                    },
                    "base_input": {
                        "type": "string",
                        "description": "Input data to feed into the script (e.g., wordlist content)"
                    }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent()
        )

        return McpServerFeatures.SyncToolSpecification(tool) { _, args ->
            val result = toolHandlers.startRun(
                script = args["script"] as? String ?: "",
                baseRequest = args["base_request"] as? String ?: "",
                endpoint = args["endpoint"] as? String ?: "",
                baseInput = args["base_input"] as? String ?: ""
            )
            McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                false
            )
        }
    }

    private fun buildStartConcurrentRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool(
            "start_concurrent_run",
            "Start a new concurrent attack run. Does not clear previous runs, allowing multiple runs to execute in parallel.",
            """
            {
                "type": "object",
                "properties": {
                    "script": {
                        "type": "string",
                        "description": "Python script code that controls the attack"
                    },
                    "base_request": {
                        "type": "string",
                        "description": "The base HTTP request template with injection points marked as %s"
                    },
                    "endpoint": {
                        "type": "string",
                        "description": "Target endpoint URL (e.g., https://example.com)"
                    },
                    "base_input": {
                        "type": "string",
                        "description": "Input data to feed into the script (e.g., wordlist content)"
                    }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent()
        )

        return McpServerFeatures.SyncToolSpecification(tool) { _, args ->
            val result = toolHandlers.startConcurrentRun(
                script = args["script"] as? String ?: "",
                baseRequest = args["base_request"] as? String ?: "",
                endpoint = args["endpoint"] as? String ?: "",
                baseInput = args["base_input"] as? String ?: ""
            )
            McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                false
            )
        }
    }

    private fun buildStopRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool(
            "stop_run",
            "Stop a running attack. Aborts the attack but preserves the results.",
            """
            {
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "ID of the run to stop. Omit or use 'current' for the most recent run."
                    }
                }
            }
            """.trimIndent()
        )

        return McpServerFeatures.SyncToolSpecification(tool) { _, args ->
            val result = toolHandlers.stopRun(args["run_id"] as? String)
            McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                false
            )
        }
    }

    private fun buildDeleteRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool(
            "delete_run",
            "Delete a run and all its results. Also stops the run if it's still executing.",
            """
            {
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "ID of the run to delete. Omit or use 'current' for the most recent run."
                    }
                }
            }
            """.trimIndent()
        )

        return McpServerFeatures.SyncToolSpecification(tool) { _, args ->
            val result = toolHandlers.deleteRun(args["run_id"] as? String)
            McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                false
            )
        }
    }

    private fun buildDeleteAllRunsTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool(
            "delete_all_runs",
            "Delete all runs and their results. Useful for cleanup.",
            """
            {
                "type": "object",
                "properties": {}
            }
            """.trimIndent()
        )

        return McpServerFeatures.SyncToolSpecification(tool) { _, _ ->
            val result = toolHandlers.deleteAllRuns()
            McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                false
            )
        }
    }

    private fun buildResourceSpecifications(): List<McpServerFeatures.SyncResourceSpecification> {
        return listOf(
            buildRunsListResource(),
            buildRunStatusResourceTemplate(),
            buildRunResultsResourceTemplate(),
            buildRequestDetailResourceTemplate()
        )
    }

    private fun buildRunsListResource(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource(
            "turbo://runs",
            "List of all Turbo Intruder runs",
            "List all attack runs with their status and result counts",
            "application/json",
            null
        )

        return McpServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val result = resourceHandlers.listRuns()
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    "turbo://runs",
                    "application/json",
                    objectMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildRunStatusResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource(
            "turbo://runs/{run_id}",
            "Status of a specific run",
            "Get detailed status of a specific run including running state, result count, and status message. Use 'current' for the most recent run.",
            "application/json",
            null
        )

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val runId = resourceHandlers.parseRunId(request.uri())
            val result = resourceHandlers.getRunStatus(runId)
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    objectMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildRunResultsResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource(
            "turbo://runs/{run_id}/results",
            "Results from a run",
            "Get paginated results from a run. Supports query params: sort_by (id|status|length|time|wordcount), descending (true|false), limit, offset",
            "application/json",
            null
        )

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val params = resourceHandlers.parseQueryParams(uri)
            val result = resourceHandlers.getResults(
                runId = runId,
                sortBy = params["sort_by"] ?: "id",
                descending = params["descending"] != "false",
                limit = params["limit"]?.toIntOrNull() ?: 100,
                offset = params["offset"]?.toIntOrNull() ?: 0
            )
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    uri,
                    "application/json",
                    objectMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildRequestDetailResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource(
            "turbo://runs/{run_id}/requests/{id}",
            "Details of a specific request",
            "Get full request and response details for a specific result item",
            "application/json",
            null
        )

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val requestId = resourceHandlers.parseRequestId(uri) ?: -1
            val result = resourceHandlers.getRequestDetail(runId, requestId)
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    uri,
                    "application/json",
                    objectMapper.writeValueAsString(result)
                ))
            )
        }
    }
}
