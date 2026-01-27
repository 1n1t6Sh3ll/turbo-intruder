package mcp.resource

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceDefinitionTest {

    @Test
    fun `matches exact URI without path params`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs",
            name = "List runs",
            baseDescription = "List all runs",
            pathParams = emptyList(),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        assertTrue(def.matches("turbo://runs"))
        assertFalse(def.matches("turbo://runs/abc"))
        assertFalse(def.matches("turbo://other"))
    }

    @Test
    fun `matches URI with query params stripped`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://organizer",
            name = "List organizer",
            baseDescription = "List organizer items",
            pathParams = emptyList(),
            queryParams = listOf(
                ResourceParam.QueryParam("domain", ParamType.STRING, null, "filter")
            ),
            handler = { _, _ -> emptyMap() }
        )

        assertTrue(def.matches("turbo://organizer"))
        assertTrue(def.matches("turbo://organizer?domain=foo"))
        assertTrue(def.matches("turbo://organizer?domain=foo&page=2"))
        assertFalse(def.matches("turbo://organizer/123"))
    }

    @Test
    fun `matches URI template with path params`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}",
            name = "Run status",
            baseDescription = "Get run status",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        assertTrue(def.matches("turbo://runs/abc123"))
        assertTrue(def.matches("turbo://runs/current"))
        assertFalse(def.matches("turbo://runs"))
        assertFalse(def.matches("turbo://runs/abc/extra"))
    }

    @Test
    fun `matches URI template with multiple path params`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}/{id}",
            name = "Request detail",
            baseDescription = "Get request detail",
            pathParams = listOf(
                ResourceParam.PathParam("run_id"),
                ResourceParam.PathParam("id")
            ),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        assertTrue(def.matches("turbo://runs/abc/123"))
        assertTrue(def.matches("turbo://runs/current/456"))
        assertFalse(def.matches("turbo://runs/abc"))
        assertFalse(def.matches("turbo://runs"))
    }

    @Test
    fun `matches URI template with path params and query params`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}/summary",
            name = "Run summary",
            baseDescription = "Get run summary",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = listOf(
                ResourceParam.QueryParam("limit", ParamType.INT, 100, "max results")
            ),
            handler = { _, _ -> emptyMap() }
        )

        assertTrue(def.matches("turbo://runs/abc/summary"))
        assertTrue(def.matches("turbo://runs/abc/summary?limit=50"))
        assertFalse(def.matches("turbo://runs/abc"))
    }

    @Test
    fun `parseParams extracts path params`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}",
            name = "Run status",
            baseDescription = "Get run status",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        val params = def.parseParams("turbo://runs/abc123")

        assertEquals("abc123", params.path("run_id"))
    }

    @Test
    fun `parseParams extracts multiple path params`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}/{id}",
            name = "Request detail",
            baseDescription = "Get request detail",
            pathParams = listOf(
                ResourceParam.PathParam("run_id"),
                ResourceParam.PathParam("id")
            ),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        val params = def.parseParams("turbo://runs/abc/123")

        assertEquals("abc", params.path("run_id"))
        assertEquals("123", params.path("id"))
    }

    @Test
    fun `parseParams extracts query params`() {
        val queryDef = ResourceParam.QueryParam("limit", ParamType.INT, 100, "max")
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}/summary",
            name = "Run summary",
            baseDescription = "Get run summary",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = listOf(queryDef),
            handler = { _, _ -> emptyMap() }
        )

        val params = def.parseParams("turbo://runs/abc/summary?limit=50")

        assertEquals("abc", params.path("run_id"))
        assertEquals(50, params.int("limit"))
    }

    @Test
    fun `parseParams uses default for missing query param`() {
        val queryDef = ResourceParam.QueryParam("limit", ParamType.INT, 100, "max")
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}/summary",
            name = "Run summary",
            baseDescription = "Get run summary",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = listOf(queryDef),
            handler = { _, _ -> emptyMap() }
        )

        val params = def.parseParams("turbo://runs/abc/summary")

        assertEquals(100, params.int("limit"))
    }

    @Test
    fun `fullDescription includes query param documentation`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}/summary",
            name = "Run summary",
            baseDescription = "Get paginated summary from a run",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = listOf(
                ResourceParam.QueryParam("sort_by", ParamType.STRING, "id", "sort field"),
                ResourceParam.QueryParam("limit", ParamType.INT, 100, "max results")
            ),
            handler = { _, _ -> emptyMap() }
        )

        val description = def.fullDescription

        assertTrue(description.startsWith("Get paginated summary from a run"))
        assertTrue(description.contains("sort_by"))
        assertTrue(description.contains("default: id"))
        assertTrue(description.contains("limit"))
        assertTrue(description.contains("default: 100"))
    }

    @Test
    fun `fullDescription without query params is just base description`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs",
            name = "List runs",
            baseDescription = "List all runs",
            pathParams = emptyList(),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        assertEquals("List all runs", def.fullDescription)
    }

    @Test
    fun `baseUri returns the pattern`() {
        val def = ResourceDefinition(
            uriPattern = "turbo://runs/{run_id}",
            name = "Run status",
            baseDescription = "Get run status",
            pathParams = listOf(ResourceParam.PathParam("run_id")),
            queryParams = emptyList(),
            handler = { _, _ -> emptyMap() }
        )

        assertEquals("turbo://runs/{run_id}", def.baseUri)
    }
}
