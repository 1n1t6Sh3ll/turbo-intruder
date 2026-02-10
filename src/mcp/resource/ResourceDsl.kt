package mcp.resource

@DslMarker
annotation class ResourceDslMarker

@ResourceDslMarker
class ResourceBuilder(private val uriPattern: String) {
    var name: String = ""
    var description: String = ""
    var mimeType: String = "application/json"

    private val pathParams = mutableListOf<ResourceParam.PathParam>()
    private val queryParams = mutableListOf<ResourceParam.QueryParam<*>>()
    private var handlerFn: ((ParsedParams) -> Map<String, Any?>)? = null

    init {
        // Auto-extract path params from URI pattern
        Regex("\\{([^}]+)\\}").findAll(uriPattern).forEach { match ->
            pathParams.add(ResourceParam.PathParam(match.groupValues[1]))
        }
    }

    fun query(name: String, type: ParamType, default: Any? = null, description: String = "") {
        queryParams.add(ResourceParam.QueryParam(name, type, default, description))
    }

    fun queryString(name: String, default: String? = null, description: String = "") =
        query(name, ParamType.STRING, default, description)

    fun queryInt(name: String, default: Int? = null, description: String = "") =
        query(name, ParamType.INT, default, description)

    fun queryBool(name: String, default: Boolean? = null, description: String = "") =
        query(name, ParamType.BOOL, default, description)

    fun handle(handler: (params: ParsedParams) -> Map<String, Any?>) {
        handlerFn = handler
    }

    fun build(): ResourceDefinition {
        require(name.isNotEmpty()) { "Resource name is required" }
        require(description.isNotEmpty()) { "Resource description is required" }
        require(handlerFn != null) { "Resource handler is required" }

        return ResourceDefinition(
            uriPattern = uriPattern,
            name = name,
            baseDescription = description,
            mimeType = mimeType,
            pathParams = pathParams.toList(),
            queryParams = queryParams.toList(),
            handler = handlerFn!!
        )
    }
}

fun resource(uriPattern: String, init: ResourceBuilder.() -> Unit): ResourceDefinition {
    return ResourceBuilder(uriPattern).apply(init).build()
}
