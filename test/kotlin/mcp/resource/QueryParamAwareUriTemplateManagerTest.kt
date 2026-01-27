package mcp.resource

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QueryParamAwareUriTemplateManagerTest {

    @Test
    fun `matches exact URI without query params`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://organizer")

        assertTrue(manager.matches("turbo://organizer"))
    }

    @Test
    fun `matches URI with query params stripped`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://organizer")

        assertTrue(manager.matches("turbo://organizer?domain=foo"))
        assertTrue(manager.matches("turbo://organizer?domain=foo&page=2"))
    }

    @Test
    fun `does not match different base URI`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://organizer")

        assertFalse(manager.matches("turbo://runs"))
        assertFalse(manager.matches("turbo://organizer/123"))
    }

    @Test
    fun `matches URI template with path variable`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}")

        assertTrue(manager.matches("turbo://runs/abc123"))
        assertTrue(manager.matches("turbo://runs/current"))
    }

    @Test
    fun `matches URI template with query params stripped`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}/summary")

        assertTrue(manager.matches("turbo://runs/abc/summary"))
        assertTrue(manager.matches("turbo://runs/abc/summary?limit=50"))
        assertTrue(manager.matches("turbo://runs/abc/summary?sort_by=id&descending=true"))
    }

    @Test
    fun `extracts variable values without query params`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}")

        val values = manager.extractVariableValues("turbo://runs/abc123")

        assertEquals(mapOf("run_id" to "abc123"), values)
    }

    @Test
    fun `extracts variable values with query params stripped`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}")

        val values = manager.extractVariableValues("turbo://runs/abc123?limit=50")

        assertEquals(mapOf("run_id" to "abc123"), values)
    }

    @Test
    fun `extracts multiple variable values`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}/{id}")

        val values = manager.extractVariableValues("turbo://runs/abc/123?body_limit=500")

        assertEquals(mapOf("run_id" to "abc", "id" to "123"), values)
    }

    @Test
    fun `getVariableNames returns path variable names`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}/{id}")

        assertEquals(listOf("run_id", "id"), manager.variableNames)
    }

    @Test
    fun `getVariableNames returns empty for non-template`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://organizer")

        assertEquals(emptyList<String>(), manager.variableNames)
    }

    @Test
    fun `isUriTemplate returns true for template`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://runs/{run_id}")

        assertTrue(manager.isUriTemplate("turbo://runs/{run_id}"))
    }

    @Test
    fun `isUriTemplate returns false for non-template`() {
        val manager = QueryParamAwareUriTemplateManager("turbo://organizer")

        assertFalse(manager.isUriTemplate("turbo://organizer"))
    }
}

class QueryParamAwareUriTemplateManagerFactoryTest {

    @Test
    fun `factory creates manager for given template`() {
        val factory = QueryParamAwareUriTemplateManagerFactory()

        val manager = factory.create("turbo://runs/{run_id}")

        assertTrue(manager.matches("turbo://runs/abc"))
        assertTrue(manager.matches("turbo://runs/abc?limit=50"))
    }

    @Test
    fun `factory creates managers that handle query params`() {
        val factory = QueryParamAwareUriTemplateManagerFactory()

        // Non-template URI with query params
        val organizerManager = factory.create("turbo://organizer")
        assertTrue(organizerManager.matches("turbo://organizer"))
        assertTrue(organizerManager.matches("turbo://organizer?domain=foo"))
    }
}
