package mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.ee10.servlet.FilterHolder
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.util.EnumSet

/**
 * Formats an exception into a map containing the error message and full stack trace.
 * This is used to provide debugging information in MCP error responses.
 */
fun formatErrorWithStackTrace(e: Exception): Map<String, Any?> {
    val stackTrace = StringWriter().also { sw ->
        e.printStackTrace(PrintWriter(sw))
    }.toString()
    return mapOf(
        "error" to (e.message ?: "Unknown error"),
        "stack_trace" to stackTrace
    )
}

class TurboMcpServer(
    private val port: Int = 31338,
    private val disabledTools: Set<String> = emptySet(),
    private val collaboratorProvider: CollaboratorProvider? = null,
    private val organizerProvider: OrganizerProvider = BurpOrganizerProvider(),
    private val desyncMode: () -> Boolean = { false }
) {
    companion object {
        const val STATELESS_MODE = true
        const val STATELESS_SESSION_ID = "stateless"
    }

    private val manager = RunManager()
    val toolHandlers = McpToolHandlers(manager, organizerProvider, collaboratorProvider)
    val resourceHandlers = McpResourceHandlers(manager, organizerProvider, desyncMode)

    private var server: McpSyncServer? = null
    private var statelessServer: io.modelcontextprotocol.server.McpStatelessSyncServer? = null
    private var jettyServer: Server? = null
    private val jsonMapper = JacksonMcpJsonMapper(ObjectMapper())

    /**
     * Wraps a tool handler action with error handling that includes stack traces.
     */
    private fun <T> executeToolWithErrorHandling(action: () -> T): McpSchema.CallToolResult {
        return try {
            val result = action()
            McpSchema.CallToolResult.builder()
                .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(result))))
                .isError(false)
                .build()
        } catch (e: Exception) {
            val errorResult = formatErrorWithStackTrace(e)
            McpSchema.CallToolResult.builder()
                .content(listOf(McpSchema.TextContent(jsonMapper.writeValueAsString(errorResult))))
                .isError(true)
                .build()
        }
    }

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

        // Set up servlet context
        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"

        // Add Host header validation filter to prevent DNS rebinding attacks
        context.addFilter(
            FilterHolder(HostValidationFilter()),
            "/*",
            EnumSet.of(DispatcherType.REQUEST)
        )

        // Add debug logging filter to track session issues
        context.addFilter(
            FilterHolder(McpDebugLoggingFilter()),
            "/*",
            EnumSet.of(DispatcherType.REQUEST)
        )

        if (STATELESS_MODE) {
            // Stateless HTTP transport - no sessions, simpler client compatibility
            val transport = HttpServletStatelessServerTransport.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint("/")
                .build()

            context.addServlet(ServletHolder(transport), "/*")
            jetty.handler = context
            jetty.start()
            jettyServer = jetty

            statelessServer = McpServer.sync(transport)
                .serverInfo("turbo-simulator", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                    .tools(true)  // listChanged
                    .resources(true, true)  // subscribe, listChanged
                    .logging()
                    .build())
                .tools(buildStatelessToolSpecifications())
                .resources(buildStatelessResourceSpecifications())
                .build()
        } else {
            // SSE streaming transport - supports sessions
            val transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/")
                .keepAliveInterval(Duration.ofSeconds(30))
                .build()

            context.addServlet(ServletHolder(transportProvider), "/*")
            jetty.handler = context
            jetty.start()
            jettyServer = jetty

            server = McpServer.sync(transportProvider)
                .serverInfo("turbo-simulator", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                    .tools(true)  // listChanged
                    .resources(true, true)  // subscribe, listChanged
                    .logging()
                    .build())
                .tools(buildToolSpecifications())
                .resources(buildResourceSpecifications())
                .build()
        }
    }

    fun stop() {
        server?.close()
        server = null
        statelessServer?.close()
        statelessServer = null
        jettyServer?.stop()
        jettyServer = null
    }

    private val allTools by lazy {
        listOf(
            buildStartRunTool(),
            buildStartRunAsyncTool(),
            buildStartConcurrentRunAsyncTool(),
            buildStopRunTool(),
            buildDeleteRunTool(),
            buildDeleteAllRunsTool(),
            buildSetOrganizerNotesTool(),
            buildSaveToOrganizerTool(),
            buildGenerateCollaboratorPayloadTool(),
            buildGetCollaboratorInteractionsTool(),
            buildSearchResponsesTool()
        )
    }

    private fun buildToolSpecifications(): List<McpServerFeatures.SyncToolSpecification> {
        return allTools.filter { it.tool().name() !in disabledTools }
    }

    private val allStatelessTools by lazy {
        listOf(
            buildStatelessStartRunTool(),
            buildStatelessStartRunAsyncTool(),
            buildStatelessStartConcurrentRunAsyncTool(),
            buildStatelessStopRunTool(),
            buildStatelessDeleteRunTool(),
            buildStatelessDeleteAllRunsTool(),
            buildStatelessSetOrganizerNotesTool(),
            buildStatelessSaveToOrganizerTool(),
            buildStatelessGenerateCollaboratorPayloadTool(),
            buildStatelessGetCollaboratorInteractionsTool(),
            buildStatelessSearchResponsesTool()
        )
    }

    private fun buildStatelessToolSpecifications(): List<McpStatelessServerFeatures.SyncToolSpecification> {
        return allStatelessTools.filter { it.tool().name() !in disabledTools }
    }

    // Stateless tool builders - use STATELESS_SESSION_ID instead of exchange.sessionId()

    private fun buildStatelessStartRunTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_run")
            .description("Start a new run and wait for completion. This clears any previous runs and starts fresh. Returns results when complete or on timeout.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "script": { "type": "string", "description": "Python script code that controls the run" },
                    "base_request": { "type": "string", "description": "The base HTTP request template with injection points marked as %s" },
                    "endpoint": { "type": "string", "description": "Target endpoint URL (e.g., https://example.com)" },
                    "base_input": { "type": "string", "description": "Input data to feed into the script (e.g., wordlist content)" },
                    "timeout_ms": { "type": "integer", "description": "Timeout in milliseconds (default: 60000). If exceeded, returns run_id for manual polling." },
                    "normalize_line_endings": { "type": "boolean", "description": "Whether to normalize mixed line endings (\\n and \\r\\n) to \\r\\n. Default: true" }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.startRun(
                    sessionId = STATELESS_SESSION_ID,
                    script = args["script"] as? String ?: "",
                    baseRequest = args["base_request"] as? String ?: "",
                    endpoint = args["endpoint"] as? String ?: "",
                    baseInput = args["base_input"] as? String ?: "",
                    timeoutMs = (args["timeout_ms"] as? Number)?.toLong() ?: 60000,
                    normalizeLineEndings = (args["normalize_line_endings"] as? Boolean) ?: true
                )
            }
        }
    }

    private fun buildStatelessStartRunAsyncTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_run_async")
            .description("Start a new run and return immediately. This clears any previous runs. Use turbo://runs/{run_id} resource to poll for status and results.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "script": { "type": "string", "description": "Python script code that controls the run" },
                    "base_request": { "type": "string", "description": "The base HTTP request template with injection points marked as %s" },
                    "endpoint": { "type": "string", "description": "Target endpoint URL (e.g., https://example.com)" },
                    "base_input": { "type": "string", "description": "Input data to feed into the script (e.g., wordlist content)" },
                    "normalize_line_endings": { "type": "boolean", "description": "Whether to normalize mixed line endings (\\n and \\r\\n) to \\r\\n. Default: true" }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.startRunAsync(
                    sessionId = STATELESS_SESSION_ID,
                    script = args["script"] as? String ?: "",
                    baseRequest = args["base_request"] as? String ?: "",
                    endpoint = args["endpoint"] as? String ?: "",
                    baseInput = args["base_input"] as? String ?: "",
                    normalizeLineEndings = (args["normalize_line_endings"] as? Boolean) ?: true
                )
            }
        }
    }

    private fun buildStatelessStartConcurrentRunAsyncTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_concurrent_run")
            .description("Start a new concurrent run. Does not clear previous runs, allowing multiple runs to execute in parallel.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "script": { "type": "string", "description": "Python script code that controls the run" },
                    "base_request": { "type": "string", "description": "The base HTTP request template with injection points marked as %s" },
                    "endpoint": { "type": "string", "description": "Target endpoint URL (e.g., https://example.com)" },
                    "base_input": { "type": "string", "description": "Input data to feed into the script (e.g., wordlist content)" },
                    "normalize_line_endings": { "type": "boolean", "description": "Whether to normalize mixed line endings (\\n and \\r\\n) to \\r\\n. Default: true" }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.startConcurrentRunAsync(
                    sessionId = STATELESS_SESSION_ID,
                    script = args["script"] as? String ?: "",
                    baseRequest = args["base_request"] as? String ?: "",
                    endpoint = args["endpoint"] as? String ?: "",
                    baseInput = args["base_input"] as? String ?: "",
                    normalizeLineEndings = (args["normalize_line_endings"] as? Boolean) ?: true
                )
            }
        }
    }

    private fun buildStatelessStopRunTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("stop_run")
            .description("Stop the active run. Aborts the run but preserves the results.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "run_id": { "type": "string", "description": "ID of the run to stop. Omit or use 'current' for the most recent run." }
                }
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                toolHandlers.stopRun(STATELESS_SESSION_ID, request.arguments()["run_id"] as? String)
            }
        }
    }

    private fun buildStatelessDeleteRunTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("delete_run")
            .description("Delete a run and all its results. Also stops the run if it's still executing.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "run_id": { "type": "string", "description": "ID of the run to delete. Omit or use 'current' for the most recent run." }
                }
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                toolHandlers.deleteRun(STATELESS_SESSION_ID, request.arguments()["run_id"] as? String)
            }
        }
    }

    private fun buildStatelessDeleteAllRunsTool(): McpStatelessServerFeatures.SyncToolSpecification {
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

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, _ ->
            executeToolWithErrorHandling {
                toolHandlers.deleteAllRuns(STATELESS_SESSION_ID)
            }
        }
    }

    private fun buildStatelessSetOrganizerNotesTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("set_organizer_notes")
            .description("Update the notes on an Organizer item.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "id": { "type": "integer", "description": "The Organizer item ID" },
                    "notes": { "type": "string", "description": "The new notes content" }
                },
                "required": ["id", "notes"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.setOrganizerNotes(
                    id = (args["id"] as? Number)?.toInt() ?: 0,
                    notes = args["notes"] as? String ?: ""
                )
            }
        }
    }

    private fun buildStatelessSaveToOrganizerTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("save_to_organizer")
            .description("Save requests from a run to Burp's Organizer with custom notes.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "run_id": { "type": "string", "description": "ID of the run to save requests from. Omit for current run." },
                    "items": { "type": "string", "description": "JSON array of objects with request_id (int) and notes (string)" }
                },
                "required": ["items"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.saveToOrganizer(
                    sessionId = STATELESS_SESSION_ID,
                    runId = args["run_id"] as? String,
                    items = args["items"] as? String ?: "[]"
                )
            }
        }
    }

    private fun buildStatelessGenerateCollaboratorPayloadTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("generate_collaborator_payload")
            .description("Generate a Burp Collaborator payload for out-of-band testing. Returns a unique domain that will capture DNS/HTTP/SMTP interactions.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "metadata": { "type": "string", "description": "Description of what this payload tests for (e.g., 'SSRF in webhook URL parameter')" }
                },
                "required": ["metadata"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.generateCollaboratorPayload(
                    metadata = args["metadata"] as? String ?: ""
                )
            }
        }
    }

    private fun buildStatelessGetCollaboratorInteractionsTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("get_collaborator_interactions")
            .description("Retrieve Collaborator interactions (DNS lookups, HTTP requests, etc.) for generated payloads. Returns interaction details including type, timestamp, client IP, and protocol-specific data.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "payloads": { "type": "array", "items": { "type": "string" }, "description": "Filter to specific payload domains. Omit to get all interactions from this session." }
                }
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                @Suppress("UNCHECKED_CAST")
                val payloads = args["payloads"] as? List<String>
                toolHandlers.getCollaboratorInteractions(payloads)
            }
        }
    }

    private fun buildStatelessSearchResponsesTool(): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("search_responses")
            .description("Search all responses in a run for a specific string. Returns IDs of requests whose responses contain the search string.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "query": { "type": "string", "description": "The string to search for in response bodies" },
                    "run_id": { "type": "string", "description": "ID of the run to search. Omit for current run." }
                },
                "required": ["query"]
            }
            """.trimIndent())
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification(tool) { _, request ->
            executeToolWithErrorHandling {
                val args = request.arguments()
                toolHandlers.searchResponses(
                    sessionId = STATELESS_SESSION_ID,
                    runId = args["run_id"] as? String,
                    query = args["query"] as? String ?: ""
                )
            }
        }
    }

    fun getEnabledToolNames(): Set<String> {
        return allTools
            .filter { it.tool().name() !in disabledTools }
            .map { it.tool().name() }
            .toSet()
    }

    private fun buildStartRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_run")
            .description("Start a new run and wait for completion. This clears any previous runs and starts fresh. Returns results when complete or on timeout.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "script": {
                        "type": "string",
                        "description": "Python script code that controls the run"
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
                    },
                    "timeout_ms": {
                        "type": "integer",
                        "description": "Timeout in milliseconds (default: 60000). If exceeded, returns run_id for manual polling."
                    },
                    "normalize_line_endings": {
                        "type": "boolean",
                        "description": "Whether to normalize mixed line endings (\\n and \\r\\n) to \\r\\n. Default: true"
                    }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.startRun(
                        sessionId = exchange.sessionId(),
                        script = args["script"] as? String ?: "",
                        baseRequest = args["base_request"] as? String ?: "",
                        endpoint = args["endpoint"] as? String ?: "",
                        baseInput = args["base_input"] as? String ?: "",
                        timeoutMs = (args["timeout_ms"] as? Number)?.toLong() ?: 60000,
                        normalizeLineEndings = (args["normalize_line_endings"] as? Boolean) ?: true
                    )
                }
            }
            .build()
    }

    private fun buildStartRunAsyncTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_run_async")
            .description("Start a new run and return immediately. This clears any previous runs. Use turbo://runs/{run_id} resource to poll for status and results.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "script": {
                        "type": "string",
                        "description": "Python script code that controls the run"
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
                    },
                    "normalize_line_endings": {
                        "type": "boolean",
                        "description": "Whether to normalize mixed line endings (\\n and \\r\\n) to \\r\\n. Default: true"
                    }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.startRunAsync(
                        sessionId = exchange.sessionId(),
                        script = args["script"] as? String ?: "",
                        baseRequest = args["base_request"] as? String ?: "",
                        endpoint = args["endpoint"] as? String ?: "",
                        baseInput = args["base_input"] as? String ?: "",
                        normalizeLineEndings = (args["normalize_line_endings"] as? Boolean) ?: true
                    )
                }
            }
            .build()
    }

    private fun buildStartConcurrentRunAsyncTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("start_concurrent_run")
            .description("Start a new concurrent run. Does not clear previous runs, allowing multiple runs to execute in parallel.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "script": {
                        "type": "string",
                        "description": "Python script code that controls the run"
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
                    },
                    "normalize_line_endings": {
                        "type": "boolean",
                        "description": "Whether to normalize mixed line endings (\\n and \\r\\n) to \\r\\n. Default: true"
                    }
                },
                "required": ["script", "base_request", "endpoint"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.startConcurrentRunAsync(
                        sessionId = exchange.sessionId(),
                        script = args["script"] as? String ?: "",
                        baseRequest = args["base_request"] as? String ?: "",
                        endpoint = args["endpoint"] as? String ?: "",
                        baseInput = args["base_input"] as? String ?: "",
                        normalizeLineEndings = (args["normalize_line_endings"] as? Boolean) ?: true
                    )
                }
            }
            .build()
    }

    private fun buildStopRunTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("stop_run")
            .description("Stop the active run. Aborts the run but preserves the results.")
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
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    toolHandlers.stopRun(exchange.sessionId(), request.arguments()["run_id"] as? String)
                }
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
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    toolHandlers.deleteRun(exchange.sessionId(), request.arguments()["run_id"] as? String)
                }
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
            .callHandler { exchange, _ ->
                executeToolWithErrorHandling {
                    toolHandlers.deleteAllRuns(exchange.sessionId())
                }
            }
            .build()
    }

    private fun buildSetOrganizerNotesTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("set_organizer_notes")
            .description("Update the notes on an Organizer item.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "id": {
                        "type": "integer",
                        "description": "The Organizer item ID"
                    },
                    "notes": {
                        "type": "string",
                        "description": "The new notes content"
                    }
                },
                "required": ["id", "notes"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.setOrganizerNotes(
                        id = (args["id"] as? Number)?.toInt() ?: 0,
                        notes = args["notes"] as? String ?: ""
                    )
                }
            }
            .build()
    }

    private fun buildSaveToOrganizerTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("save_to_organizer")
            .description("Save requests from a run to Burp's Organizer with custom notes.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "run_id": {
                        "type": "string",
                        "description": "ID of the run to save requests from. Omit for current run."
                    },
                    "items": {
                        "type": "string",
                        "description": "JSON array of objects with request_id (int) and notes (string)"
                    }
                },
                "required": ["items"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.saveToOrganizer(
                        sessionId = exchange.sessionId(),
                        runId = args["run_id"] as? String,
                        items = args["items"] as? String ?: "[]"
                    )
                }
            }
            .build()
    }

    private fun buildGenerateCollaboratorPayloadTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("generate_collaborator_payload")
            .description("Generate a Burp Collaborator payload for out-of-band testing. Returns a unique domain that will capture DNS/HTTP/SMTP interactions.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "metadata": {
                        "type": "string",
                        "description": "Description of what this payload tests for (e.g., 'SSRF in webhook URL parameter')"
                    }
                },
                "required": ["metadata"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.generateCollaboratorPayload(
                        metadata = args["metadata"] as? String ?: ""
                    )
                }
            }
            .build()
    }

    private fun buildGetCollaboratorInteractionsTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("get_collaborator_interactions")
            .description("Retrieve Collaborator interactions (DNS lookups, HTTP requests, etc.) for generated payloads. Returns interaction details including type, timestamp, client IP, and protocol-specific data.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "payloads": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "Filter to specific payload domains. Omit to get all interactions from this session."
                    }
                }
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { _, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    @Suppress("UNCHECKED_CAST")
                    val payloads = args["payloads"] as? List<String>
                    toolHandlers.getCollaboratorInteractions(payloads)
                }
            }
            .build()
    }

    private fun buildSearchResponsesTool(): McpServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name("search_responses")
            .description("Search all responses in a run for a specific string. Returns IDs of requests whose responses contain the search string.")
            .inputSchema(jsonMapper, """
            {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The string to search for in response bodies"
                    },
                    "run_id": {
                        "type": "string",
                        "description": "ID of the run to search. Omit for current run."
                    }
                },
                "required": ["query"]
            }
            """.trimIndent())
            .build()

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { exchange, request ->
                executeToolWithErrorHandling {
                    val args = request.arguments()
                    toolHandlers.searchResponses(
                        sessionId = exchange.sessionId(),
                        runId = args["run_id"] as? String,
                        query = args["query"] as? String ?: ""
                    )
                }
            }
            .build()
    }

    // Stateless resource builders

    private fun buildStatelessResourceSpecifications(): List<McpStatelessServerFeatures.SyncResourceSpecification> {
        return listOf(
            buildStatelessRunsListResource(),
            buildStatelessRunStatusResourceTemplate(),
            buildStatelessRunResultsResourceTemplate(),
            buildStatelessRequestDetailResourceTemplate(),
            buildStatelessShorthandRequestDetailResourceTemplate(),
            buildStatelessOrganizerListResource(),
            buildStatelessOrganizerByDomainResourceTemplate(),
            buildStatelessOrganizerItemResourceTemplate(),
            buildStatelessDocsListResource(),
            buildStatelessDocTopicResourceTemplate()
        )
    }

    private fun buildStatelessRunsListResource(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs")
            .name("List of all runs")
            .description("List all runs with their status and result counts")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val result = resourceHandlers.listRuns(STATELESS_SESSION_ID)
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    "turbo://runs",
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildStatelessRunStatusResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs/{run_id}")
            .name("Status of a specific run")
            .description("Get detailed status of a specific run including running state, result count, and status message. Use 'current' for the most recent run.")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val runId = resourceHandlers.parseRunId(request.uri())
            val result = resourceHandlers.getRunStatus(STATELESS_SESSION_ID, runId)
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildStatelessRunResultsResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs/{run_id}/summary")
            .name("Summary from a run")
            .description("Get paginated summary from a run. Supports query params: sort_by (id|status|length|time|wordcount), descending (true|false), limit, offset")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val params = resourceHandlers.parseQueryParams(uri)
            val result = resourceHandlers.getResults(
                sessionId = STATELESS_SESSION_ID,
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

    private fun buildStatelessRequestDetailResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs/{run_id}/results/{id}")
            .name("Details of a specific result")
            .description("Get request and response details for a result. Supports query params: body_limit (default 100, chars of body to include), export=file (write to temp files and return paths)")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val requestId = resourceHandlers.parseRequestId(uri) ?: -1
            val params = resourceHandlers.parseQueryParams(uri)
            val result = resourceHandlers.getRequestDetail(
                sessionId = STATELESS_SESSION_ID,
                runId = runId,
                requestId = requestId,
                bodyLimit = params["body_limit"]?.toIntOrNull() ?: 100,
                exportFile = params["export"] == "file"
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

    private fun buildStatelessShorthandRequestDetailResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://results/{id}")
            .name("Details of a specific result (shorthand)")
            .description("Shorthand for turbo://runs/current/results/{id}. Get request and response details from the current run.")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val result = resourceHandlers.handleResourceRead(STATELESS_SESSION_ID, request.uri())
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildStatelessOrganizerListResource(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://organizer")
            .name("List of all Organizer items")
            .description("List all items in Burp's Organizer with their IDs")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val result = resourceHandlers.listOrganizerItems()
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    "turbo://organizer",
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildStatelessOrganizerByDomainResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://organizer/by-domain/{domain}")
            .name("Organizer items filtered by domain")
            .description("List Organizer items for a specific domain, paginated (10 per page). Add ?page=N for pagination.")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val domain = Regex("turbo://organizer/by-domain/([^/?]+)").find(request.uri())?.groupValues?.get(1)
            val params = resourceHandlers.parseQueryParams(request.uri())
            val result = resourceHandlers.listOrganizerItems(
                domain = domain,
                page = params["page"]?.toIntOrNull() ?: 1
            )
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildStatelessOrganizerItemResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://organizer/{id}")
            .name("Details of an Organizer item")
            .description("Get the full request, response, and notes for an Organizer item by ID")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val organizerId = resourceHandlers.parseOrganizerId(request.uri())
            val params = resourceHandlers.parseQueryParams(request.uri())
            val bodyLimit = params["body_limit"]?.toIntOrNull() ?: 100
            val result = if (organizerId != null) {
                resourceHandlers.getOrganizerItem(organizerId, bodyLimit)
            } else {
                mapOf("error" to "invalid_organizer_id")
            }
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildStatelessDocsListResource(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://docs")
            .name("Documentation topics")
            .description("List available documentation topics for scripting reference")
            .mimeType("application/json")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, _ ->
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

    private fun buildStatelessDocTopicResourceTemplate(): McpStatelessServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://docs/{topic}")
            .name("Documentation for a specific topic")
            .description("Get documentation content. Topics: api-quickstart, engines, settings, race-conditions, response-processing, decorators, misc")
            .mimeType("text/markdown")
            .build()

        return McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
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

    private fun buildResourceSpecifications(): List<McpServerFeatures.SyncResourceSpecification> {
        return listOf(
            buildRunsListResource(),
            buildRunStatusResourceTemplate(),
            buildRunResultsResourceTemplate(),
            buildRequestDetailResourceTemplate(),
            buildShorthandRequestDetailResourceTemplate(),
            buildOrganizerListResource(),
            buildOrganizerByDomainResourceTemplate(),
            buildOrganizerItemResourceTemplate(),
            buildDocsListResource(),
            buildDocTopicResourceTemplate()
        )
    }

    private fun buildRunsListResource(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://runs")
            .name("List of all runs")
            .description("List all runs with their status and result counts")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { exchange, _ ->
            val result = resourceHandlers.listRuns(exchange.sessionId())
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

        return McpServerFeatures.SyncResourceSpecification(resource) { exchange, request ->
            val runId = resourceHandlers.parseRunId(request.uri())
            val result = resourceHandlers.getRunStatus(exchange.sessionId(), runId)
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
            .uri("turbo://runs/{run_id}/summary")
            .name("Summary from a run")
            .description("Get paginated summary from a run. Supports query params: sort_by (id|status|length|time|wordcount), descending (true|false), limit, offset")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { exchange, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val params = resourceHandlers.parseQueryParams(uri)
            val result = resourceHandlers.getResults(
                sessionId = exchange.sessionId(),
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
            .uri("turbo://runs/{run_id}/results/{id}")
            .name("Details of a specific result")
            .description("Get request and response details for a result. Supports query params: body_limit (default 100, chars of body to include), export=file (write to temp files and return paths)")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { exchange, request ->
            val uri = request.uri()
            val runId = resourceHandlers.parseRunId(uri)
            val requestId = resourceHandlers.parseRequestId(uri) ?: -1
            val params = resourceHandlers.parseQueryParams(uri)
            val result = resourceHandlers.getRequestDetail(
                sessionId = exchange.sessionId(),
                runId = runId,
                requestId = requestId,
                bodyLimit = params["body_limit"]?.toIntOrNull() ?: 100,
                exportFile = params["export"] == "file"
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

    private fun buildShorthandRequestDetailResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://results/{id}")
            .name("Details of a specific result (shorthand)")
            .description("Shorthand for turbo://runs/current/results/{id}. Get request and response details from the current run.")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { exchange, request ->
            val result = resourceHandlers.handleResourceRead(exchange.sessionId(), request.uri())
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildOrganizerListResource(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://organizer")
            .name("List of all Organizer items")
            .description("List all items in Burp's Organizer with their IDs")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, _ ->
            val result = resourceHandlers.listOrganizerItems()
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    "turbo://organizer",
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildOrganizerByDomainResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://organizer/by-domain/{domain}")
            .name("Organizer items filtered by domain")
            .description("List Organizer items for a specific domain, paginated (10 per page). Add ?page=N for pagination.")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val domain = Regex("turbo://organizer/by-domain/([^/?]+)").find(request.uri())?.groupValues?.get(1)
            val params = resourceHandlers.parseQueryParams(request.uri())
            val result = resourceHandlers.listOrganizerItems(
                domain = domain,
                page = params["page"]?.toIntOrNull() ?: 1
            )
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildOrganizerItemResourceTemplate(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://organizer/{id}")
            .name("Details of an Organizer item")
            .description("Get the full request, response, and notes for an Organizer item by ID")
            .mimeType("application/json")
            .build()

        return McpServerFeatures.SyncResourceSpecification(resource) { _, request ->
            val organizerId = resourceHandlers.parseOrganizerId(request.uri())
            val params = resourceHandlers.parseQueryParams(request.uri())
            val bodyLimit = params["body_limit"]?.toIntOrNull() ?: 100
            val result = if (organizerId != null) {
                resourceHandlers.getOrganizerItem(organizerId, bodyLimit)
            } else {
                mapOf("error" to "invalid_organizer_id")
            }
            McpSchema.ReadResourceResult(
                listOf(McpSchema.TextResourceContents(
                    request.uri(),
                    "application/json",
                    jsonMapper.writeValueAsString(result)
                ))
            )
        }
    }

    private fun buildDocsListResource(): McpServerFeatures.SyncResourceSpecification {
        val resource = McpSchema.Resource.builder()
            .uri("turbo://docs")
            .name("Documentation topics")
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

/**
 * Filter that validates the Host header to prevent DNS rebinding attacks.
 * Only allows requests with Host header set to localhost or 127.0.0.1.
 */
private class HostValidationFilter : Filter {
    companion object {
        private val ALLOWED_HOSTS = setOf(
            "localhost",
            "127.0.0.1"
        )
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        val host = httpRequest.getHeader("Host")?.lowercase()?.substringBefore(":") ?: ""

        if (host in ALLOWED_HOSTS) {
            chain.doFilter(request, response)
        } else {
            httpResponse.status = HttpServletResponse.SC_FORBIDDEN
            httpResponse.writer.write("Forbidden: Invalid Host header")
        }
    }
}

/**
 * Filter that logs all MCP requests and errors to a file for debugging session issues.
 */
private class McpDebugLoggingFilter : Filter {
    companion object {
        private val logFile = java.io.File("/tmp/mcp-debug.log")

        @Synchronized
        fun log(message: String) {
            logFile.appendText("$message\n")
        }
    }

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val httpResponse = response as HttpServletResponse

        val sessionId = httpRequest.getHeader("mcp-session-id") ?: "no-session"
        val method = httpRequest.method
        val timestamp = java.time.Instant.now()

        log("[$timestamp] $method request, session: $sessionId")

        try {
            chain.doFilter(request, response)
            log("[$timestamp] $method completed, status: ${httpResponse.status}, session: $sessionId")
        } catch (e: Exception) {
            log("[$timestamp] ERROR in $method, session: $sessionId")
            log("[$timestamp] Exception: ${e.javaClass.name}: ${e.message}")
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            log(sw.toString())
            throw e
        }
    }
}
