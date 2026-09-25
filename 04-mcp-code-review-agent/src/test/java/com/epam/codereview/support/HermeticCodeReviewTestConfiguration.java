package com.epam.codereview.support;

import java.util.List;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Ported from {@code 03-code-review-agent}'s {@code HermeticCodeReviewTestConfiguration} pattern:
 * supplies a {@code @Primary} {@link RecordingChatModel} so no hermetic test ever makes a real
 * network call to Azure OpenAI, while every other bean in the application context - including
 * {@link com.epam.codereview.config.AgentConfig}'s own {@code chatModel}/{@code chatOptions}
 * factory methods - remains real and unconditional. {@code @Primary} only wins autowiring
 * preference; it does not prevent Spring from still constructing the real {@code chatModel} bean
 * during context refresh, so that bean's own lines remain exercised and JaCoCo-covered even under
 * this hermetic profile.
 *
 * <p>{@link #syncMcpToolCallbackProvider} supplies the {@link SyncMcpToolCallbackProvider} that
 * {@code AgentConfig.chatOptions(...)} needs to autowire. With {@code spring.ai.mcp.client.enabled
 * = false} active (this module's {@code test} profile, {@code application-test.yml}), Spring AI's
 * own MCP auto-configurations never run - decompilation-confirmed against the resolved {@code
 * spring-ai-autoconfigure-mcp-client-common-1.1.2.jar} (per {@code context/PLAN.md}'s Architecture
 * Notes and this module's own {@code AgentConfigTest}/{@code McpToolDiscoveryLoggerTest} findings):
 * {@code McpClientAutoConfiguration} carries a class-level {@code @ConditionalOnProperty(prefix =
 * "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)}, and
 * {@code McpToolCallbackAutoConfiguration} is gated by an {@code AllNestedConditions} whose two
 * nested conditions are {@code spring.ai.mcp.client.toolcallback.enabled} (defaults true) and that
 * exact same {@code spring.ai.mcp.client.enabled} property - so setting it {@code false} alone
 * disables both, and (confirmed the same way) the streamable-HTTP transport auto-configuration too.
 * This bean is therefore the <em>only</em> {@code SyncMcpToolCallbackProvider} bean in this profile
 * and needs no {@code @Primary} to win autowiring. {@code new SyncMcpToolCallbackProvider(List.of
 * ())} is a public constructor whose {@code getToolCallbacks()} returns an empty array with zero
 * network activity (independently decompilation-confirmed by Increment 3's own {@code AgentConfig}
 * work, reused here rather than re-verified).
 */
@TestConfiguration(proxyBeanMethods = false)
public class HermeticCodeReviewTestConfiguration {

  @Bean
  @Primary
  public RecordingChatModel recordingChatModel() {
    return new RecordingChatModel();
  }

  @Bean
  public SyncMcpToolCallbackProvider syncMcpToolCallbackProvider() {
    return new SyncMcpToolCallbackProvider(List.of());
  }
}
