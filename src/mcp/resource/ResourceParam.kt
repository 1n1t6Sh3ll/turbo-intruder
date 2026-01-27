package mcp.resource

enum class ParamType { STRING, INT, BOOL }

sealed class ResourceParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean
) {
    class PathParam(
        name: String,
        description: String = ""
    ) : ResourceParam(name, ParamType.STRING, description, required = true)

    class QueryParam<T>(
        name: String,
        type: ParamType,
        val default: T?,
        description: String
    ) : ResourceParam(name, type, description, required = default == null)
}
