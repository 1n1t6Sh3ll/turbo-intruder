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
}
