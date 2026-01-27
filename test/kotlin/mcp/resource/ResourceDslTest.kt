package mcp.resource

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceDslTest {

    @Test
    fun `resource DSL creates definition with basic properties`() {
        val def = resource("turbo://runs") {
            name = "List runs"
            description = "List all runs"
            handle { _, _ -> mapOf("runs" to emptyList<Any>()) }
        }

        assertEquals("turbo://runs", def.uriPattern)
        assertEquals("List runs", def.name)
        assertEquals("List all runs", def.baseDescription)
        assertEquals("application/json", def.mimeType)
        assertTrue(def.pathParams.isEmpty())
        assertTrue(def.queryParams.isEmpty())
    }

    @Test
    fun `resource DSL auto-extracts path params from URI`() {
        val def = resource("turbo://runs/{run_id}") {
            name = "Run status"
            description = "Get run status"
            handle { _, _ -> emptyMap() }
        }

        assertEquals(1, def.pathParams.size)
        assertEquals("run_id", def.pathParams[0].name)
    }

    @Test
    fun `resource DSL auto-extracts multiple path params`() {
        val def = resource("turbo://runs/{run_id}/{id}") {
            name = "Request detail"
            description = "Get request detail"
            handle { _, _ -> emptyMap() }
        }

        assertEquals(2, def.pathParams.size)
        assertEquals("run_id", def.pathParams[0].name)
        assertEquals("id", def.pathParams[1].name)
    }

    @Test
    fun `resource DSL supports queryString`() {
        val def = resource("turbo://runs/{run_id}/summary") {
            name = "Run summary"
            description = "Get run summary"
            queryString("sort_by", default = "id", description = "sort field")
            handle { _, _ -> emptyMap() }
        }

        assertEquals(1, def.queryParams.size)
        assertEquals("sort_by", def.queryParams[0].name)
        assertEquals(ParamType.STRING, def.queryParams[0].type)
        assertEquals("id", def.queryParams[0].default)
        assertEquals("sort field", def.queryParams[0].description)
    }

    @Test
    fun `resource DSL supports queryInt`() {
        val def = resource("turbo://organizer") {
            name = "List organizer"
            description = "List organizer items"
            queryInt("page", default = 1, description = "page number")
            handle { _, _ -> emptyMap() }
        }

        assertEquals(1, def.queryParams.size)
        assertEquals("page", def.queryParams[0].name)
        assertEquals(ParamType.INT, def.queryParams[0].type)
        assertEquals(1, def.queryParams[0].default)
    }

    @Test
    fun `resource DSL supports queryBool`() {
        val def = resource("turbo://runs/{run_id}/summary") {
            name = "Run summary"
            description = "Get run summary"
            queryBool("descending", default = true, description = "sort order")
            handle { _, _ -> emptyMap() }
        }

        assertEquals(1, def.queryParams.size)
        assertEquals("descending", def.queryParams[0].name)
        assertEquals(ParamType.BOOL, def.queryParams[0].type)
        assertEquals(true, def.queryParams[0].default)
    }

    @Test
    fun `resource DSL supports custom mimeType`() {
        val def = resource("turbo://docs/{topic}") {
            name = "Doc topic"
            description = "Get documentation"
            mimeType = "text/markdown"
            handle { _, _ -> emptyMap() }
        }

        assertEquals("text/markdown", def.mimeType)
    }

    @Test
    fun `resource DSL handler receives session and params`() {
        var capturedSessionId: String? = null
        var capturedParams: ParsedParams? = null

        val def = resource("turbo://runs/{run_id}") {
            name = "Run status"
            description = "Get run status"
            handle { sessionId, params ->
                capturedSessionId = sessionId
                capturedParams = params
                mapOf("status" to "ok")
            }
        }

        val params = def.parseParams("turbo://runs/abc123")
        val result = def.handler("test-session", params)

        assertEquals("test-session", capturedSessionId)
        assertEquals("abc123", capturedParams?.path("run_id"))
        assertEquals("ok", result["status"])
    }

    @Test
    fun `resource DSL requires name`() {
        assertThrows(IllegalArgumentException::class.java) {
            resource("turbo://runs") {
                description = "List all runs"
                handle { _, _ -> emptyMap() }
            }
        }
    }

    @Test
    fun `resource DSL requires description`() {
        assertThrows(IllegalArgumentException::class.java) {
            resource("turbo://runs") {
                name = "List runs"
                handle { _, _ -> emptyMap() }
            }
        }
    }

    @Test
    fun `resource DSL requires handler`() {
        assertThrows(IllegalArgumentException::class.java) {
            resource("turbo://runs") {
                name = "List runs"
                description = "List all runs"
            }
        }
    }
}
