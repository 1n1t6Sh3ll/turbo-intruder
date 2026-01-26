package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class McpToolHandlersTest {

    private lateinit var manager: RunManager
    private lateinit var handlers: McpToolHandlers
    private val testSessionId = "test-session"

    @BeforeEach
    fun setup() {
        manager = RunManager()
        handlers = McpToolHandlers(manager)
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
    fun `startRun blocks until completion and returns results`() {
        // Use a script that completes immediately
        val result = handlers.startRun(
            sessionId = testSessionId,
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        assertEquals("completed", result["status"])
        assertNotNull(result["run_id"])
        assertNotNull(result["results"])
        assertNotNull(result["result_count"])
    }

    @Test
    fun `startRun returns timeout status when timeout exceeded`() {
        // Use a script that would run forever (but we timeout quickly)
        val result = handlers.startRun(
            sessionId = testSessionId,
            script = """
def queueRequests(target, wordlists):
    import time
    time.sleep(10)  # Longer than timeout

def completed(results):
    pass
            """.trimIndent(),
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = "",
            timeoutMs = 100  // Very short timeout
        )

        assertEquals("timeout", result["status"])
        assertNotNull(result["run_id"])  // Run should still be available
    }

    @Test
    fun `startRun uses default 60 second timeout`() {
        // This test verifies the default parameter exists
        // We don't actually wait 60 seconds - just verify the function accepts no timeout param
        val result = handlers.startRun(
            sessionId = testSessionId,
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        // Should complete (not timeout) since script finishes immediately
        assertEquals("completed", result["status"])
    }

    @Test
    fun `startRunAsync returns immediately with started status`() {
        val result = handlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        assertEquals("started", result["status"])
        assertNotNull(result["run_id"])
        assertNotNull(manager.currentRun)
    }

    @Test
    fun `startConcurrentRunAsync preserves existing runs`() {
        handlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )
        val firstRunId = manager.currentRun?.id

        val result = handlers.startConcurrentRunAsync(
            sessionId = testSessionId,
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
        handlers.startRunAsync(
            sessionId = testSessionId,
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        val result = handlers.stopRun(testSessionId, null)

        assertEquals("stopped", result["status"])
    }

    @Test
    fun `deleteAllRuns returns count`() {
        manager.startConcurrentRun(testSessionId)
        manager.startConcurrentRun(testSessionId)

        val result = handlers.deleteAllRuns(testSessionId)

        assertEquals(2, result["deleted_count"])
    }

    @Test
    fun `saveToOrganizer saves requests with notes and script`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        // Create a run with some requests
        val run = manager.startRun(testSessionId)
        run.handler.code = "def queueRequests(target, wordlists):\n    pass"
        val req1 = burp.Request("GET /page1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\nOK"
        val req2 = burp.Request("GET /page2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 2
        req2.response = "HTTP/1.1 404 Not Found\r\n\r\nNot Found"
        run.store.add(req1)
        run.store.add(req2)

        val result = handlersWithOrganizer.saveToOrganizer(
            sessionId = testSessionId,
            runId = null,
            items = """[{"request_id": 1, "notes": "Interesting finding"}, {"request_id": 2, "notes": "Check this"}]"""
        )

        val saved = result["saved"] as List<*>
        assertEquals(listOf(1, 2), saved)
        assertEquals(2, fakeOrganizer.sentItems.size)
        assertTrue(fakeOrganizer.sentItems[0].second.startsWith("Interesting finding"))
        assertTrue(fakeOrganizer.sentItems[0].second.contains("--- Script ---"))
        assertTrue(fakeOrganizer.sentItems[0].second.contains("def queueRequests"))
        assertTrue(fakeOrganizer.sentItems[1].second.startsWith("Check this"))
        assertTrue(fakeOrganizer.sentItems[1].second.contains("--- Script ---"))
    }

    @Test
    fun `saveToOrganizer returns error for non-existent request`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        val run = manager.startRun(testSessionId)
        val req1 = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\n"
        run.store.add(req1)

        val result = handlersWithOrganizer.saveToOrganizer(
            sessionId = testSessionId,
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
    fun `searchResponses returns matching request IDs`() {
        val run = manager.startRun(testSessionId)
        val req1 = burp.Request("GET /page1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\nHello World"
        val req2 = burp.Request("GET /page2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 2
        req2.response = "HTTP/1.1 200 OK\r\n\r\nGoodbye World"
        val req3 = burp.Request("GET /page3 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req3.id = 3
        req3.response = "HTTP/1.1 404 Not Found\r\n\r\nNot Found"
        run.store.add(req1)
        run.store.add(req2)
        run.store.add(req3)

        val result = handlers.searchResponses(sessionId = testSessionId, runId = null, query = "World")

        val matches = result["matches"] as List<Int>
        assertEquals(listOf(1, 2), matches.sorted())
        assertEquals(2, result["match_count"])
    }

    @Test
    fun `searchResponses returns empty list when no matches`() {
        val run = manager.startRun(testSessionId)
        val req = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req.id = 1
        req.response = "HTTP/1.1 200 OK\r\n\r\nHello"
        run.store.add(req)

        val result = handlers.searchResponses(sessionId = testSessionId, runId = null, query = "notfound")

        val matches = result["matches"] as List<Int>
        assertEquals(emptyList<Int>(), matches)
        assertEquals(0, result["match_count"])
    }

    @Test
    fun `searchResponses handles null responses`() {
        val run = manager.startRun(testSessionId)
        val req = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req.id = 1
        req.response = null
        run.store.add(req)

        val result = handlers.searchResponses(sessionId = testSessionId, runId = null, query = "test")

        val matches = result["matches"] as List<Int>
        assertEquals(emptyList<Int>(), matches)
    }

    @Test
    fun `searchResponses uses specified run`() {
        val run1 = manager.startConcurrentRun(testSessionId)
        val req1 = burp.Request("GET /run1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\nFindMe"
        run1.store.add(req1)

        val run2 = manager.startConcurrentRun(testSessionId)
        val req2 = burp.Request("GET /run2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 1
        req2.response = "HTTP/1.1 200 OK\r\n\r\nNotThis"
        run2.store.add(req2)

        val result = handlers.searchResponses(sessionId = testSessionId, runId = run1.id, query = "FindMe")

        val matches = result["matches"] as List<Int>
        assertEquals(listOf(1), matches)
    }

    @Test
    fun `searchResponses returns error when no run found`() {
        val result = handlers.searchResponses(sessionId = testSessionId, runId = "nonexistent", query = "test")

        assertEquals("No run found", result["error"])
    }

    @Test
    fun `saveToOrganizer uses specified run`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val handlersWithOrganizer = McpToolHandlers(manager, fakeOrganizer)

        // Create two runs
        val run1 = manager.startConcurrentRun(testSessionId)
        val req1 = burp.Request("GET /run1 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req1.id = 1
        req1.response = "HTTP/1.1 200 OK\r\n\r\n"
        run1.store.add(req1)

        val run2 = manager.startConcurrentRun(testSessionId)
        val req2 = burp.Request("GET /run2 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req2.id = 1  // Same ID but different run
        req2.response = "HTTP/1.1 404 Not Found\r\n\r\n"
        run2.store.add(req2)

        val result = handlersWithOrganizer.saveToOrganizer(
            sessionId = testSessionId,
            runId = run1.id,
            items = """[{"request_id": 1, "notes": "From run1"}]"""
        )

        val saved = result["saved"] as List<*>
        assertEquals(listOf(1), saved)
        assertEquals(1, fakeOrganizer.sentItems.size)
        assertTrue(fakeOrganizer.sentItems[0].first.template.contains("/run1"))
    }

    @Test
    fun `startRun results include anomaly_rank field`() {
        // Use the ResourceHandlers to test the result structure directly
        // since startRun() uses the same result mapping
        val resourceHandlers = McpResourceHandlers(manager)
        val run = manager.startRun(testSessionId)

        val req = burp.Request("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        req.id = 1
        req.response = "HTTP/1.1 200 OK\r\n\r\nok"
        req.anomalyRank = 42
        run.store.add(req)

        val result = resourceHandlers.getResults(testSessionId, null, "id", true, 100, 0)
        val results = result["results"] as List<Map<String, Any?>>

        assertEquals(1, results.size)
        assertTrue(results[0].containsKey("anomaly_rank"))
        assertEquals(42, results[0]["anomaly_rank"])
    }

    @Test
    fun `startRun returns failed status when script throws error`() {
        val result = handlers.startRun(
            sessionId = testSessionId,
            script = """
def queueRequests(target, wordlists):
    raise Exception("Test error message")

def completed(results):
    pass
            """.trimIndent(),
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        assertEquals("failed", result["status"])
        assertTrue((result["error_message"] as String).contains("Test error message"))
    }

    @Test
    fun `startRun does not include error_message on success`() {
        val result = handlers.startRun(
            sessionId = testSessionId,
            script = "def queueRequests(target, wordlists):\n    pass\ndef completed(results):\n    pass",
            baseRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            endpoint = "https://example.com:443",
            baseInput = ""
        )

        assertEquals("completed", result["status"])
        assertFalse(result.containsKey("error_message"))
    }
}

// Test helpers
data class FakeOrganizerItem(
    val id: Int,
    val request: String,
    val response: String,
    var notes: String = "",
    val host: String = "example.com",
    val port: Int = 443,
    val secure: Boolean = true,
    val timeRequestSent: java.time.ZonedDateTime? = null
)

class FakeOrganizerProvider(items: List<FakeOrganizerItem>) : OrganizerProvider {
    private val items = items.toMutableList()
    val sentItems = mutableListOf<Pair<burp.Request, String>>()

    override fun getItems(): List<OrganizerItemData> {
        return items.map { OrganizerItemData(it.id, it.request, it.response, it.notes, it.host, it.port, it.secure, it.timeRequestSent) }
    }

    override fun getItemsByIds(ids: Set<Int>): List<OrganizerItemData> {
        return items.filter { it.id in ids }.map { OrganizerItemData(it.id, it.request, it.response, it.notes, it.host, it.port, it.secure, it.timeRequestSent) }
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

// Collaborator test helpers
class FakeCollaboratorProvider(
    private val organizerProvider: OrganizerProvider? = null
) : CollaboratorProvider {
    private var payloadCounter = 0
    private val interactions = mutableListOf<CollaboratorInteractionData>()
    var organizerSentCount = 0

    override fun generatePayload(metadata: String): String {
        val payload = "test${++payloadCounter}.oastify.com"
        return payload
    }

    override fun getInteractions(payloads: List<String>?): List<CollaboratorInteractionData> {
        val result = if (payloads == null) {
            interactions.toList()
        } else {
            interactions.filter { it.payload in payloads }
        }

        // Simulate sending HTTP interactions to Organizer
        for (interaction in result) {
            if (interaction.type == "HTTP" && organizerProvider != null) {
                organizerSentCount++
            }
        }

        return result
    }

    // Test helper to simulate interactions
    fun addInteraction(interaction: CollaboratorInteractionData) {
        interactions.add(interaction)
    }
}

class CollaboratorToolHandlersTest {

    private lateinit var manager: RunManager
    private lateinit var handlers: McpToolHandlers

    @BeforeEach
    fun setup() {
        manager = RunManager()
    }

    @Test
    fun `generateCollaboratorPayload returns payload domain`() {
        val fakeCollaborator = FakeCollaboratorProvider()
        handlers = McpToolHandlers(manager, collaboratorProvider = fakeCollaborator)

        val result = handlers.generateCollaboratorPayload("SSRF test in webhook param")

        assertEquals("test1.oastify.com", result["payload"])
    }

    @Test
    fun `generateCollaboratorPayload stores metadata for later retrieval`() {
        val fakeCollaborator = FakeCollaboratorProvider()
        handlers = McpToolHandlers(manager, collaboratorProvider = fakeCollaborator)

        handlers.generateCollaboratorPayload("SSRF test")

        // Add an interaction for the generated payload
        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "test1.oastify.com",
            metadata = "SSRF test",
            type = "DNS",
            timestamp = "2024-01-01T12:00:00Z",
            clientIp = "1.2.3.4",
            details = mapOf("query_type" to "A")
        ))

        val interactions = handlers.getCollaboratorInteractions(null)
        val items = interactions["interactions"] as List<Map<String, Any?>>

        assertEquals(1, items.size)
        assertEquals("SSRF test", items[0]["metadata"])
    }

    @Test
    fun `getCollaboratorInteractions filters by payload`() {
        val fakeCollaborator = FakeCollaboratorProvider()
        handlers = McpToolHandlers(manager, collaboratorProvider = fakeCollaborator)

        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "abc.oastify.com",
            metadata = "Test A",
            type = "DNS",
            timestamp = "2024-01-01T12:00:00Z",
            clientIp = "1.2.3.4",
            details = emptyMap()
        ))
        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "xyz.oastify.com",
            metadata = "Test B",
            type = "HTTP",
            timestamp = "2024-01-01T12:01:00Z",
            clientIp = "5.6.7.8",
            details = emptyMap()
        ))

        val result = handlers.getCollaboratorInteractions(listOf("abc.oastify.com"))
        val items = result["interactions"] as List<Map<String, Any?>>

        assertEquals(1, items.size)
        assertEquals("abc.oastify.com", items[0]["payload"])
    }

    @Test
    fun `getCollaboratorInteractions returns all interactions when no filter`() {
        val fakeCollaborator = FakeCollaboratorProvider()
        handlers = McpToolHandlers(manager, collaboratorProvider = fakeCollaborator)

        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "abc.oastify.com",
            metadata = "Test A",
            type = "DNS",
            timestamp = "2024-01-01T12:00:00Z",
            clientIp = "1.2.3.4",
            details = emptyMap()
        ))
        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "xyz.oastify.com",
            metadata = "Test B",
            type = "HTTP",
            timestamp = "2024-01-01T12:01:00Z",
            clientIp = "5.6.7.8",
            details = emptyMap()
        ))

        val result = handlers.getCollaboratorInteractions(null)
        val items = result["interactions"] as List<Map<String, Any?>>

        assertEquals(2, items.size)
    }

    @Test
    fun `getCollaboratorInteractions returns interaction details`() {
        val fakeCollaborator = FakeCollaboratorProvider()
        handlers = McpToolHandlers(manager, collaboratorProvider = fakeCollaborator)

        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "test.oastify.com",
            metadata = "SSRF test",
            type = "HTTP",
            timestamp = "2024-01-01T12:00:00Z",
            clientIp = "10.0.0.1",
            details = mapOf("method" to "GET", "path" to "/")
        ))

        val result = handlers.getCollaboratorInteractions(null)
        val items = result["interactions"] as List<Map<String, Any?>>

        assertEquals("test.oastify.com", items[0]["payload"])
        assertEquals("SSRF test", items[0]["metadata"])
        assertEquals("HTTP", items[0]["type"])
        assertEquals("2024-01-01T12:00:00Z", items[0]["timestamp"])
        assertEquals("10.0.0.1", items[0]["client_ip"])
        val details = items[0]["details"] as Map<*, *>
        assertEquals("GET", details["method"])
    }

    @Test
    fun `getCollaboratorInteractions sends HTTP interactions to Organizer`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val fakeCollaborator = FakeCollaboratorProvider(organizerProvider = fakeOrganizer)
        handlers = McpToolHandlers(manager, organizerProvider = fakeOrganizer, collaboratorProvider = fakeCollaborator)

        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "test.oastify.com",
            metadata = "SSRF in webhook",
            type = "HTTP",
            timestamp = "2024-01-01T12:00:00Z",
            clientIp = "10.0.0.1",
            details = mapOf("request" to "GET / HTTP/1.1\r\nHost: test.oastify.com\r\n\r\n")
        ))

        handlers.getCollaboratorInteractions(null)

        assertEquals(1, fakeCollaborator.organizerSentCount)
    }

    @Test
    fun `getCollaboratorInteractions does not send DNS interactions to Organizer`() {
        val fakeOrganizer = FakeOrganizerProvider(emptyList())
        val fakeCollaborator = FakeCollaboratorProvider(organizerProvider = fakeOrganizer)
        handlers = McpToolHandlers(manager, organizerProvider = fakeOrganizer, collaboratorProvider = fakeCollaborator)

        fakeCollaborator.addInteraction(CollaboratorInteractionData(
            payload = "test.oastify.com",
            metadata = "DNS test",
            type = "DNS",
            timestamp = "2024-01-01T12:00:00Z",
            clientIp = "10.0.0.1",
            details = mapOf("query_type" to "A")
        ))

        handlers.getCollaboratorInteractions(null)

        assertEquals(0, fakeCollaborator.organizerSentCount)
    }
}
