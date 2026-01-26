package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class McpResourceHandlersTest {

    private lateinit var manager: RunManager
    private lateinit var handlers: McpResourceHandlers
    private val testSessionId = "test-session"

    @BeforeEach
    fun setup() {
        manager = RunManager()
        handlers = McpResourceHandlers(manager)
    }

    @Test
    fun `listRuns returns empty when no runs`() {
        val result = handlers.listRuns(testSessionId)

        assertTrue((result["runs"] as List<*>).isEmpty())
    }

    @Test
    fun `listRuns returns all runs`() {
        manager.startConcurrentRun(testSessionId)
        manager.startConcurrentRun(testSessionId)

        val result = handlers.listRuns(testSessionId)
        val runs = result["runs"] as List<*>

        assertEquals(2, runs.size)
    }

    @Test
    fun `getRunStatus returns error for no current run`() {
        val result = handlers.getRunStatus(testSessionId, null)

        assertEquals("no_current_run", result["error"])
    }

    @Test
    fun `getRunStatus returns run info`() {
        manager.startRun(testSessionId)

        val result = handlers.getRunStatus(testSessionId, null)

        assertNotNull(result["run_id"])
        assertNotNull(result["running"])
        assertNotNull(result["finished"])
        assertNotNull(result["result_count"])
    }

    @Test
    fun `getResults returns empty list when no results`() {
        manager.startRun(testSessionId)

        val result = handlers.getResults(testSessionId, null, "id", true, 100, 0)

        assertEquals(0, result["total_count"])
        assertTrue((result["results"] as List<*>).isEmpty())
    }

    @Test
    fun `getRequestDetail returns error for invalid request`() {
        manager.startRun(testSessionId)

        val result = handlers.getRequestDetail(testSessionId, null, 999)

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
        assertEquals(42, handlers.parseRequestId("turbo://runs/abc123/42"))
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
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
            "A".repeat(500)  // 500-char body
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, bodyLimit = 100)

        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/html", result["response_headers"])
        assertEquals("A".repeat(100), result["response_body"])
        assertEquals(200, result["status"])
    }

    @Test
    fun `getRequestDetail respects custom body_limit`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\n" + "B".repeat(1000)
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, bodyLimit = 250)

        assertEquals("B".repeat(250), result["response_body"])
    }

    @Test
    fun `getRequestDetail returns full body when shorter than limit`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nshort"
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, bodyLimit = 100)

        assertEquals("short", result["response_body"])
    }

    @Test
    fun `getRequestDetail includes truncation metadata when truncated`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\n" + "A".repeat(500)
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, bodyLimit = 100)

        assertEquals(true, result["response_body_truncated"])
        assertEquals(500, result["response_body_total_length"])
    }

    @Test
    fun `getRequestDetail truncation metadata shows false when not truncated`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nshort"
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, bodyLimit = 100)

        assertEquals(false, result["response_body_truncated"])
        assertEquals(5, result["response_body_total_length"])
    }

    @Test
    fun `getRequestDetail with exportFile writes response to file and returns path`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        val fullResponse = "HTTP/1.1 200 OK\r\n\r\n" + "X".repeat(1000)
        request.response = fullResponse
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, exportFile = true)

        assertNotNull(result["response_file"])
        val filePath = result["response_file"] as String
        val fileContent = java.io.File(filePath).readText()
        assertEquals(fullResponse, fileContent)
        // Should not include inline body when exporting to file
        assertNull(result["response_body"])
    }

    @Test
    fun `getRequestDetail with exportFile also exports request`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET /test HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nok"
        run.store.add(request)

        val result = handlers.getRequestDetail(testSessionId, null, 1, exportFile = true)

        assertNotNull(result["request_file"])
        val filePath = result["request_file"] as String
        val fileContent = java.io.File(filePath).readText()
        assertTrue(fileContent.contains("GET /test HTTP/1.1"))
    }

    @Test
    fun `handleResourceRead parses body_limit from URI`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\n" + "Z".repeat(500)
        run.store.add(request)

        val result = handlers.handleResourceRead(testSessionId, "turbo://runs/current/1?body_limit=50")

        assertEquals("Z".repeat(50), result["response_body"])
    }

    @Test
    fun `handleResourceRead parses export param from URI`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\ndata"
        run.store.add(request)

        val result = handlers.handleResourceRead(testSessionId, "turbo://runs/current/1?export=file")

        assertNotNull(result["response_file"])
        assertNull(result["response_body"])
    }

    @Test
    fun `getResults includes anomaly_rank in results`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\n\r\nok"
        request.anomalyRank = 42
        run.store.add(request)

        val result = handlers.getResults(testSessionId, null, "id", true, 100, 0)

        val results = result["results"] as List<Map<String, Any?>>
        assertEquals(1, results.size)
        assertEquals(42, results[0]["anomaly_rank"])
    }

    @Test
    fun `handleResourceRead routes result by id for current run`() {
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 36
        request.response = "HTTP/1.1 200 OK\r\n\r\ntest body"
        run.store.add(request)

        val result = handlers.handleResourceRead(testSessionId, "turbo://runs/current/36")

        assertEquals("test body", result["response_body"])
        assertEquals(200, result["status"])
    }

    @Test
    fun `getResults defaults to sorting by anomaly_rank descending`() {
        val run = manager.startRun(testSessionId)

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
        val result = handlers.handleResourceRead(testSessionId, "turbo://runs/current/summary")

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

        val result = handlersWithOrganizer.handleResourceRead(testSessionId, "turbo://organizer")

        assertEquals(1, result["count"])
    }

    @Test
    fun `getOrganizerItem returns item by id`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(100, "GET /page1 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\nbody", "Test notes")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100)

        assertEquals(100, result["id"])
        assertEquals("GET /page1 HTTP/1.1", result["request"])
        assertEquals("HTTP/1.1 200 OK", result["response_headers"])
        assertEquals("body", result["response_body"])
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

        val result = handlersWithOrganizer.handleResourceRead(testSessionId, "turbo://organizer/42")

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
    fun `parseOrganizerIds extracts comma-separated ids from URI`() {
        assertEquals(setOf(1, 2, 3), handlers.parseOrganizerIds("turbo://organizer/1,2,3"))
        assertEquals(setOf(42), handlers.parseOrganizerIds("turbo://organizer/42"))
        assertEquals(setOf(10, 20), handlers.parseOrganizerIds("turbo://organizer/10,20?body_limit=100"))
        assertEquals(emptySet<Int>(), handlers.parseOrganizerIds("turbo://organizer"))
        assertEquals(emptySet<Int>(), handlers.parseOrganizerIds("turbo://organizer/"))
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

    @Test
    fun `getOrganizerItem splits response into headers and body`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(
                id = 100,
                request = "GET /page HTTP/1.1",
                response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>body</html>"
            )
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100)

        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/html", result["response_headers"])
        assertEquals("<html>body</html>", result["response_body"])
        assertNull(result["response"])  // Old field should be gone
    }

    @Test
    fun `getOrganizerItem truncates body by default`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(
                id = 100,
                request = "GET /page HTTP/1.1",
                response = "HTTP/1.1 200 OK\r\n\r\n" + "X".repeat(500)
            )
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100)

        assertEquals("X".repeat(100), result["response_body"])
        assertEquals(true, result["response_body_truncated"])
        assertEquals(500, result["response_body_total_length"])
    }

    @Test
    fun `getOrganizerItem respects custom body_limit`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(
                id = 100,
                request = "GET /page HTTP/1.1",
                response = "HTTP/1.1 200 OK\r\n\r\n" + "Y".repeat(500)
            )
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100, bodyLimit = 200)

        assertEquals("Y".repeat(200), result["response_body"])
        assertEquals(true, result["response_body_truncated"])
    }

    @Test
    fun `getOrganizerItem returns full body when limit is zero`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(
                id = 100,
                request = "GET /page HTTP/1.1",
                response = "HTTP/1.1 200 OK\r\n\r\n" + "Z".repeat(500)
            )
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItem(100, bodyLimit = 0)

        assertEquals("Z".repeat(500), result["response_body"])
        assertEquals(false, result["response_body_truncated"])
    }

    @Test
    fun `handleResourceRead parses body_limit for organizer items`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(
                id = 42,
                request = "GET /test HTTP/1.1",
                response = "HTTP/1.1 200 OK\r\n\r\n" + "Q".repeat(500)
            )
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.handleResourceRead(testSessionId, "turbo://organizer/42?body_limit=150")

        assertEquals("Q".repeat(150), result["response_body"])
        assertEquals(true, result["response_body_truncated"])
    }

    @Test
    fun `getResults includes status_codes field with all unique status codes`() {
        val run = manager.startRun(testSessionId)

        val req200a = burp.Request("GET /a HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req200a.id = 1
        req200a.response = "HTTP/1.1 200 OK\r\n\r\nok"

        val req200b = burp.Request("GET /b HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req200b.id = 2
        req200b.response = "HTTP/1.1 200 OK\r\n\r\nok"

        val req404 = burp.Request("GET /c HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req404.id = 3
        req404.response = "HTTP/1.1 404 Not Found\r\n\r\nnot found"

        val req500 = burp.Request("GET /d HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req500.id = 4
        req500.response = "HTTP/1.1 500 Error\r\n\r\nerror"

        run.store.add(req200a)
        run.store.add(req200b)
        run.store.add(req404)
        run.store.add(req500)

        val result = handlers.getResults(testSessionId, null, "id", true, 100, 0)

        @Suppress("UNCHECKED_CAST")
        val statusCodes = result["status_codes"] as Set<Int>
        assertEquals(setOf(200, 404, 500), statusCodes)
    }

    @Test
    fun `getOrganizerItems returns multiple items by ids`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\nbody1"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\nbody2"),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\nbody3")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItems(setOf(1, 3))

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals(setOf(1, 3), items.map { it["id"] }.toSet())
    }

    @Test
    fun `getOrganizerItems respects body_limit`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\n" + "X".repeat(500))
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItems(setOf(1), bodyLimit = 50)

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals("X".repeat(50), items[0]["response_body"])
        assertEquals(true, items[0]["response_body_truncated"])
    }

    @Test
    fun `handleResourceRead routes comma-separated organizer ids`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(10, "GET /10 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\nten"),
            FakeOrganizerItem(20, "GET /20 HTTP/1.1", "HTTP/1.1 200 OK\r\n\r\ntwenty")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.handleResourceRead(testSessionId, "turbo://organizer/10,20")

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
    }

    // Desync mode Connection header stripping tests

    @Test
    fun `getRequestDetail strips Connection header when desync mode enabled`() {
        val handlersWithDesync = McpResourceHandlers(manager, desyncMode = { true })
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nContent-Type: text/html\r\n\r\nbody"
        run.store.add(request)

        val result = handlersWithDesync.getRequestDetail(testSessionId, null, 1)

        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/html", result["response_headers"])
    }

    @Test
    fun `getRequestDetail preserves Connection header when desync mode disabled`() {
        val handlersNoDesync = McpResourceHandlers(manager, desyncMode = { false })
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nContent-Type: text/html\r\n\r\nbody"
        run.store.add(request)

        val result = handlersNoDesync.getRequestDetail(testSessionId, null, 1)

        assertEquals("HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nContent-Type: text/html", result["response_headers"])
    }

    @Test
    fun `getRequestDetail strips Connection header case-insensitively`() {
        val handlersWithDesync = McpResourceHandlers(manager, desyncMode = { true })
        val run = manager.startRun(testSessionId)
        val request = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        request.id = 1
        request.response = "HTTP/1.1 200 OK\r\nconnection: close\r\nContent-Type: text/html\r\n\r\nbody"
        run.store.add(request)

        val result = handlersWithDesync.getRequestDetail(testSessionId, null, 1)

        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/html", result["response_headers"])
    }

    @Test
    fun `getOrganizerItem strips Connection header when desync mode enabled`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK\r\nConnection: close\r\nX-Custom: value\r\n\r\nbody")
        ))
        val handlersWithDesync = McpResourceHandlers(manager, fakeOrganizer, desyncMode = { true })

        val result = handlersWithDesync.getOrganizerItem(1)

        assertEquals("HTTP/1.1 200 OK\r\nX-Custom: value", result["response_headers"])
    }

    // Domain filtering tests

    @Test
    fun `listOrganizerItems with domain filter returns only matching items`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "other.com"),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "example.com")

        assertEquals(2, result["count"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        // Sorted by ID descending (timestamps are null)
        assertEquals(listOf(3, 1), items.map { it["id"] })
    }

    @Test
    fun `listOrganizerItems with domain filter paginates 10 per page`() {
        // Create 25 items for example.com
        val items = (1..25).map {
            FakeOrganizerItem(it, "GET /$it HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        }
        val fakeOrganizer = FakeOrganizerProvider(items)
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "example.com")

        assertEquals(25, result["count"])
        assertEquals(1, result["page"])
        assertEquals(10, result["page_size"])
        assertEquals(3, result["total_pages"])
        @Suppress("UNCHECKED_CAST")
        val returnedItems = result["items"] as List<Map<String, Any?>>
        assertEquals(10, returnedItems.size)
    }

    @Test
    fun `listOrganizerItems with domain filter supports page parameter`() {
        // Create 25 items for example.com
        val items = (1..25).map {
            FakeOrganizerItem(it, "GET /$it HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        }
        val fakeOrganizer = FakeOrganizerProvider(items)
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "example.com", page = 3)

        assertEquals(25, result["count"])
        assertEquals(3, result["page"])
        assertEquals(3, result["total_pages"])
        @Suppress("UNCHECKED_CAST")
        val returnedItems = result["items"] as List<Map<String, Any?>>
        assertEquals(5, returnedItems.size)  // Last page has only 5 items
    }

    @Test
    fun `listOrganizerItems with domain filter sorts by timestamp descending`() {
        val now = java.time.ZonedDateTime.now()
        val items = listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = now.minusHours(2)),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = now),  // Most recent
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = now.minusHours(1))
        )
        val fakeOrganizer = FakeOrganizerProvider(items)
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "example.com")

        @Suppress("UNCHECKED_CAST")
        val returnedItems = result["items"] as List<Map<String, Any?>>
        // Should be sorted: most recent first (2, 3, 1)
        assertEquals(listOf(2, 3, 1), returnedItems.map { it["id"] })
    }

    @Test
    fun `listOrganizerItems with domain filter sorts nulls last then by ID descending`() {
        val now = java.time.ZonedDateTime.now()
        val items = listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = null),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = now),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = null),
            FakeOrganizerItem(4, "GET /4 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com", timeRequestSent = now.minusHours(1))
        )
        val fakeOrganizer = FakeOrganizerProvider(items)
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "example.com")

        @Suppress("UNCHECKED_CAST")
        val returnedItems = result["items"] as List<Map<String, Any?>>
        // Items with timestamps first (2, 4), then nulls sorted by ID descending (3, 1)
        assertEquals(listOf(2, 4, 3, 1), returnedItems.map { it["id"] })
    }

    @Test
    fun `handleResourceRead parses domain query param for organizer`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "other.com"),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.handleResourceRead(testSessionId, "turbo://organizer?domain=example.com")

        assertEquals(2, result["count"])
        assertEquals(1, result["page"])
    }

    @Test
    fun `handleResourceRead parses domain and page query params for organizer`() {
        // Create 15 items for example.com
        val items = (1..15).map {
            FakeOrganizerItem(it, "GET /$it HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        }
        val fakeOrganizer = FakeOrganizerProvider(items)
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.handleResourceRead(testSessionId, "turbo://organizer?domain=example.com&page=2")

        assertEquals(15, result["count"])
        assertEquals(2, result["page"])
        @Suppress("UNCHECKED_CAST")
        val returnedItems = result["items"] as List<Map<String, Any?>>
        assertEquals(5, returnedItems.size)  // Page 2 has 5 items (11-15)
    }

    @Test
    fun `listOrganizerItems without filter returns no pagination metadata`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "other.com")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems()

        assertEquals(2, result["count"])
        assertFalse(result.containsKey("page"))
        assertFalse(result.containsKey("page_size"))
        assertFalse(result.containsKey("total_pages"))
    }

    @Test
    fun `listOrganizerItems with non-matching domain returns empty paginated result`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "nonexistent.com")

        assertEquals(0, result["count"])
        assertEquals(1, result["page"])
        assertEquals(0, result["total_pages"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertTrue(items.isEmpty())
    }

    @Test
    fun `listOrganizerItems domain filter is case-sensitive`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "Example.com"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "example.com")
        ))
        val handlersWithOrganizer = McpResourceHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems(domain = "example.com")

        assertEquals(1, result["count"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(listOf(2), items.map { it["id"] })
    }
}
