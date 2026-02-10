package mcp.resource

class ResourceDefinition(
    val uriPattern: String,
    val name: String,
    val baseDescription: String,
    val mimeType: String = "application/json",
    val pathParams: List<ResourceParam.PathParam>,
    val queryParams: List<ResourceParam.QueryParam<*>>,
    val handler: (params: ParsedParams) -> Map<String, Any?>
) {
    /** Base URI without query params, used for SDK registration */
    val baseUri: String get() = uriPattern

    /** Auto-generated full description including query params */
    val fullDescription: String get() = buildString {
        append(baseDescription)
        if (queryParams.isNotEmpty()) {
            append(". Query params: ")
            append(queryParams.joinToString(", ") { param ->
                buildString {
                    append(param.name)
                    if (param.description.isNotEmpty()) {
                        append(" (")
                        append(param.description)
                        param.default?.let { append(", default: $it") }
                        append(")")
                    } else {
                        param.default?.let { append(" (default: $it)") }
                    }
                }
            })
        }
    }

    /** Check if a request URI matches this resource */
    fun matches(requestUri: String): Boolean {
        val baseRequestUri = requestUri.substringBefore('?')
        return if (pathParams.isEmpty() && !uriPattern.contains("{")) {
            baseRequestUri == uriPattern
        } else {
            val regex = buildMatchRegex()
            baseRequestUri.matches(regex)
        }
    }

    private fun buildMatchRegex(): Regex {
        // Escape special regex chars except for our placeholders
        var pattern = uriPattern
            .replace(".", "\\.")
            .replace("?", "\\?")
        // Replace {param} with [^/]+ to match any segment
        pattern = pattern.replace(Regex("\\{[^}]+\\}"), "[^/]+")
        return Regex("^$pattern$")
    }

    /** Parse parameters from a request URI */
    fun parseParams(requestUri: String): ParsedParams {
        val pathValues = extractPathParams(requestUri)
        val queryValues = extractQueryParams(requestUri)
        return ParsedParams(pathValues, queryValues, pathParams + queryParams)
    }

    private fun extractPathParams(requestUri: String): Map<String, String> {
        val baseUri = requestUri.substringBefore('?')
        val patternParts = uriPattern.split("/")
        val uriParts = baseUri.split("/")

        return pathParams.associate { param ->
            val index = patternParts.indexOfFirst { it == "{${param.name}}" }
            param.name to (uriParts.getOrNull(index) ?: "")
        }
    }

    private fun extractQueryParams(requestUri: String): Map<String, String> {
        val queryStart = requestUri.indexOf('?')
        if (queryStart == -1) return emptyMap()

        return requestUri.substring(queryStart + 1)
            .split('&')
            .mapNotNull { param ->
                val parts = param.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }
}
