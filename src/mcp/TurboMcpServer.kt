package mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
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
    private val jsonMapper = JacksonMcpJsonMapper(ObjectMapper())

    fun start() {
        // Fix classloader for ServiceLoader in Burp's environment
        val originalClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = this::class.java.classLoader
        try {
            startInternal()
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    private fun startInternal() {
        // Create Jetty server on specified port
        val jetty = Server()
        val connector = ServerConnector(jetty)
        connector.host = "127.0.0.1"
        connector.port = port
        jetty.addConnector(connector)

        // Create the MCP streaming HTTP transport provider
        val transportProvider = HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(jsonMapper)
            .mcpEndpoint("/mcp")
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
                .tools(true)  // listChanged
                .resources(true, true)  // subscribe, listChanged
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
        val tool = McpSchema.Tool.builder()
            .name("start_run")
            .description("Start a new Turbo Intruder attack run. This clears any previous runs and starts fresh. Use for single-run scenarios.")
            .inputSchema(jsonMapper, """
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
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments()
                val result = toolHandlers.startRun(
                    script = args["script"] as? String ?: "",
                    baseRequest = args["base_request"] as? String ?: "",
                    endpoint = args["endpoint"] as? String ?: "",
                    baseInput = args["base_input"] as? String ?: ""
                )
                McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(result))))
                    .isError(false)
                    .build()
            }
            .build()
    }

    private fun buildStartConcurrentRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_concurrent_run")
            .description("Start a new concurrent attack run. Does not clear previous runs, allowing multiple runs to execute in parallel.")
            .inputSchema(jsonMapper, """
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
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val args = request.arguments()
                val result = toolHandlers.startConcurrentRun(
                    script = args["script"] as? String ?: "",
                    baseRequest = args["base_request"] as? String ?: "",
                    endpoint = args["endpoint"] as? String ?: "",
                    baseInput = args["base_input"] as? String ?: ""
                )
                McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(result))))
                    .isError(false)
                    .build()
            }
            .build()
    }

    private fun buildStopRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("stop_run")
            .description("Stop a running attack. Aborts the attack but preserves the results.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "ID of the run to stop. Omit or use 'current' for the most recent run."
                    }
                }
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val result = toolHandlers.stopRun(request.arguments()["run_id"] as? String)
                McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(result))))
                    .isError(false)
                    .build()
            }
            .build()
    }

    private fun buildDeleteRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("delete_run")
            .description("Delete a run and all its results. Also stops the run if it's still executing.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "ID of the run to delete. Omit or use 'current' for the most recent run."
                    }
                }
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                val result = toolHandlers.deleteRun(request.arguments()["run_id"] as? String)
                McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(result))))
                    .isError(false)
                    .build()
            }
            .build()
    }

    private fun buildDeleteAllRunsTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("delete_all_runs")
            .description("Delete all runs and their results. Useful for cleanup.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {}
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, _ ->
                val result = toolHandlers.deleteAllRuns()
                McpSchema.CallToolResult.builder()
                    .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(result))))
                    .isError(false)
                    .build()
            }
            .build()
    }

    private fun buildResourceSpecifications(): List<McpServerFeatures.SyncResourceSpecification> {
        return listOf(
            buildRunsListResource(),
            buildRunStatusResourceTemplate(),
            buildRunResultsResourceTemplate(),
            buildRequestDetailResourceTemplate(),
            buildDocsListResource(),
            buildDocTopicResourceTemplate()
        )
    }

    private fun buildRunsListResource(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs")
            .name("List of all Turbo Intruder runs")
            .description("List all attack runs with their status and result counts")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val result = resourceHandlers.listRuns()
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    "turbo://runs",
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildRunStatusResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs/{run_id}")
            .name("Status of a specific run")
            .description("Get detailed status of a specific run including running state, result count, and status message. Use 'current' for the most recent run.")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val runId = resourceHandlers.parseRunId(request.uri())
            val result = resourceHandlers.getRunStatus(runId)
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildRunResultsResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs/{run_id}/results")
            .name("Results from a run")
            .description("Get paginated results from a run. Supports query params: sort_by (id|status|length|time|wordcount), descending (true|false), limit, offset")
            .mimeType("application/json")
            .build()

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
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildRequestDetailResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs/{run_id}/requests/{id}")
            .name("Details of a specific request")
            .description("Get full request and response details for a specific result item")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val requestId = resourceHandlers.parseRequestId(uri) ?: -1
            val result = resourceHandlers.getRequestDetail(runId, requestId)
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    uri,
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildDocsListResource(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://docs")
            .name("Turbo Intruder documentation topics")
            .description("List available documentation topics for scripting reference")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val result = resourceHandlers.listDocs()
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    "turbo://docs",
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildDocTopicResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://docs/{topic}")
            .name("Documentation for a specific topic")
            .description("Get documentation content. Topics: api-quickstart, engines, settings, race-conditions, response-processing, decorators, misc")
            .mimeType("text/markdown")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val uri = request.uri()
            val topic = resourceHandlers.parseDocTopic(uri)
            val result = resourceHandlers.getDoc(topic ?: "")

            if (result.containsKey("error")) {
                McpSchema.ReadResourceResult(
                    listOf(McpSchema.TextResourceContents(
                        uri,
                        "application/json",
                        jsonMapper.writeValueAsString(result)
                    ))
                )
            } else {
                McpSchema.ReadResourceResult(
                    listOf(McpSchema.TextResourceContents(
                        uri,
                        "text/markdown",
                        result["content"] as String
                    ))
                )
            }
        }
    }
}
