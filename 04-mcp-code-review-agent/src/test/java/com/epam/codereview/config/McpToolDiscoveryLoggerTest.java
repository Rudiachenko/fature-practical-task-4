package com.epam.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Asserts {@link McpToolDiscoveryLogger} logs the discovered MCP tool count/names at INFO once
 * the application becomes ready, using a Logback {@link ListAppender} attached directly to the
 * class's own logger (same pattern as {@code 02-rag}'s
 * {@code GeneratorDeploymentReporterTest}) rather than booting a Spring context.
 */
class McpToolDiscoveryLoggerTest {

  private final ch.qos.logback.classic.Logger logger =
    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(McpToolDiscoveryLogger.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void shouldLogDiscoveredToolCountAndNamesAtInfo_whenApplicationBecomesReady() {
    SyncMcpToolCallbackProvider mcpToolCallbackProvider = mock(SyncMcpToolCallbackProvider.class);
    ToolCallback callback1 = fakeToolCallback("get_pull_request");
    ToolCallback callback2 = fakeToolCallback("get_pull_request_diff");
    when(mcpToolCallbackProvider.getToolCallbacks()).thenReturn(
      new ToolCallback[] {callback1, callback2});
    McpToolDiscoveryLogger mcpToolDiscoveryLogger = new McpToolDiscoveryLogger(mcpToolCallbackProvider);

    mcpToolDiscoveryLogger.logDiscoveredMcpTools();

    assertThat(appender.list)
      .singleElement()
      .satisfies(event -> {
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
          .contains("2")
          .contains("get_pull_request")
          .contains("get_pull_request_diff");
      });
  }

  @Test
  void shouldLogZeroToolsWithAnEmptyNameList_whenNoMcpClientsAreConfigured() {
    // A real, hand-built provider constructed with an empty McpSyncClient list - the same
    // zero-network hermetic shape AgentConfigTest and the plan's own "Hermetic IT strategy"
    // Architecture Note rely on - rather than a mock, to also prove this exact constructor call
    // genuinely yields zero tool callbacks end to end.
    SyncMcpToolCallbackProvider mcpToolCallbackProvider = new SyncMcpToolCallbackProvider(List.of());
    McpToolDiscoveryLogger mcpToolDiscoveryLogger = new McpToolDiscoveryLogger(mcpToolCallbackProvider);

    mcpToolDiscoveryLogger.logDiscoveredMcpTools();

    assertThat(appender.list)
      .singleElement()
      .satisfies(event -> {
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("0").contains("[]");
      });
  }

  private static ToolCallback fakeToolCallback(String name) {
    ToolDefinition toolDefinition = mock(ToolDefinition.class);
    when(toolDefinition.name()).thenReturn(name);
    ToolCallback toolCallback = mock(ToolCallback.class);
    when(toolCallback.getToolDefinition()).thenReturn(toolDefinition);
    return toolCallback;
  }
}
