# MCP Server Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add an MCP server to Turbo Intruder enabling Claude Code to start/stop runs and query results.

**Architecture:** HTTP server on localhost:31337 using MCP Java SDK. RunManager handles multiple concurrent runs. Tools expose run lifecycle and result querying.

**Tech Stack:** MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`), Kotlin, JUnit 5

---

## Task 1: Add MCP SDK Dependency

**Files:**
- Modify: `build.gradle`

**Step 1: Add the MCP SDK dependency**

```gradle
dependencies {
    // ... existing dependencies ...

    // MCP Server
    implementation 'io.modelcontextprotocol.sdk:mcp:0.10.0'
}
```

**Step 2: Sync gradle and verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add build.gradle
git commit -m "chore: add MCP Java SDK dependency"
```

---

## Task 2: Create ActiveRun Data Class

**Files:**
- Create: `src/mcp/ActiveRun.kt`
- Test: `test/kotlin/mcp/ActiveRunTest.kt`

**Step 1: Write the test**

```kotlin
package mcp

import burp.ResultStore
import burp.RunHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ActiveRunTest {

    @Test
    fun `creates ActiveRun with unique id`() {
        val run1 = ActiveRun()
        val run2 = ActiveRun()

        assertNotNull(run1.id)
        assertNotNull(run2.id)
        assertNotEquals(run1.id, run2.id)
    }

    @Test
    fun `provides access to RunHandler and ResultStore`() {
        val run = ActiveRun()

        assertNotNull(run.handler)
        assertNotNull(run.store)
    }

    @Test
    fun `tracks creation time`() {
        val before = System.currentTimeMillis()
        val run = ActiveRun()
        val after = System.currentTimeMillis()

        assertTrue(run.createdAt >= before)
        assertTrue(run.createdAt <= after)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "mcp.ActiveRunTest"`
Expected: FAIL - class not found

**Step 3: Write minimal implementation**

```kotlin
package mcp

import burp.ResultStore
import burp.RunHandler
import java.util.UUID

class ActiveRun {
    val id: String = UUID.randomUUID().toString()
    val handler: RunHandler = RunHandler()
    val store: ResultStore = ResultStore()
    val createdAt: Long = System.currentTimeMillis()
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "mcp.ActiveRunTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add src/mcp/ActiveRun.kt test/kotlin/mcp/ActiveRunTest.kt
git commit -m "feat(mcp): add ActiveRun data class"
```

---

## Task 3: Create RunManager

**Files:**
- Create: `src/mcp/RunManager.kt`
- Test: `test/kotlin/mcp/RunManagerTest.kt`

**Step 1: Write the test**

```kotlin
package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class RunManagerTest {

    private lateinit var manager: RunManager

    @BeforeEach
    fun setup() {
        manager = RunManager()
    }

    @Test
    fun `startRun clears existing runs and creates new current run`() {
        val run1 = manager.startConcurrentRun()
        val run2 = manager.startRun()

        assertNull(manager.getRun(run1.id))
        assertNotNull(manager.currentRun)
        assertEquals(run2.id, manager.currentRun?.id)
    }

    @Test
    fun `startConcurrentRun preserves existing runs`() {
        val run1 = manager.startConcurrentRun()
        val run2 = manager.startConcurrentRun()

        assertNotNull(manager.getRun(run1.id))
        assertNotNull(manager.getRun(run2.id))
    }

    @Test
    fun `getRun with null returns current run`() {
        val run = manager.startRun()

        assertEquals(run.id, manager.getRun(null)?.id)
    }

    @Test
    fun `getRun with id returns specific run`() {
        val run1 = manager.startConcurrentRun()
        val run2 = manager.startConcurrentRun()

        assertEquals(run1.id, manager.getRun(run1.id)?.id)
        assertEquals(run2.id, manager.getRun(run2.id)?.id)
    }

    @Test
    fun `stopRun aborts the run handler`() {
        val run = manager.startRun()

        val result = manager.stopRun(null)

        assertEquals("stopped", result)
    }

    @Test
    fun `stopRun returns not_found for unknown id`() {
        val result = manager.stopRun("unknown-id")

        assertEquals("not_found", result)
    }

    @Test
    fun `deleteRun removes run from manager`() {
        val run = manager.startConcurrentRun()

        val result = manager.deleteRun(run.id)

        assertEquals("deleted", result)
        assertNull(manager.getRun(run.id))
    }

    @Test
    fun `deleteAllRuns clears everything`() {
        manager.startConcurrentRun()
        manager.startConcurrentRun()
        manager.startRun()

        val count = manager.deleteAllRuns()

        assertEquals(3, count)
        assertNull(manager.currentRun)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "mcp.RunManagerTest"`
Expected: FAIL - class not found

**Step 3: Write implementation**

```kotlin
package mcp

import java.util.concurrent.ConcurrentHashMap

class RunManager {
    private val runs = ConcurrentHashMap<String, ActiveRun>()
    var currentRun: ActiveRun? = null
        private set

    fun startRun(): ActiveRun {
        deleteAllRuns()
        val run = ActiveRun()
        runs[run.id] = run
        currentRun = run
        return run
    }

    fun startConcurrentRun(): ActiveRun {
        val run = ActiveRun()
        runs[run.id] = run
        currentRun = run
        return run
    }

    fun getRun(runId: String?): ActiveRun? {
        return if (runId == null) currentRun else runs[runId]
    }

    fun stopRun(runId: String?): String {
        val run = getRun(runId) ?: return if (runId == null) "no_current_run" else "not_found"
        run.handler.abort()
        return "stopped"
    }

    fun deleteRun(runId: String?): String {
        val run = getRun(runId) ?: return if (runId == null) "no_current_run" else "not_found"
        run.handler.abort()
        runs.remove(run.id)
        if (currentRun?.id == run.id) {
            currentRun = null
        }
        return "deleted"
    }

    fun deleteAllRuns(): Int {
        val count = runs.size
        runs.values.forEach { it.handler.abort() }
        runs.clear()
        currentRun = null
        return count
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "mcp.RunManagerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add src/mcp/RunManager.kt test/kotlin/mcp/RunManagerTest.kt
git commit -m "feat(mcp): add RunManager for multi-run support"
```

---

## Task 4: Create McpToolHandlers

**Files:**
- Create: `src/mcp/McpToolHandlers.kt`
- Test: `test/kotlin/mcp/McpToolHandlersTest.kt`

**Step 1: Write the test for get_status**

```kotlin
package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class McpToolHandlersTest {

    private lateinit var manager: RunManager
    private lateinit var handlers: McpToolHandlers

    @BeforeEach
    fun setup() {
        manager = RunManager()
        handlers = McpToolHandlers(manager)
    }

    @Test
    fun `getStatus returns no_current_run when no run exists`() {
        val result = handlers.getStatus(null)

        assertEquals("no_current_run", result["error"])
    }

    @Test
    fun `getStatus returns run info for current run`() {
        manager.startRun()

        val result = handlers.getStatus(null)

        assertNotNull(result["run_id"])
        assertNotNull(result["running"])
        assertNotNull(result["finished"])
        assertNotNull(result["result_count"])
    }

    @Test
    fun `getResults returns empty list when no results`() {
        manager.startRun()

        val result = handlers.getResults(null, "id", true, 100, 0)

        assertEquals(0, result["total_count"])
        assertTrue((result["results"] as List<*>).isEmpty())
    }

    @Test
    fun `deleteAllRuns returns count`() {
        manager.startConcurrentRun()
        manager.startConcurrentRun()

        val result = handlers.deleteAllRuns()

        assertEquals(2, result["deleted_count"])
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "mcp.McpToolHandlersTest"`
Expected: FAIL - class not found

**Step 3: Write implementation**

```kotlin
package mcp

import burp.SortField

class McpToolHandlers(private val manager: RunManager) {

    fun getStatus(runId: String?): Map<String, Any?> {
        val run = manager.getRun(runId)
            ?: return mapOf("error" to if (runId == null) "no_current_run" else "not_found")

        return mapOf(
            "run_id" to run.id,
            "running" to run.handler.isRunning(),
            "finished" to run.handler.hasFinished(),
            "status_message" to run.handler.statusString(),
            "result_count" to run.store.count()
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
            ?: return mapOf("error" to if (runId == null) "no_current_run" else "not_found")

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
            ?: return mapOf("error" to if (runId == null) "no_current_run" else "not_found")

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

    fun stopRun(runId: String?): Map<String, String> {
        return mapOf("status" to manager.stopRun(runId))
    }

    fun deleteRun(runId: String?): Map<String, String> {
        return mapOf("status" to manager.deleteRun(runId))
    }

    fun deleteAllRuns(): Map<String, Int> {
        return mapOf("deleted_count" to manager.deleteAllRuns())
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "mcp.McpToolHandlersTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add src/mcp/McpToolHandlers.kt test/kotlin/mcp/McpToolHandlersTest.kt
git commit -m "feat(mcp): add tool handlers for status, results, delete"
```

---

## Task 5: Add Start Run Handlers

**Files:**
- Modify: `src/mcp/McpToolHandlers.kt`
- Modify: `test/kotlin/mcp/McpToolHandlersTest.kt`

**Step 1: Add test for startRun**

Add to `McpToolHandlersTest.kt`:

```kotlin
@Test
fun `startRun creates new run and returns status`() {
    val result = handlers.startRun(
        script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
        baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        endpoint = "https://example.com:443",
        baseInput = ""
    )

    assertEquals("started", result["status"])
    assertNotNull(manager.currentRun)
}

@Test
fun `startConcurrentRun preserves existing runs`() {
    handlers.startRun(
        script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
        baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        endpoint = "https://example.com:443",
        baseInput = ""
    )
    val firstRunId = manager.currentRun?.id

    val result = handlers.startConcurrentRun(
        script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
        baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        endpoint = "https://example.com:443",
        baseInput = ""
    )

    assertEquals("started", result["status"])
    assertNotNull(result["run_id"])
    assertNotNull(manager.getRun(firstRunId))
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "mcp.McpToolHandlersTest"`
Expected: FAIL - method not found

**Step 3: Add implementation**

Add to `McpToolHandlers.kt`:

```kotlin
import burp.evalJython
import kotlin.concurrent.thread

fun startRun(
    script: String,
    baseRequest: String,
    endpoint: String,
    baseInput: String
): Map<String, Any?> {
    val run = manager.startRun()
    launchRun(run, script, baseRequest, endpoint, baseInput)
    return mapOf("status" to "started")
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

    thread {
        evalJython(
            code = script,
            baseRequest = baseRequest,
            rawRequest = baseRequest.toByteArray(Charsets.ISO_8859_1),
            endpoint = endpoint,
            host = host,
            baseInput = baseInput,
            store = run.store,
            handler = run.handler,
            reqs = null,
            requestTable = null
        )
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "mcp.McpToolHandlersTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add src/mcp/McpToolHandlers.kt test/kotlin/mcp/McpToolHandlersTest.kt
git commit -m "feat(mcp): add startRun and startConcurrentRun handlers"
```

---

## Task 6: Create TurboMcpServer

**Files:**
- Create: `src/mcp/TurboMcpServer.kt`
- Test: `test/kotlin/mcp/TurboMcpServerTest.kt`

**Step 1: Write test for server creation**

```kotlin
package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TurboMcpServerTest {

    @Test
    fun `server can be created with port`() {
        val server = TurboMcpServer(port = 31337)
        assertNotNull(server)
    }

    @Test
    fun `server exposes tool handlers`() {
        val server = TurboMcpServer(port = 31337)
        assertNotNull(server.handlers)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "mcp.TurboMcpServerTest"`
Expected: FAIL - class not found

**Step 3: Write implementation**

```kotlin
package mcp

import burp.Utils
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.ServerOptions
import io.modelcontextprotocol.spec.McpSchema.*
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class TurboMcpServer(private val port: Int = 31337) {

    private val manager = RunManager()
    val handlers = McpToolHandlers(manager)
    private var httpServer: HttpServer? = null
    private var mcpServer: McpSyncServer? = null

    fun start() {
        try {
            httpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)

            val serverInfo = Implementation("turbo-intruder", "1.0.0")
            val capabilities = ServerCapabilities.builder()
                .tools(true)
                .build()

            mcpServer = McpServer.sync(createTransport())
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(createToolSpecs())
                .build()

            httpServer?.start()
            Utils.out("MCP server started on localhost:$port")
        } catch (e: Exception) {
            Utils.err("Failed to start MCP server: ${e.message}")
        }
    }

    fun stop() {
        mcpServer?.close()
        httpServer?.stop(0)
        Utils.out("MCP server stopped")
    }

    private fun createTransport(): HttpServletSseServerTransport {
        return HttpServletSseServerTransport(ObjectMapper(), "/mcp")
    }

    private fun createToolSpecs(): List<McpServerFeatures.SyncToolSpecification> {
        return listOf(
            createStartRunTool(),
            createStartConcurrentRunTool(),
            createStopRunTool(),
            createGetStatusTool(),
            createGetResultsTool(),
            createGetRequestDetailTool(),
            createDeleteRunTool(),
            createDeleteAllRunsTool()
        )
    }

    // Tool creation methods will be added in next task
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "mcp.TurboMcpServerTest"`
Expected: PASS (basic instantiation)

**Step 5: Commit**

```bash
git add src/mcp/TurboMcpServer.kt test/kotlin/mcp/TurboMcpServerTest.kt
git commit -m "feat(mcp): add TurboMcpServer skeleton"
```

---

## Task 7: Implement MCP Tool Specifications

**Files:**
- Modify: `src/mcp/TurboMcpServer.kt`

**Step 1: Add tool specification methods**

Add to `TurboMcpServer.kt`:

```kotlin
private fun createStartRunTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "script" to mapOf("type" to "string", "description" to "Python script content"),
            "base_request" to mapOf("type" to "string", "description" to "HTTP request template"),
            "endpoint" to mapOf("type" to "string", "description" to "Target URL (e.g., https://example.com:443)"),
            "base_input" to mapOf("type" to "string", "description" to "Default value for first %s placeholder")
        ),
        "required" to listOf("script", "base_request", "endpoint")
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("start_run", "Start a new run, clearing any existing runs", schema)
    ) { args ->
        val result = handlers.startRun(
            script = args["script"] as String,
            baseRequest = args["base_request"] as String,
            endpoint = args["endpoint"] as String,
            baseInput = (args["base_input"] as? String) ?: ""
        )
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createStartConcurrentRunTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "script" to mapOf("type" to "string", "description" to "Python script content"),
            "base_request" to mapOf("type" to "string", "description" to "HTTP request template"),
            "endpoint" to mapOf("type" to "string", "description" to "Target URL"),
            "base_input" to mapOf("type" to "string", "description" to "Default value for first %s")
        ),
        "required" to listOf("script", "base_request", "endpoint")
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("start_concurrent_run", "Start a new run without clearing existing runs", schema)
    ) { args ->
        val result = handlers.startConcurrentRun(
            script = args["script"] as String,
            baseRequest = args["base_request"] as String,
            endpoint = args["endpoint"] as String,
            baseInput = (args["base_input"] as? String) ?: ""
        )
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createStopRunTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "run_id" to mapOf("type" to "string", "description" to "Run ID (optional, defaults to current run)")
        )
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("stop_run", "Stop a running run", schema)
    ) { args ->
        val result = handlers.stopRun(args["run_id"] as? String)
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createGetStatusTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "run_id" to mapOf("type" to "string", "description" to "Run ID (optional, defaults to current run)")
        )
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("get_status", "Get status of a run", schema)
    ) { args ->
        val result = handlers.getStatus(args["run_id"] as? String)
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createGetResultsTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "run_id" to mapOf("type" to "string", "description" to "Run ID (optional)"),
            "sort_by" to mapOf("type" to "string", "enum" to listOf("id", "status", "length", "time", "wordcount", "anomaly_rank", "arrival")),
            "descending" to mapOf("type" to "boolean", "default" to true),
            "limit" to mapOf("type" to "integer", "default" to 100),
            "offset" to mapOf("type" to "integer", "default" to 0)
        )
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("get_results", "Query results with sorting and pagination", schema)
    ) { args ->
        val result = handlers.getResults(
            runId = args["run_id"] as? String,
            sortBy = (args["sort_by"] as? String) ?: "id",
            descending = (args["descending"] as? Boolean) ?: true,
            limit = (args["limit"] as? Number)?.toInt() ?: 100,
            offset = (args["offset"] as? Number)?.toInt() ?: 0
        )
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createGetRequestDetailTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "run_id" to mapOf("type" to "string", "description" to "Run ID (optional)"),
            "request_id" to mapOf("type" to "integer", "description" to "Request ID to get details for")
        ),
        "required" to listOf("request_id")
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("get_request_detail", "Get full request/response for a specific result", schema)
    ) { args ->
        val result = handlers.getRequestDetail(
            runId = args["run_id"] as? String,
            requestId = (args["request_id"] as Number).toInt()
        )
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createDeleteRunTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "run_id" to mapOf("type" to "string", "description" to "Run ID (optional)")
        )
    )

    return McpServerFeatures.SyncToolSpecification(
        Tool("delete_run", "Delete a run and free memory", schema)
    ) { args ->
        val result = handlers.deleteRun(args["run_id"] as? String)
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}

private fun createDeleteAllRunsTool(): McpServerFeatures.SyncToolSpecification {
    val schema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

    return McpServerFeatures.SyncToolSpecification(
        Tool("delete_all_runs", "Delete all runs", schema)
    ) { _ ->
        val result = handlers.deleteAllRuns()
        CallToolResult(listOf(TextContent(ObjectMapper().writeValueAsString(result))))
    }
}
```

**Step 2: Run tests**

Run: `./gradlew test`
Expected: PASS

**Step 3: Commit**

```bash
git add src/mcp/TurboMcpServer.kt
git commit -m "feat(mcp): implement all tool specifications"
```

---

## Task 8: Integrate MCP Server into Main Entry Points

**Files:**
- Modify: `src/fast-http.kt`
- Modify: `src/BurpExtender.kt`

**Step 1: Add --mcp flag to standalone mode**

Modify `main()` in `fast-http.kt`:

```kotlin
fun main(args: Array<String>) {
    if (args.contains("--mcp")) {
        val server = mcp.TurboMcpServer(port = 31337)
        server.start()

        // Block until shutdown signal
        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop()
        })

        // Keep main thread alive
        Thread.currentThread().join()
        return
    }

    // ... existing CLI code ...
}
```

**Step 2: Start MCP server in Burp extension**

Modify `BurpExtender.kt` to add:

```kotlin
private var mcpServer: mcp.TurboMcpServer? = null

// In registerExtenderCallbacks():
mcpServer = mcp.TurboMcpServer(port = 31337)
mcpServer?.start()

// Register unload handler:
callbacks.registerExtensionStateListener(object : IExtensionStateListener {
    override fun extensionUnloaded() {
        mcpServer?.stop()
    }
})
```

**Step 3: Build and verify**

Run: `./gradlew fatJar`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/fast-http.kt src/BurpExtender.kt
git commit -m "feat(mcp): integrate MCP server into main and Burp extension"
```

---

## Task 9: Integration Test

**Files:**
- Create: `test/kotlin/mcp/McpIntegrationTest.kt`

**Step 1: Write integration test**

```kotlin
package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import java.net.HttpURLConnection
import java.net.URL

class McpIntegrationTest {

    private lateinit var server: TurboMcpServer

    @BeforeEach
    fun setup() {
        server = TurboMcpServer(port = 31338) // Use different port for tests
        server.start()
        Thread.sleep(500) // Wait for server to start
    }

    @AfterEach
    fun teardown() {
        server.stop()
    }

    @Test
    fun `server responds to HTTP requests`() {
        val url = URL("http://localhost:31338/mcp")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        // Should get some response (even if error, server is running)
        assertDoesNotThrow { connection.responseCode }
    }
}
```

**Step 2: Run integration test**

Run: `./gradlew test --tests "mcp.McpIntegrationTest"`
Expected: PASS

**Step 3: Commit**

```bash
git add test/kotlin/mcp/McpIntegrationTest.kt
git commit -m "test(mcp): add integration test for server"
```

---

## Task 10: Final Verification and Documentation

**Step 1: Run all tests**

Run: `./gradlew test`
Expected: All tests PASS

**Step 2: Build fat jar**

Run: `./gradlew fatJar`
Expected: BUILD SUCCESSFUL

**Step 3: Manual smoke test**

Run: `java -jar build/libs/turbo-intruder-all.jar --mcp`
Expected: "MCP server started on localhost:31337"

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat(mcp): complete MCP server implementation

Adds MCP server to Turbo Intruder with 8 tools:
- start_run / start_concurrent_run
- stop_run / get_status / get_results / get_request_detail
- delete_run / delete_all_runs

Works in both standalone (--mcp flag) and Burp extension modes.
HTTP transport on localhost:31337."
```

---

## Notes for Implementation

1. **MCP SDK API may differ** - The exact API calls may need adjustment based on the actual SDK version. Check `io.modelcontextprotocol.sdk` package structure.

2. **HTTP Server approach** - The plan uses `HttpServletSseServerTransport` but may need to adapt to use a simpler embedded server like NanoHTTPD if the SDK's servlet transport doesn't work standalone.

3. **Error handling** - Add try-catch around Jython execution in `launchRun` to capture and report errors properly.

4. **Thread safety** - `RunManager` uses `ConcurrentHashMap` but `currentRun` access may need synchronization.
