package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.*

class McpIntegrationTest {

    private lateinit var server: TurboMcpServer

    @BeforeEach
    fun setup() {
        // Create server without starting (MCP transport requires full client setup)
        // We test the handlers directly as they contain the core functionality
        server = TurboMcpServer(port = 31338)
    }

    @Test
    fun `tool handlers work end to end`() {
        // Test via handlers directly since HTTP client setup is complex
        val result = server.toolHandlers.startRun(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])
    }

    @Test
    fun `resource handlers work end to end`() {
        server.toolHandlers.startRun(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )

        val status = server.resourceHandlers.getRunStatus(null)
        assertNotNull(status["run_id"])
    }

    @Test
    fun `resource URI routing works`() {
        server.toolHandlers.startRun(
            script = "def queueRequests(t, w): pass\ndef completed(r): pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: test\r\n\r\n",
            endpoint = "https://test.com:443",
            baseInput = ""
        )

        val result = server.resourceHandlers.handleResourceRead("turbo://runs/current")
        assertNotNull(result["run_id"])
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

        val result = server.toolHandlers.startRun(
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val detail = server.resourceHandlers.getRequestDetail(null, 0)
        assertNull(detail["error"], "Should not have error: ${detail["error"]}")

        val response = detail["response"] as String
        assertTrue(response.contains("User-agent: *"), "Response should contain User-agent directive")
        assertTrue(response.contains("Disallow: /settings"), "Response should contain Disallow /settings")
        assertTrue(response.contains("Disallow: /pleasebanme"), "Response should contain Disallow /pleasebanme")
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

        val result = server.toolHandlers.startRun(
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val detail = server.resourceHandlers.getRequestDetail(null, 0)
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

        val result = server.toolHandlers.startRun(
            script = script,
            baseRequest = baseRequest,
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val detail = server.resourceHandlers.getRequestDetail(null, 0)
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

        val result = server.toolHandlers.startRun(
            script = script,
            baseRequest = "GET / HTTP/1.1\r\nHost: hackxor.net\r\n\r\n",
            endpoint = "https://hackxor.net:443",
            baseInput = ""
        )
        assertEquals("started", result["status"])

        // Wait for run to complete
        var attempts = 0
        while (attempts < 30) {
            val status = server.resourceHandlers.getRunStatus(null)
            if (status["finished"] == true && status["result_count"] as Int > 0) break
            Thread.sleep(500)
            attempts++
        }

        val status = server.resourceHandlers.getRunStatus(null)
        assertEquals(true, status["finished"], "Run should have finished")
        assertTrue((status["result_count"] as Int) > 0, "Should have at least one result")

        val detail = server.resourceHandlers.getRequestDetail(null, 0)
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
