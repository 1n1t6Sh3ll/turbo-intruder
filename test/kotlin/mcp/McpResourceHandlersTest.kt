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

    @Test
    fun `getResults includes anomaly_rank in results`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nok"
        request.anomalyRank = 42
        run.store.add(request)

        val result = handlers.getResults(null, "id", true, 100, 0)

        val results = result["results"] as List<Map<String, Any?>>
        assertEquals(1, results.size)
        assertEquals(42, results[0]["anomaly_rank"])
    }

    @Test
    fun `handleResourceRead supports shorthand turbo requests id for current run`() {
        val run = manager.startRun()
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 36
        request.response = "HTTP/1.1 200 OK\r\n\r\ntest body"
        run.store.add(request)

        val result = handlers.handleResourceRead("turbo://requests/36")

        assertEquals("test body", result["response_body"])
        assertEquals(200, result["status"])
    }

    @Test
    fun `getResults defaults to sorting by anomaly_rank descending`() {
        val run = manager.startRun()

        val req1 = burp.Request("GET /1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\nok"
        req1.anomalyRank = 10

        val req2 = burp.Request("GET /2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 2
        req2.response = "HTTP/1.1 200 OK\r\n\r\nok"
        req2.anomalyRank = 100

        val req3 = burp.Request("GET /3 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req3.id = 3
        req3.response = "HTTP/1.1 200 OK\r\n\r\nok"
        req3.anomalyRank = 50

        run.store.add(req1)
        run.store.add(req2)
        run.store.add(req3)

        // Use handleResourceRead with no sort_by param to test default
        val result = handlers.handleResourceRead("turbo://runs/current/summary")

        val results = result["results"] as List<Map<String, Any?>>
        assertEquals(3, results.size)
        // Should be sorted by anomaly_rank descending: 100, 50, 10
        assertEquals(100, results[0]["anomaly_rank"])
        assertEquals(50, results[1]["anomaly_rank"])
        assertEquals(10, results[2]["anomaly_rank"])
    }
}
