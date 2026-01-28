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

        assertTrue(uris.contains("turbo://runs"))
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
    fun `runs list definition has correct metadata`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs" }!!

        assertEquals("List of all runs", def.name)
        assertTrue(def.baseDescription.contains("List all runs"))
        assertEquals("application/json", def.mimeType)
        assertTrue(def.pathParams.isEmpty())
        assertTrue(def.queryParams.isEmpty())
    }

    @Test
    fun `run status definition extracts run_id path param`() {
        val definitions = createResourceDefinitions(handlers)
        val def = definitions.find { it.uriPattern == "turbo://runs/{run_id}" }!!

        assertEquals(1, def.pathParams.size)
        assertEquals("run_id", def.pathParams[0].name)
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
        val def = definitions.find { it.uriPattern == "turbo://runs" }!!

        val params = def.parseParams("turbo://runs")
        val result = def.handler("test-session", params)

        // Should return runs list (empty since no runs exist)
        assertTrue(result.containsKey("runs"))
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
}
