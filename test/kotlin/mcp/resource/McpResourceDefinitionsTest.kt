package mcp.resource

import mcp.McpResourceHandlers
import mcp.OrganizerProvider
import mcp.RunManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class McpResourceDefinitionsTest {

    private val manager = RunManager()
    private val handlers = McpResourceHandlers(manager, null) { false }

    @Test
    fun `creates all expected resource definitions`() {
        val definitions = createResourceDefinitions(handlers)

        val uris = definitions.map { it.uriPattern }.toSet()

        assertTrue(uris.contains("turbo://runs/{run_id}"))
        assertTrue(uris.contains("turbo://runs/{run_id}/summary"))
        assertTrue(uris.contains("turbo://runs/{run_id}/{id}"))
        assertTrue(uris.contains("turbo://organizer"))
        assertTrue(uris.contains("turbo://organizer/{id}"))
        assertTrue(uris.contains("turbo://docs/api-quickstart"))
        assertTrue(uris.contains("turbo://docs/engines"))
        assertTrue(uris.contains("turbo://docs/settings"))
        assertTrue(uris.contains("turbo://docs/race-conditions"))
        assertTrue(uris.contains("turbo://docs/response-processing"))
        assertTrue(uris.contains("turbo://docs/decorators"))
        assertTrue(uris.contains("turbo://docs/misc"))
    }

    @Test
    fun `run status definition extracts run_id path param`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}" }!!

        assertEquals(1, def.pathParams.size)
        assertEquals("run_id", def.pathParams[0].name)
    }

    @Test
    fun `run status definition has wait query param`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}" }!!

        val waitParam = def.queryParams.find { it.name == "wait" }
        assertNotNull(waitParam, "Should have a 'wait' query param")
        assertEquals(false, waitParam!!.default)
    }

    @Test
    fun `run summary definition has query params with defaults`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}/summary" }!!

        val paramNames = def.queryParams.map { it.name }.toSet()
        assertTrue(paramNames.contains("sort_by"))
        assertTrue(paramNames.contains("descending"))
        assertTrue(paramNames.contains("limit"))
        assertTrue(paramNames.contains("offset"))

        // Check defaults
        val sortBy = def.queryParams.find { it.name == "sort_by" }!!
        assertEquals("id", sortBy.default)

        val limit = def.queryParams.find { it.name == "limit" }!!
        assertEquals(100, limit.default)
    }

    @Test
    fun `request detail definition has body_limit and export params`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}/{id}" }!!

        val paramNames = def.queryParams.map { it.name }.toSet()
        assertTrue(paramNames.contains("body_limit"))
        assertTrue(paramNames.contains("export"))

        val bodyLimit = def.queryParams.find { it.name == "body_limit" }!!
        assertEquals(100, bodyLimit.default)
    }

    @Test
    fun `request detail alias with requests in path exists`() {
        val definitions = createResourceDefinitions(handlers)
        val alias = definitions.find { it.uriPattern == "turbo://runs/{run_id}/requests/{id}" }

        assertNotNull(alias, "Alias pattern turbo://runs/{run_id}/requests/{id} should exist")
        assertEquals("Result detail (alias)", alias!!.name)
    }

    @Test
    fun `organizer list definition has domain and page params`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://organizer" }!!

        val paramNames = def.queryParams.map { it.name }.toSet()
        assertTrue(paramNames.contains("domain"))
        assertTrue(paramNames.contains("page"))

        val page = def.queryParams.find { it.name == "page" }!!
        assertEquals(1, page.default)
    }

    @Test
    fun `organizer list definition has search params`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://organizer" }!!

        val paramNames = def.queryParams.map { it.name }.toSet()
        assertTrue(paramNames.contains("searchNotes"))
        assertTrue(paramNames.contains("searchRequest"))
        assertTrue(paramNames.contains("searchResponse"))
    }

    @Test
    fun `organizer item definition has body_limit param`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://organizer/{id}" }!!

        assertEquals(1, def.pathParams.size)
        assertEquals("id", def.pathParams[0].name)

        val bodyLimit = def.queryParams.find { it.name == "body_limit" }!!
        assertEquals(100, bodyLimit.default)
    }

    @Test
    fun `docs definitions have markdown mime type`() {
        val definitions = createResourceDefinitions(handlers)
        val docDefs = definitions.filter { it.uriPattern.startsWith("turbo://docs/") }

        assertEquals(7, docDefs.size)
        docDefs.forEach { def ->
            assertEquals("text/markdown", def.mimeType)
            assertTrue(def.pathParams.isEmpty(), "Doc resources should not have path params")
        }
    }

    @Test
    fun `handlers are invoked with correct params`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}" }!!

        val params = def.parseParams("turbo://runs/nonexistent")
        val result = def.handler(params)

        // Should return not_found since run doesn't exist
        assertEquals("not_found", result["error"])
    }

    @Test
    fun `run summary handler receives parsed query params`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}/summary" }!!

        val params = def.parseParams("turbo://runs/current/summary?limit=50&sort_by=length")

        assertEquals("current", params.path("run_id"))
        assertEquals(50, params.int("limit"))
        assertEquals("length", params.string("sort_by"))
        // Defaults should still work for unspecified params
        assertEquals(true, params.bool("descending"))
        assertEquals(0, params.int("offset"))
    }

    @Test
    fun `result detail handler delegates to summary when id is summary`() {
        // When the MCP SDK misroutes turbo://runs/{run_id}/summary to the {id} handler,
        // the handler should delegate to the summary handler instead of crashing
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}/{id}" }!!

        // Simulate the SDK misrouting /summary?limit=5 to the {id} handler
        val params = def.parseParams("turbo://runs/nonexistent/summary?limit=5")
        val result = def.handler(params)

        // Should delegate to summary handler (returns not_found for nonexistent run),
        // not crash with NumberFormatException
        assertTrue(
            result.containsKey("results") || result["error"] == "not_found",
            "Should delegate to summary handler, got: $result"
        )
    }

    // Example script resource tests

    @Test
    fun `examples list definition exists`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://examples" }

        assertNotNull(def, "turbo://examples should exist")
        assertEquals("Example Scripts", def!!.name)
    }

    @Test
    fun `example detail definition exists with name param`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://examples/{name}" }

        assertNotNull(def, "turbo://examples/{name} should exist")
        assertEquals(1, def!!.pathParams.size)
        assertEquals("name", def.pathParams[0].name)
        assertEquals("text/x-python", def.mimeType)
    }
}
