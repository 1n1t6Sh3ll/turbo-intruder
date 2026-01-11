package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class McpResourceHandlersTest {

    private lateinit var manager: RunManager
    private lateinit var handlers: McpResourceHandlers

    @BeforeEach
    fun setup() {
        manager = RunManager()
        handlers = McpResourceHandlers(manager)
    }

    @Test
    fun `listRuns returns empty when no runs`() {
        val result = handlers.listRuns()

        assertTrue((result["runs"] as List<*>).isEmpty())
    }

    @Test
    fun `listRuns returns all runs`() {
        manager.startConcurrentRun()
        manager.startConcurrentRun()

        val result = handlers.listRuns()
        val runs = result["runs"] as List<*>

        assertEquals(2, runs.size)
    }

    @Test
    fun `getRunStatus returns error for no current run`() {
        val result = handlers.getRunStatus(null)

        assertEquals("no_current_run", result["error"])
    }

    @Test
    fun `getRunStatus returns run info`() {
        manager.startRun()

        val result = handlers.getRunStatus(null)

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
    fun `getRequestDetail returns error for invalid request`() {
        manager.startRun()

        val result = handlers.getRequestDetail(null, 999)

        assertEquals("request_not_found", result["error"])
    }

    @Test
    fun `parseUri extracts run_id correctly`() {
        assertEquals("abc123", handlers.parseRunId("turbo://runs/abc123"))
        assertEquals("abc123", handlers.parseRunId("turbo://runs/abc123/summary"))
        assertEquals("current", handlers.parseRunId("turbo://runs/current"))
        assertNull(handlers.parseRunId("turbo://runs"))
    }

    @Test
    fun `parseUri extracts request_id correctly`() {
        assertEquals(42, handlers.parseRequestId("turbo://runs/abc123/requests/42"))
        assertNull(handlers.parseRequestId("turbo://runs/abc123/summary"))
    }

    @Test
    fun `parseQueryParams extracts parameters`() {
        val params = handlers.parseQueryParams("turbo://runs/abc/summary?sort_by=status&limit=50")

        assertEquals("status", params["sort_by"])
        assertEquals("50", params["limit"])
    }

    @Test
    fun `getRequestDetail returns headers and truncated body by default`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
            "A".repeat(500)  // 500-char body
        run.store.add(request)

        val result = handlers.getRequestDetail(null, 1, bodyLimit = 100)

        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/html", result["response_headers"])
        assertEquals("A".repeat(100), result["response_body"])
        assertEquals(200, result["status"])
    }

    @Test
    fun `getRequestDetail respects custom body_limit`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\n" + "B".repeat(1000)
        run.store.add(request)

        val result = handlers.getRequestDetail(null, 1, bodyLimit = 250)

        assertEquals("B".repeat(250), result["response_body"])
    }

    @Test
    fun `getRequestDetail returns full body when shorter than limit`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nshort"
        run.store.add(request)

        val result = handlers.getRequestDetail(null, 1, bodyLimit = 100)

        assertEquals("short", result["response_body"])
    }

    @Test
    fun `getRequestDetail with exportFile writes response to file and returns path`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        val fullResponse = "HTTP/1.1 200 OK\r\n\r\n" + "X".repeat(1000)
        request.response = fullResponse
        run.store.add(request)

        val result = handlers.getRequestDetail(null, 1, exportFile = true)

        assertNotNull(result["response_file"])
        val filePath = result["response_file"] as String
        val fileContent = java.io.File(filePath).readText()
        assertEquals(fullResponse, fileContent)
        // Should not include inline body when exporting to file
        assertNull(result["response_body"])
    }

    @Test
    fun `getRequestDetail with exportFile also exports request`() {
        val run = manager.startRun()
        val request = burp.Request("GET /test HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nok"
        run.store.add(request)

        val result = handlers.getRequestDetail(null, 1, exportFile = true)

        assertNotNull(result["request_file"])
        val filePath = result["request_file"] as String
        val fileContent = java.io.File(filePath).readText()
        assertTrue(fileContent.contains("GET /test HTTP/1.1"))
    }

    @Test
    fun `handleResourceRead parses body_limit from URI`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\n" + "Z".repeat(500)
        run.store.add(request)

        val result = handlers.handleResourceRead("turbo://runs/current/requests/1?body_limit=50")

        assertEquals("Z".repeat(50), result["response_body"])
    }

    @Test
    fun `handleResourceRead parses export param from URI`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\ndata"
        run.store.add(request)

        val result = handlers.handleResourceRead("turbo://runs/current/requests/1?export=file")

        assertNotNull(result["response_file"])
        assertNull(result["response_body"])
    }
}
