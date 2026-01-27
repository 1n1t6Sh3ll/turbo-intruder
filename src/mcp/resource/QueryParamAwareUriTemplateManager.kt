package mcp.resource

import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager
import io.modelcontextprotocol.util.McpUriTemplateManager
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory

/**
 * Custom URI template manager that strips query parameters before matching.
 * This fixes the bug where non-template URIs like "turbo://organizer" fail
 * to match requests with query params like "turbo://organizer?domain=foo".
 */
class QueryParamAwareUriTemplateManager(private val uriTemplate: String) : McpUriTemplateManager {
    private val delegate = DefaultMcpUriTemplateManager(uriTemplate)

    override fun getVariableNames(): List<String> = delegate.variableNames

    override fun extractVariableValues(requestUri: String): Map<String, String> {
        // Strip query params before extracting path variables
        return delegate.extractVariableValues(requestUri.substringBefore('?'))
    }

    override fun matches(uri: String): Boolean {
        // Strip query params before matching
        val baseUri = uri.substringBefore('?')

        // For non-template URIs, do exact match on base URI
        if (!isUriTemplate(uriTemplate)) {
            return baseUri == uriTemplate
        }

        return delegate.matches(baseUri)
    }

    override fun isUriTemplate(uri: String): Boolean = delegate.isUriTemplate(uri)
}

class QueryParamAwareUriTemplateManagerFactory : McpUriTemplateManagerFactory {
    override fun create(uriTemplate: String): McpUriTemplateManager =
        QueryParamAwareUriTemplateManager(uriTemplate)
}
