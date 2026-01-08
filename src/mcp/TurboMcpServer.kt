package mcp

class TurboMcpServer(private val port: Int = 31337) {

    private val manager = RunManager()
    val toolHandlers = McpToolHandlers(manager)
    val resourceHandlers = McpResourceHandlers(manager)

    fun start() {
        // TODO: Implement MCP server startup with actual MCP SDK integration
    }

    fun stop() {
        // TODO: Implement MCP server shutdown
    }
}
