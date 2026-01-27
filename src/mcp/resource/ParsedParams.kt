package mcp.resource

class ParsedParams(
    private val pathParams: Map<String, String>,
    private val queryParams: Map<String, String>,
    private val definitions: List<ResourceParam>
) {
    fun string(name: String): String? = queryParams[name] ?: findDefault(name)

    fun stringRequired(name: String): String =
        string(name) ?: error("Missing required param: $name")

    fun int(name: String): Int? =
        queryParams[name]?.toIntOrNull() ?: findDefault(name)

    fun intRequired(name: String): Int =
        int(name) ?: error("Missing required param: $name")

    fun bool(name: String): Boolean? =
        queryParams[name]?.toBooleanStrictOrNull() ?: findDefault(name)

    fun boolRequired(name: String): Boolean =
        bool(name) ?: error("Missing required param: $name")

    fun path(name: String): String =
        pathParams[name] ?: error("Missing path param: $name")

    @Suppress("UNCHECKED_CAST")
    private fun <T> findDefault(name: String): T? {
        val param = definitions.find { it.name == name } as? ResourceParam.QueryParam<*>
        return param?.default as? T
    }
}
