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
}
