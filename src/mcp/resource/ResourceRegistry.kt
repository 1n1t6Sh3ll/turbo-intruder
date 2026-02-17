package mcp.resource

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema

class ResourceRegistry(
    private val jsonMapper: ObjectMapper = ObjectMapper()
) {
    private val resources = mutableListOf<ResourceDefinition>()

    fun register(resource: ResourceDefinition) {
        resources.add(resource)
    }

    fun register(vararg definitions: ResourceDefinition) {
        resources.addAll(definitions)
    }

    /** Find matching resource for a request URI */
    fun findResource(requestUri: String): ResourceDefinition? {
        // Try to find the most specific match
        // Resources with more path segments are more specific
        return resources
            .filter { it.matches(requestUri) }
            .maxByOrNull { specificity(it.uriPattern) }
    }

    /** Get all registered definitions */
    fun getDefinitions(): List<ResourceDefinition> = resources.toList()

    /** Generate stateless SDK resource specs */
    fun buildStatelessSpecs(): List<McpStatelessServerFeatures.SyncResourceSpecification> {
        return resources.map { def ->
            val resource = McpSchema.Resource.builder()
                .uri(def.baseUri)
                .name(def.name)
                .description(def.fullDescription)
                .mimeType(def.mimeType)
                .build()

            McpStatelessServerFeatures.SyncResourceSpecification(resource) { _, request ->
                val params = def.parseParams(request.uri())
                val result = def.handler(params)
                McpSchema.ReadResourceResult(
                    listOf(McpSchema.TextResourceContents(
                        request.uri(),
                        def.mimeType,
                        jsonMapper.writeValueAsString(result)
                    ))
                )
            }
        }
    }

    private fun specificity(pattern: String): Int {
        // Count non-variable segments for specificity
        return pattern.split("/").count { !it.startsWith("{") }
    }
}
