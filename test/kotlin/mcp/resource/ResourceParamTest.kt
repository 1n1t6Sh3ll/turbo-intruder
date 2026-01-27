package mcp.resource

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceParamTest {

    @Test
    fun `PathParam is always required with string type`() {
        val param = ResourceParam.PathParam("run_id", "The run identifier")

        assertEquals("run_id", param.name)
        assertEquals(ParamType.STRING, param.type)
        assertEquals("The run identifier", param.description)
        assertTrue(param.required)
    }

    @Test
    fun `PathParam with empty description`() {
        val param = ResourceParam.PathParam("id")

        assertEquals("id", param.name)
        assertEquals("", param.description)
        assertTrue(param.required)
    }

    @Test
    fun `QueryParam with default is not required`() {
        val param = ResourceParam.QueryParam("limit", ParamType.INT, 100, "max results")

        assertEquals("limit", param.name)
        assertEquals(ParamType.INT, param.type)
        assertEquals(100, param.default)
        assertEquals("max results", param.description)
        assertFalse(param.required)
    }

    @Test
    fun `QueryParam without default is required`() {
        val param = ResourceParam.QueryParam<String>("domain", ParamType.STRING, null, "filter domain")

        assertEquals("domain", param.name)
        assertEquals(ParamType.STRING, param.type)
        assertNull(param.default)
        assertTrue(param.required)
    }

    @Test
    fun `QueryParam boolean type with default`() {
        val param = ResourceParam.QueryParam("descending", ParamType.BOOL, true, "sort order")

        assertEquals(ParamType.BOOL, param.type)
        assertEquals(true, param.default)
        assertFalse(param.required)
    }
}

class ParsedParamsTest {

    @Test
    fun `string returns query param value`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = mapOf("domain" to "example.com"),
            definitions = emptyList()
        )

        assertEquals("example.com", params.string("domain"))
    }

    @Test
    fun `string returns null for missing param without default`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = emptyMap(),
            definitions = emptyList()
        )

        assertNull(params.string("domain"))
    }

    @Test
    fun `string returns default when param missing`() {
        val definitions = listOf(
            ResourceParam.QueryParam("sort_by", ParamType.STRING, "id", "sort field")
        )
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = emptyMap(),
            definitions = definitions
        )

        assertEquals("id", params.string("sort_by"))
    }

    @Test
    fun `stringRequired throws for missing required param`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = emptyMap(),
            definitions = emptyList()
        )

        assertThrows(IllegalStateException::class.java) {
            params.stringRequired("domain")
        }
    }

    @Test
    fun `int parses integer from query string`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = mapOf("limit" to "50"),
            definitions = emptyList()
        )

        assertEquals(50, params.int("limit"))
    }

    @Test
    fun `int returns null for invalid integer`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = mapOf("limit" to "abc"),
            definitions = emptyList()
        )

        assertNull(params.int("limit"))
    }

    @Test
    fun `int returns default when param missing`() {
        val definitions = listOf(
            ResourceParam.QueryParam("limit", ParamType.INT, 100, "max results")
        )
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = emptyMap(),
            definitions = definitions
        )

        assertEquals(100, params.int("limit"))
    }

    @Test
    fun `bool parses true`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = mapOf("descending" to "true"),
            definitions = emptyList()
        )

        assertEquals(true, params.bool("descending"))
    }

    @Test
    fun `bool parses false`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = mapOf("descending" to "false"),
            definitions = emptyList()
        )

        assertEquals(false, params.bool("descending"))
    }

    @Test
    fun `bool returns null for invalid boolean`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = mapOf("flag" to "yes"),
            definitions = emptyList()
        )

        assertNull(params.bool("flag"))
    }

    @Test
    fun `bool returns default when param missing`() {
        val definitions = listOf(
            ResourceParam.QueryParam("descending", ParamType.BOOL, true, "sort order")
        )
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = emptyMap(),
            definitions = definitions
        )

        assertEquals(true, params.bool("descending"))
    }

    @Test
    fun `path returns path param value`() {
        val params = ParsedParams(
            pathParams = mapOf("run_id" to "abc123"),
            queryParams = emptyMap(),
            definitions = emptyList()
        )

        assertEquals("abc123", params.path("run_id"))
    }

    @Test
    fun `path throws for missing path param`() {
        val params = ParsedParams(
            pathParams = emptyMap(),
            queryParams = emptyMap(),
            definitions = emptyList()
        )

        assertThrows(IllegalStateException::class.java) {
            params.path("run_id")
        }
    }
}
