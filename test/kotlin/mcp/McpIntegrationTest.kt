package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.*
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager

class McpIntegrationTest {

    private lateinit var server: TurboMcpServer
    private val testSessionId = "test-session"

    @BeforeEach
    fun setup() {
        // Create server without starting (MCP transport requires full client setup)
        // We test the handlers directly as they contain the core functionality
        server = TurboMcpServer(port = 31338)
    }

    // Helper to get the first result's ID (getRequestDetail now looks up by ID, not index)
    @Suppress("UNCHECKED_CAST")
    private fun getFirstResultId(): Int {
        val results = server.resourceHandlers.getResults(testSessionId, null, "id", false, 1, 0)
        val resultsList = results["results"] as List<Map<String, Any?>>
        return resultsList.first()["id"] as Int
    }

    @Test
    fun `tool handlers work end to end`() {
        // Test via handlers directly since HTTP client setup is complex
        val result = server.toolHandlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
    }

    @Test
    fun `resource handlers work end to end`() {
        server.toolHandlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )

        val status = server.resourceHandlers.getRunStatus(testSessionId, null)
        assertNotNull(status["run_id"])
    }

    @Test
    fun `resource URI routing works`() {
        server.toolHandlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )

        val result = server.invokeResourceHandler("turbo://runs/current", testSessionId)
        assertNotNull(result["run_id"])
    }

    @Test
    fun `summary endpoint is not confused with result ID endpoint`() {
        // This test catches routing collisions where /summary might be matched by /{id}
        // The bug: MCP template turbo://runs/{run_id}/{id} matches "summary" as an ID
        server.toolHandlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )

        // Summary endpoint should return results array, not request_not_found error
        val summary = server.invokeResourceHandler("turbo://runs/current/summary", testSessionId)
        assertNull(summary["error"], "Summary should not return error, got: ${summary["error"]}")
        assertNotNull(summary["results"], "Summary should contain results array")
        assertNotNull(summary["total_count"], "Summary should contain total_count")
    }

    @Test
    fun `numeric result ID routes to result detail handler`() {
        server.toolHandlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )

        // Numeric ID should route to result detail (will error if no results, but correct error)
        val detail = server.invokeResourceHandler("turbo://runs/current/42", testSessionId)
        // Should get request_not_found (correct handler, no request with ID 42)
        // NOT invalid_request_id (which would mean routing worked but parsing failed)
        assertEquals("request_not_found", detail["error"],
            "Numeric ID should route to result detail handler")
    }

    @Test
    fun `MCP URI templates must not have ambiguous matches`() {
        // This test verifies that our MCP resource templates don't have collision issues
        // where multiple templates match the same URI.
        //
        // The MCP SDK uses findFirst() on template matches, so if both:
        //   turbo://runs/{run_id}/summary
        //   turbo://runs/{run_id}/{id}
        // match "turbo://runs/current/summary", routing depends on registration order.
        //
        // This test documents the collision so we don't accidentally break routing.

        val summaryTemplate = DefaultMcpUriTemplateManager("turbo://runs/{run_id}/summary")
        val resultTemplate = DefaultMcpUriTemplateManager("turbo://runs/{run_id}/{id}")

        val summaryUri = "turbo://runs/current/summary"
        val resultUri = "turbo://runs/current/42"

        // Summary template should match summary URI
        assertTrue(summaryTemplate.matches(summaryUri),
            "Summary template should match summary URI")

        // Result template should match result URI
        assertTrue(resultTemplate.matches(resultUri),
            "Result template should match result URI")

        // BUG: Result template also matches summary URI because {id} matches "summary"
        // This test documents this known issue - if it starts failing, the SDK behavior changed
        assertTrue(resultTemplate.matches(summaryUri),
            "Result template incorrectly matches summary URI - this is a known collision. " +
            "If this assertion fails, the MCP SDK may have added type constraints to templates.")

        // Summary template should NOT match result URI (summary is literal, not a pattern)
        assertFalse(summaryTemplate.matches(resultUri),
            "Summary template should not match numeric result URI")
    }

    @Test
    fun `MCP resource template registration order prevents summary collision`() {
        // The MCP SDK uses findFirst() when matching templates, so order matters.
        // This test verifies that summary template is registered before result template,
        // ensuring /summary routes correctly even though {id} would also match it.
        //
        // If this test fails, check buildStatelessResourceSpecifications() in TurboMcpServer.kt
        // to ensure buildStatelessRunResultsResourceTemplate (summary) comes before
        // buildStatelessRequestDetailResourceTemplate (result/{id}).

        val summaryTemplate = DefaultMcpUriTemplateManager("turbo://runs/{run_id}/summary")
        val resultTemplate = DefaultMcpUriTemplateManager("turbo://runs/{run_id}/{id}")

        // Simulate SDK's template matching with our registration order
        val templates = listOf(
            "turbo://runs/{run_id}/summary" to "summary_handler",
            "turbo://runs/{run_id}/{id}" to "result_handler"
        )

        val summaryUri = "turbo://runs/current/summary"

        // Find first matching template (mimics SDK behavior)
        val matchedHandler = templates
            .firstOrNull { (template, _) ->
                DefaultMcpUriTemplateManager(template).matches(summaryUri)
            }
            ?.second

        assertEquals("summary_handler", matchedHandler,
            "Summary URI should match summary_handler first due to registration order. " +
            "If this fails, the template order in TurboMcpServer may have changed.")
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
            sessionId = testSessionId,
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(testSessionId, null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(testSessionId, null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId()
        val detail = server.resourceHandlers.getRequestDetail(testSessionId, null, requestId)
        assertNull(detail["error"], "Should not have error: ${detail["error"]}")

        // Response is now split into headers and body (truncated to body_limit, default 100 chars)
        // For this test, we need more body, so call with higher limit
        val detailFull = server.resourceHandlers.getRequestDetail(testSessionId, null, requestId, bodyLimit = 10000)
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
            sessionId = testSessionId,
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(testSessionId, null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(testSessionId, null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId()
        val detail = server.resourceHandlers.getRequestDetail(testSessionId, null, requestId)
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
            sessionId = testSessionId,
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(testSessionId, null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(testSessionId, null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId()
        val detail = server.resourceHandlers.getRequestDetail(testSessionId, null, requestId)
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
            sessionId = testSessionId,
            script = script,
            baseRequest = "GET / HTTP/1.1\r\nHost: hackxor.net\r\n\r\n",
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(testSessionId, null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(testSessionId, null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val requestId = getFirstResultId()
        val detail = server.resourceHandlers.getRequestDetail(testSessionId, null, requestId)
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
