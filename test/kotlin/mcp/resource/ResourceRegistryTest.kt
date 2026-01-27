package mcp.resource

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceRegistryTest {

    @Test
    fun `findResource matches exact URI`() {
        val registry = ResourceRegistry()
        val def = resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> mapOf("runs" to emptyList<Any>()) }
        }
        registry.register(def)

        val found = registry.findResource("turbo://runs")

        assertNotNull(found)
        assertEquals("turbo://runs", found?.uriPattern)
    }

    @Test
    fun `findResource matches URI with query params`() {
        val registry = ResourceRegistry()
        val def = resource("turbo://organizer") {
            name = "List organizer"
            description = "List organizer items"
            queryString("domain", description = "filter domain")
            handle { _, _ -> emptyMap() }
        }
        registry.register(def)

        val found = registry.findResource("turbo://organizer?domain=example.com")

        assertNotNull(found)
        assertEquals("turbo://organizer", found?.uriPattern)
    }

    @Test
    fun `findResource matches URI template`() {
        val registry = ResourceRegistry()
        val def = resource("turbo://runs/{run_id}") {
            name = "Run status"
            description = "Get run status"
            handle { _, _ -> emptyMap() }
        }
        registry.register(def)

        val found = registry.findResource("turbo://runs/abc123")

        assertNotNull(found)
        assertEquals("turbo://runs/{run_id}", found?.uriPattern)
    }

    @Test
    fun `findResource matches most specific pattern`() {
        val registry = ResourceRegistry()

        // Register in order: more specific patterns should match even if registered later
        registry.register(resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> mapOf("type" to "list") }
        })
        registry.register(resource("turbo://runs/{run_id}") {
            name = "Run status"
            description = "Get run status"
            handle { _, _ -> mapOf("type" to "status") }
        })
        registry.register(resource("turbo://runs/{run_id}/summary") {
            name = "Run summary"
            description = "Get run summary"
            handle { _, _ -> mapOf("type" to "summary") }
        })

        assertEquals("turbo://runs", registry.findResource("turbo://runs")?.uriPattern)
        assertEquals("turbo://runs/{run_id}", registry.findResource("turbo://runs/abc")?.uriPattern)
        assertEquals("turbo://runs/{run_id}/summary", registry.findResource("turbo://runs/abc/summary")?.uriPattern)
    }

    @Test
    fun `findResource returns null for unknown URI`() {
        val registry = ResourceRegistry()
        registry.register(resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> emptyMap() }
        })

        val found = registry.findResource("turbo://unknown")

        assertNull(found)
    }

    @Test
    fun `register accepts vararg definitions`() {
        val registry = ResourceRegistry()

        val def1 = resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> emptyMap() }
        }
        val def2 = resource("turbo://docs") {
            name = "List docs"
            description = "List docs"
            handle { _, _ -> emptyMap() }
        }

        registry.register(def1, def2)

        assertNotNull(registry.findResource("turbo://runs"))
        assertNotNull(registry.findResource("turbo://docs"))
    }

    @Test
    fun `getDefinitions returns all registered definitions`() {
        val registry = ResourceRegistry()

        registry.register(resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> emptyMap() }
        })
        registry.register(resource("turbo://docs") {
            name = "List docs"
            description = "List docs"
            handle { _, _ -> emptyMap() }
        })

        val definitions = registry.getDefinitions()

        assertEquals(2, definitions.size)
    }

    @Test
    fun `buildStatelessSpecs generates specs for all definitions`() {
        val registry = ResourceRegistry(ObjectMapper())
        registry.register(resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> mapOf("runs" to emptyList<Any>()) }
        })
        registry.register(resource("turbo://docs") {
            name = "List docs"
            description = "List docs"
            handle { _, _ -> mapOf("topics" to emptyList<Any>()) }
        })

        val specs = registry.buildStatelessSpecs()

        assertEquals(2, specs.size)
        val uris = specs.map { it.resource().uri() }.toSet()
        assertTrue(uris.contains("turbo://runs"))
        assertTrue(uris.contains("turbo://docs"))
    }

    @Test
    fun `buildStatelessSpecs generates spec with full description including query params`() {
        val registry = ResourceRegistry(ObjectMapper())
        registry.register(resource("turbo://organizer") {
            name = "List organizer"
            description = "List organizer items"
            queryString("domain", description = "filter by host")
            queryInt("page", default = 1)
            handle { _, _ -> emptyMap() }
        })

        val specs = registry.buildStatelessSpecs()

        assertEquals(1, specs.size)
        val description = specs[0].resource().description()
        assertTrue(description.contains("domain"))
        assertTrue(description.contains("page"))
        assertTrue(description.contains("default: 1"))
    }

    @Test
    fun `buildStatefulSpecs generates specs for all definitions`() {
        val registry = ResourceRegistry(ObjectMapper())
        registry.register(resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> mapOf("runs" to emptyList<Any>()) }
        })

        val specs = registry.buildStatefulSpecs()

        assertEquals(1, specs.size)
        assertEquals("turbo://runs", specs[0].resource().uri())
    }
}
