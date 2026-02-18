package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.*
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager

class McpIntegrationTest {

    private lateinit var server: TurboMcpServer

    @BeforeEach
    fun setup() {
        // Create server without starting (MCP transport requires full client setup)
        // We test the handlers directly as they contain the core functionality
        server = TurboMcpServer(port = 31338)
    }

    // Helper to get the first result's ID (getRequestDetail now looks up by ID, not index)
    @Suppress("UNCHECKED_CAST")
    private fun getFirstResultId(runId: String): Int {
        val results = server.resourceHandlers.getResults(runId,"id", false, 1, 0)
        val resultsList = results["results"] as List<Map<String, Any?>>
        return resultsList.first()["id"] as Int
    }

    @Test
    fun `tool handlers work end to end`() {
        // Test via handlers directly since HTTP client setup is complex
        val result = server.toolHandlers.startRunAsync(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
    }

    @Test
    fun `resource handlers work end to end`() {
        val startResult = server.toolHandlers.startRunAsync(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        val runId = startResult["run_id"] as String

        val status = server.resourceHandlers.getRunStatus(runId)
        assertNotNull(status["run_id"])
    }

    @Test
    fun `resource URI routing works`() {
        val startResult = server.toolHandlers.startRunAsync(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        val runId = startResult["run_id"] as String

        val result = server.invokeResourceHandler("turbo://runs/$runId")
        assertNotNull(result["run_id"])
    }

    @Test
    fun `summary endpoint is not confused with result ID endpoint`() {
        // This test catches routing collisions where /summary might be matched by /{id}
        // The bug: MCP template turbo://runs/{run_id}/{id} matches "summary" as an ID
        val startResult = server.toolHandlers.startRunAsync(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        val runId = startResult["run_id"] as String

        // Summary endpoint should return results array, not request_not_found error
        val summary = server.invokeResourceHandler("turbo://runs/$runId/summary")
        assertNull(summary["error"], "Summary should not return error, got: ${summary["error"]}")
        assertNotNull(summary["results"], "Summary should contain results array")
        assertNotNull(summary["total_count"], "Summary should contain total_count")
    }

    @Test
    fun `numeric result ID routes to result detail handler`() {
        val startResult = server.toolHandlers.startRunAsync(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        val runId = startResult["run_id"] as String

        // Numeric ID should route to result detail (will error if no results, but correct error)
        val detail = server.invokeResourceHandler("turbo://runs/$runId/42")
        // Should get request_not_found (correct handler, no request with ID 42)
        // NOT invalid_request_id (which would mean routing worked but parsing failed)
        assertEquals("request_not_found", detail["error"],
            "Numeric ID should route to result detail handler")
    }

    @Test
    fun `MCP SDK has URI template collision between summary and id`() {
        // The MCP SDK doesn't support URI template specificity, so both:
        //   turbo://runs/{run_id}/summary
        //   turbo://runs/{run_id}/{id}
        // match "turbo://runs/current/summary". The SDK may route to either handler.
        //
        // This is worked around in the {id} handler (requestDetailParams) which
        // delegates to the summary handler when id == "summary".

        val summaryTemplate = DefaultMcpUriTemplateManager("turbo://runs/{run_id}/summary")
        val resultTemplate = DefaultMcpUriTemplateManager("turbo://runs/{run_id}/{id}")

        val summaryUri = "turbo://runs/current/summary"
        val resultUri = "turbo://runs/current/42"

        assertTrue(summaryTemplate.matches(summaryUri))
        assertTrue(resultTemplate.matches(resultUri))

        // Collision: {id} also matches "summary"
        assertTrue(resultTemplate.matches(summaryUri),
            "If this fails, the MCP SDK added type constraints and the workaround may be removable")

        assertFalse(summaryTemplate.matches(resultUri))
    }

    @Tag("integration")
    @Test
    fun `sends requests to hackxor and verifies response body`() {
        val script = """
def queueRequests(target, wordlists):
    engine = RequestEngine(endpoint=target.endpoint,
                           concurrentConnections=1,
                           requestsPerConnection=1,
                           pipeline=False,
                           engine=Engine.THREADED)
    engine.queue(target.req)

def handleResponse(req, interesting):
    table.add(req)
""".trimIndent()

        val baseRequest = "GET /static/robots.txt HTTP/1.1\r\nHost: hackxor.net\r\nConnection: close\r\n\r\n"

        val result = server.toolHandlers.startRunAsync(
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
        val runId = result["run_id"] as String

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(runId)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(runId)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId(runId)
        val detail = server.resourceHandlers.getRequestDetail(runId, requestId)
        assertNull(detail["error"], "Should not have error: ${detail["error"]}")

        // Response is now split into headers and body (truncated to body_limit, default 100 chars)
        // For this test, we need more body, so call with higher limit
        val detailFull = server.resourceHandlers.getRequestDetail(runId, requestId, bodyLimit = 10000)
        val responseBody = detailFull["response_body"] as String
        assertTrue(responseBody.contains("User-agent: *"), "Response should contain User-agent directive")
        assertTrue(responseBody.contains("Disallow: /settings"), "Response should contain Disallow /settings")
        assertTrue(responseBody.contains("Disallow: /pleasebanme"), "Response should contain Disallow /pleasebanme")
    }

    @Test
    fun `Content-Length header is preserved exactly as specified`() {
        val script = """
def queueRequests(target, wordlists):
    engine = RequestEngine(endpoint=target.endpoint,
                           concurrentConnections=1,
                           requestsPerConnection=1,
                           pipeline=False,
                           engine=Engine.THREADED)
    engine.queue(target.req)

def handleResponse(req, interesting):
    table.add(req)
""".trimIndent()

        // Body is exactly 20 bytes: "XXXXXXXXXXXXXXXXXXXX"
        val baseRequest = "POST /static/test HTTP/1.1\r\nHost: hackxor.net\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 20\r\nConnection: close\r\n\r\nXXXXXXXXXXXXXXXXXXXX"

        val result = server.toolHandlers.startRunAsync(
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
        val runId = result["run_id"] as String

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(runId)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(runId)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId(runId)
        val detail = server.resourceHandlers.getRequestDetail(runId, requestId)
        assertNull(detail["error"], "Should not have error: ${detail["error"]}")

        val request = detail["request"] as String

        // Extract Content-Length value from the sent request
        val contentLengthMatch = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE).find(request)
        assertNotNull(contentLengthMatch, "Request should contain Content-Length header")

        val actualContentLength = contentLengthMatch!!.groupValues[1].toInt()
        assertEquals(20, actualContentLength,
            "Content-Length should be preserved as 20, but was $actualContentLength. Full request:\n$request")
    }

    @Test
    fun `Content-Length is calculated correctly when baseRequest uses LF line endings`() {
        // Test that LF line endings in baseRequest are normalized to CRLF
        // This matches CLI behavior from fast-http.kt:559-561
        val script = """
def queueRequests(target, wordlists):
    engine = RequestEngine(endpoint=target.endpoint,
                           concurrentConnections=1,
                           requestsPerConnection=1,
                           pipeline=False,
                           engine=Engine.THREADED)
    engine.queue(target.req)

def handleResponse(req, interesting):
    table.add(req)
""".trimIndent()

        // baseRequest uses LF only - should be normalized to CRLF
        val baseRequest = "POST /static/test HTTP/1.1\nHost: hackxor.net\nContent-Type: application/x-www-form-urlencoded\nContent-Length: 20\nConnection: close\n\nXXXXXXXXXXXXXXXXXXXX"

        val result = server.toolHandlers.startRunAsync(
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
        val runId = result["run_id"] as String

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(runId)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(runId)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId(runId)
        val detail = server.resourceHandlers.getRequestDetail(runId, requestId)
        assertNull(detail["error"], "Should not have error: ${detail["error"]}")

        val request = detail["request"] as String

        // Extract Content-Length value from the sent request
        val contentLengthMatch = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE).find(request)
        assertNotNull(contentLengthMatch, "Request should contain Content-Length header")

        val actualContentLength = contentLengthMatch!!.groupValues[1].toInt()
        assertEquals(20, actualContentLength,
            "Content-Length should be 20 (body size), but was $actualContentLength. " +
            "LF line endings should be normalized to CRLF. Full request:\n$request")
    }

    @Test
    fun `Content-Length is calculated correctly for inline requests with CRLF`() {
        // Test that Content-Length is calculated correctly when inline request uses \r\n
        val script = "def queueRequests(target, wordlists):\r\n" +
            "    engine = RequestEngine(endpoint=target.endpoint,\r\n" +
            "                           concurrentConnections=1,\r\n" +
            "                           requestsPerConnection=1,\r\n" +
            "                           pipeline=False,\r\n" +
            "                           engine=Engine.THREADED)\r\n" +
            "    req = 'POST /static/test HTTP/1.1\\r\\nHost: hackxor.net\\r\\nContent-Type: application/x-www-form-urlencoded\\r\\nContent-Length: 20\\r\\nConnection: close\\r\\n\\r\\nXXXXXXXXXXXXXXXXXXXX'\r\n" +
            "    engine.queue(req)\r\n" +
            "\r\n" +
            "def handleResponse(req, interesting):\r\n" +
            "    table.add(req)\r\n"

        val result = server.toolHandlers.startRunAsync(
            script = script,
            baseRequest = "GET / HTTP/1.1\r\nHost: hackxor.net\r\n\r\n",
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
        val runId = result["run_id"] as String

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(runId)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(runId)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId(runId)
        val detail = server.resourceHandlers.getRequestDetail(runId, requestId)
        assertNull(detail["error"], "Should not have error: ${detail["error"]}")

        val request = detail["request"] as String

        // Extract Content-Length value from the sent request
        val contentLengthMatch = Regex("""Content-Length:\s*(\d+)""", RegexOption.IGNORE_CASE).find(request)
        assertNotNull(contentLengthMatch, "Request should contain Content-Length header")

        val actualContentLength = contentLengthMatch!!.groupValues[1].toInt()
        assertEquals(20, actualContentLength,
            "Content-Length should be 20 (body size), but was $actualContentLength. Full request:\n$request")
    }
}
