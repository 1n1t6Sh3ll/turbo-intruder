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
    fun `getOrganizerItems returns items matching requested IDs`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(100, "GET /page1 HTTP/1.1", "HTTP/1.1 200 OK"),
            FakeOrganizerItem(101, "GET /page2 HTTP/1.1", "HTTP/1.1 404 Not Found"),
            FakeOrganizerItem(102, "POST /api HTTP/1.1", "HTTP/1.1 201 Created"),
            FakeOrganizerItem(103, "GET /other HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItems("100,101,102")

        assertEquals(3, (result["items"] as List<*>).size)
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(100, items[0]["id"])
        assertEquals(101, items[1]["id"])
        assertEquals(102, items[2]["id"])
    }

    @Test
    fun `getOrganizerItems returns empty list when no matching IDs`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(100, "GET / HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItems("999,998")

        assertEquals(0, (result["items"] as List<*>).size)
    }

    @Test
    fun `getOrganizerItems handles single ID`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(42, "GET /test HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItems("42")

        assertEquals(1, (result["items"] as List<*>).size)
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(42, items[0]["id"])
        assertEquals("GET /test HTTP/1.1", items[0]["request"])
        assertEquals("HTTP/1.1 200 OK", items[0]["response"])
    }

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

    @Test
    fun `stopRun stops current run`() {
        handlers.startRun(
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        val result = handlers.stopRun(null)

        assertEquals("stopped", result["status"])
    }

    @Test
    fun `deleteAllRuns returns count`() {
        manager.startConcurrentRun()
        manager.startConcurrentRun()

        val result = handlers.deleteAllRuns()

        assertEquals(2, result["deleted_count"])
    }
}

// Test helpers
data class FakeOrganizerItem(
    val id: Int,
    val request: String,
    val response: String
)

class FakeOrganizerProvider(private val items: List<FakeOrganizerItem>) : OrganizerProvider {
    override fun getItems(): List<OrganizerItemData> {
        return items.map { OrganizerItemData(it.id, it.request, it.response) }
    }

    override fun getItemsByIds(ids: Set<Int>): List<OrganizerItemData> {
        return items.filter { it.id in ids }.map { OrganizerItemData(it.id, it.request, it.response) }
    }
}
