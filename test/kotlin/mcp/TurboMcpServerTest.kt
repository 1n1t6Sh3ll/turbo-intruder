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
}
