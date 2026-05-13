package com.epam.codereview.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures HTTP request customization for GitHub MCP remote server.
 * <p>
 * Adds the {@code Authorization: Bearer <PAT>} header to every outgoing request
 * to the GitHub remote MCP server at {@code https://api.githubcopilot.com/mcp/}.
 * <p>
 * Spring AI's {@code streamable-http.connections} properties only expose
 * {@code url} and {@code endpoint}. Per-connection HTTP headers are configured
 * by contributing a {@link McpSyncHttpClientRequestCustomizer} bean, which the
 * streamable-HTTP auto-configuration applies to the underlying {@code HttpClient}.
 */
@Configuration
public class GitHubMcpRequestCustomizerConfig {

  @Bean
  public McpSyncHttpClientRequestCustomizer gitHubMcpAuthorizationCustomizer(
      @Value("${GITHUB_TOKEN}") String gitHubToken) {

    return (HttpRequest.Builder builder,
            String method,
            URI uri,
            String body,
            McpTransportContext context) ->
        builder.header("Authorization", "Bearer " + gitHubToken);
  }
}
