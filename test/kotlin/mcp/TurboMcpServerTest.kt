package mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TurboMcpServerTest {

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
        assertTrue(toolNames.contains("get_organizer_items"))
        assertTrue(toolNames.contains("set_organizer_notes"))
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
        assertTrue(toolNames.contains("get_organizer_items"))
    }
}
