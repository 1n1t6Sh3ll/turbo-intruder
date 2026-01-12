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

    // Organizer resource tests

    @Test
    fun `listOrganizerItems returns all items`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK"),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems()

        assertEquals(3, result["count"])
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(listOf(1, 2, 3), items.map { it["id"] })
    }

    @Test
    fun `listOrganizerItems returns empty list when no items`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems()

        assertEquals(0, result["count"])
        assertEquals(emptyList<Any>(), result["items"])
    }

    @Test
    fun `handleResourceRead routes turbo organizer to listOrganizerItems`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(42, "GET /test HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.handleResourceRead("turbo://organizer")

        assertEquals(1, result["count"])
    }

    @Test
    fun `getOrganizerItem returns item by id`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(100, "GET /page1 HTTP/1.1", "HTTP/1.1 200 OK", "Test notes")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100)

        assertEquals(100, result["id"])
        assertEquals("GET /page1 HTTP/1.1", result["request"])
        assertEquals("HTTP/1.1 200 OK", result["response"])
        assertEquals("Test notes", result["notes"])
    }

    @Test
    fun `getOrganizerItem returns error for non-existent item`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(999)

        assertEquals("not_found", result["error"])
    }

    @Test
    fun `handleResourceRead routes turbo organizer id to getOrganizerItem`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(42, "GET /test HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.handleResourceRead("turbo://organizer/42")

        assertEquals(42, result["id"])
        assertEquals("GET /test HTTP/1.1", result["request"])
    }

    @Test
    fun `parseOrganizerId extracts id from URI`() {
        assertEquals(42, handlers.parseOrganizerId("turbo://organizer/42"))
        assertEquals(100, handlers.parseOrganizerId("turbo://organizer/100"))
        assertNull(handlers.parseOrganizerId("turbo://organizer"))
        assertNull(handlers.parseOrganizerId("turbo://organizer/"))
    }

    @Test
    fun `getOrganizerItem returns http service info`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(
                id = 100,
                request = "GET /page1 HTTP/1.1",
                response = "HTTP/1.1 200 OK",
                notes = "Test",
                host = "example.com",
                port = 443,
                secure = true
            )
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100)

        assertEquals("example.com", result["host"])
        assertEquals(443, result["port"])
        assertEquals(true, result["secure"])
    }
}
