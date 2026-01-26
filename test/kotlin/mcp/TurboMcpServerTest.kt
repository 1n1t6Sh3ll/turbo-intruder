package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.ServerSocket

class TurboMcpServerTest {

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @Test
    fun `stateless mode is enabled by default`() {
        assertTrue(TurboMcpServer.STATELESS_MODE)
    }

    @Test
    fun `server can be created with port`() {
        val server = TurboMcpServer(port = 31337)
        assertNotNull(server)
    }

    @Test
    fun `server exposes tool and resource handlers`() {
        val server = TurboMcpServer(port = 31337)
        assertNotNull(server.toolHandlers)
        assertNotNull(server.resourceHandlers)
    }

    @Test
    fun `all tools enabled by default`() {
        val server = TurboMcpServer(port = 31337)
        val toolNames = server.getEnabledToolNames()
        assertTrue(toolNames.contains("start_run"))
        assertTrue(toolNames.contains("set_organizer_notes"))
        assertTrue(toolNames.contains("save_to_organizer"))
    }

    @Test
    fun `disabled tools are excluded`() {
        val server = TurboMcpServer(
            port = 31337,
            disabledTools = setOf("start_run", "start_concurrent_run")
        )
        val toolNames = server.getEnabledToolNames()
        assertFalse(toolNames.contains("start_run"))
        assertFalse(toolNames.contains("start_concurrent_run"))
        assertTrue(toolNames.contains("stop_run"))
        assertTrue(toolNames.contains("set_organizer_notes"))
    }

    @Test
    fun `server starts and stops with stateless transport`() {
        assertTrue(TurboMcpServer.STATELESS_MODE, "Stateless mode should be enabled")

        val port = findFreePort()
        val server = TurboMcpServer(port = port)

        // Should not throw
        server.start()

        // Server should be listening - verify by trying to start another server on same port
        val conflictException = assertThrows(Exception::class.java) {
            val server2 = TurboMcpServer(port = port)
            server2.start()
        }
        assertTrue(conflictException.message?.contains("Address already in use") == true ||
                   conflictException.cause?.message?.contains("Address already in use") == true,
            "Port should be in use")

        server.stop()
    }

    @Test
    fun `organizer list resource supports domain filter via URI template`() {
        val fakeOrganizer = FakeOrganizerProvider(listOf(
            FakeOrganizerItem(1, "GET /1 HTTP/1.1", "HTTP/1.1 200 OK", host = "target.com"),
            FakeOrganizerItem(2, "GET /2 HTTP/1.1", "HTTP/1.1 200 OK", host = "other.com"),
            FakeOrganizerItem(3, "GET /3 HTTP/1.1", "HTTP/1.1 200 OK", host = "target.com")
        ))
        val server = TurboMcpServer(port = 31337, organizerProvider = fakeOrganizer)

        // Test that resourceHandlers correctly handles domain filter
        val result = server.resourceHandlers.listOrganizerItems(domain = "target.com")

        assertEquals(2, result["count"])
        assertEquals(1, result["page"])
        assertEquals(10, result["page_size"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        // Sorted by ID descending (no timestamps)
        assertEquals(listOf(3, 1), items.map { it["id"] })
    }

    @Test
    fun `organizer list resource supports pagination via URI template`() {
        // Create 25 items
        val items = (1..25).map {
            FakeOrganizerItem(it, "GET /$it HTTP/1.1", "HTTP/1.1 200 OK", host = "target.com")
        }
        val fakeOrganizer = FakeOrganizerProvider(items)
        val server = TurboMcpServer(port = 31337, organizerProvider = fakeOrganizer)

        val page1 = server.resourceHandlers.listOrganizerItems(domain = "target.com", page = 1)
        val page2 = server.resourceHandlers.listOrganizerItems(domain = "target.com", page = 2)
        val page3 = server.resourceHandlers.listOrganizerItems(domain = "target.com", page = 3)

        assertEquals(25, page1["count"])
        assertEquals(1, page1["page"])
        assertEquals(3, page1["total_pages"])

        assertEquals(2, page2["page"])
        assertEquals(3, page3["page"])

        @Suppress("UNCHECKED_CAST")
        val page1Items = page1["items"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val page3Items = page3["items"] as List<Map<String, Any?>>

        assertEquals(10, page1Items.size)
        assertEquals(5, page3Items.size)
    }
}
