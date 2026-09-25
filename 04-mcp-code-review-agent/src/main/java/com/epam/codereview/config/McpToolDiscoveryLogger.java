package com.epam.codereview.config;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs, at INFO, the count and names of every MCP tool the remote GitHub MCP server exposed by
 * the time the application context finished starting - the ticket's "log MCP connectivity"
 * requirement made legible at a single, predictable log line, rather than left to the existing
 * DEBUG-level {@code org.springframework.ai.mcp}/{@code io.modelcontextprotocol} transport
 * logging ({@code application.yml}) to surface incidentally.
 *
 * <p>Mirrors {@link com.epam.codereview.service.ConventionService}'s own
 * {@code @EventListener(ApplicationReadyEvent.class)} idiom: both log a one-time, INFO-level
 * discovery summary once the context is fully up, rather than at bean-construction time (before
 * the MCP client's own connection handshake has necessarily completed).</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class McpToolDiscoveryLogger {

  private final SyncMcpToolCallbackProvider mcpToolCallbackProvider;

  @EventListener(ApplicationReadyEvent.class)
  public void logDiscoveredMcpTools() {
    List<String> toolNames = Arrays.stream(mcpToolCallbackProvider.getToolCallbacks())
      .map(toolCallback -> toolCallback.getToolDefinition().name())
      .toList();

    log.info("Application ready - Discovered {} MCP tool(s): {}", toolNames.size(), toolNames);
  }
}
