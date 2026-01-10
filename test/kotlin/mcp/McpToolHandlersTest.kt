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
    fun `getOrganizerItems returns notes`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET / HTTP/1.1", "HTTP/1.1 200 OK", "Interesting finding")
        ))
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.getOrganizerItems("1")

        val items = result["items"] as List<Map<String, Any?>>
        assertEquals("Interesting finding", items[0]["notes"])
    }

    @Test
    fun `setOrganizerNotes updates notes on item`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET / HTTP/1.1", "HTTP/1.1 200 OK", "Old note")
        ))
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.setOrganizerNotes(1, "New note")

        assertEquals("success", result["status"])
        assertEquals("New note", fakeOrganizer.getNotes(1))
    }

    @Test
    fun `setOrganizerNotes returns error for non-existent item`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.setOrganizerNotes(999, "Note")

        assertEquals("not_found", result["error"])
    }

    @Test
    fun `listOrganizerItems returns all item IDs`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK"),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK")
        ))
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems()

        assertEquals(3, result["count"])
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(listOf(1, 2, 3), items.map { it["id"] })
    }

    @Test
    fun `listOrganizerItems returns empty list when no items`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val result = handlersWithOrganizer.listOrganizerItems()

        assertEquals(0, result["count"])
        assertEquals(emptyList<Any>(), result["items"])
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

    @Test
    fun `saveToOrganizer saves requests with notes`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        // Create a run with some requests
        val run = manager.startRun()
        val req1 = burp.Request("GET /page1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\nOK"
        val req2 = burp.Request("GET /page2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 2
        req2.response = "HTTP/1.1 404 Not Found\r\n\r\nNot Found"
        run.store.add(req1)
        run.store.add(req2)

        val result = handlersWithOrganizer.saveToOrganizer(
            runId = null,
            items = """[{"request_id": 1, "notes": "Interesting finding"}, {"request_id": 2, "notes": "Check this"}]"""
        )

        val saved = result["saved"] as List<*>
        assertEquals(listOf(1, 2), saved)
        assertEquals(2, fakeOrganizer.sentItems.size)
        assertEquals("Interesting finding", fakeOrganizer.sentItems[0].second)
        assertEquals("Check this", fakeOrganizer.sentItems[1].second)
    }

    @Test
    fun `saveToOrganizer returns error for non-existent request`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val run = manager.startRun()
        val req1 = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\n"
        run.store.add(req1)

        val result = handlersWithOrganizer.saveToOrganizer(
            runId = null,
            items = """[{"request_id": 1, "notes": "Good"}, {"request_id": 999, "notes": "Missing"}]"""
        )

        val saved = result["saved"] as List<*>
        val errors = result["errors"] as List<Map<String, Any>>
        assertEquals(listOf(1), saved)
        assertEquals(1, errors.size)
        assertEquals(999, errors[0]["request_id"])
    }

    @Test
    fun `saveToOrganizer uses specified run`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        // Create two runs
        val run1 = manager.startConcurrentRun()
        val req1 = burp.Request("GET /run1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\n"
        run1.store.add(req1)

        val run2 = manager.startConcurrentRun()
        val req2 = burp.Request("GET /run2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 1  // Same ID but different run
        req2.response = "HTTP/1.1 404 Not Found\r\n\r\n"
        run2.store.add(req2)

        val result = handlersWithOrganizer.saveToOrganizer(
            runId = run1.id,
            items = """[{"request_id": 1, "notes": "From run1"}]"""
        )

        val saved = result["saved"] as List<*>
        assertEquals(listOf(1), saved)
        assertEquals(1, fakeOrganizer.sentItems.size)
        assertTrue(fakeOrganizer.sentItems[0].first.template.contains("/run1"))
    }
}

// Test helpers
data class FakeOrganizerItem(
    val id: Int,
    val request: String,
    val response: String,
    var notes: String = ""
)

class FakeOrganizerProvider(items: List<FakeOrganizerItem>) : OrganizerProvider {
    private val items = items.toMutableList()
    val sentItems = mutableListOf<Pair<burp.Request, String>>()

    override fun getItems(): List<OrganizerItemData> {
        return items.map { OrganizerItemData(it.id, it.request, it.response, it.notes) }
    }

    override fun getItemsByIds(ids: Set<Int>): List<OrganizerItemData> {
        return items.filter { it.id in ids }.map { OrganizerItemData(it.id, it.request, it.response, it.notes) }
    }

    override fun setNotes(id: Int, notes: String): Boolean {
        val item = items.find { it.id == id } ?: return false
        item.notes = notes
        return true
    }

    override fun sendToOrganizer(request: burp.Request, notes: String) {
        sentItems.add(Pair(request, notes))
    }

    fun getNotes(id: Int): String? = items.find { it.id == id }?.notes
}

class ThrowingOrganizerProvider : OrganizerProvider {
    override fun getItems(): List<OrganizerItemData> = throw RuntimeException("Test exception from getItems")
    override fun getItemsByIds(ids: Set<Int>): List<OrganizerItemData> = throw RuntimeException("Test exception from getItemsByIds")
    override fun setNotes(id: Int, notes: String): Boolean = throw RuntimeException("Test exception from setNotes")
    override fun sendToOrganizer(request: burp.Request, notes: String) = throw RuntimeException("Test exception from sendToOrganizer")
}
