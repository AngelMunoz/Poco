package com.poco.api.mcp

import com.poco.api.security.DemoIdentityStore
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse

/**
 * MCP transport wiring, kept with the tools it serves. Stateless HTTP on
 * /mcp, guarded by the same security filter chain as the HTTP routes
 * (mcp:use to connect at all). The context extractor is the MCP equivalent
 * of reading the HttpContext in an endpoint: it carries who the API key
 * belongs to into each tool call, where tools re-check their own permission.
 */
@Configuration
class McpConfig(private val identities: DemoIdentityStore) {

    @Bean
    fun mcpTransport(tools: InsuranceTools): WebMvcStatelessServerTransport {
        val transport = WebMvcStatelessServerTransport.builder()
            .messageEndpoint("/mcp")
            .contextExtractor { request ->
                val identity = identities.find(request.headers().firstHeader("X-Api-Key"))
                if (identity == null) {
                    McpTransportContext.EMPTY
                } else {
                    McpTransportContext.create(
                        mapOf(
                            InsuranceTools.CONTEXT_NAME to identity.name,
                            InsuranceTools.CONTEXT_PERMISSIONS to identity.permissions,
                        ),
                    )
                }
            }
            .build()

        McpServer.sync(transport)
            .serverInfo(McpSchema.Implementation("Poco.Api", "1.0.0"))
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
            .tools(tools.specifications())
            .build()

        return transport
    }

    @Bean
    fun mcpRouterFunction(transport: WebMvcStatelessServerTransport): RouterFunction<ServerResponse> =
        transport.routerFunction
}
