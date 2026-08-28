package com.epam.codereviewagent.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Ported from {@code 02-rag}'s {@code HermeticRagTestConfiguration} pattern: supplies a
 * {@code @Primary} {@link RecordingChatModel} so no hermetic test ever makes a real network call to
 * Azure OpenAI/DIAL, while every other bean in the application context — including {@link
 * com.epam.codereviewagent.config.AgentConfig}'s own {@code chatModel}/{@code chatOptions} factory
 * methods — remains real and unconditional (Architecture Note A3). {@code @Primary} only wins
 * autowiring preference; it does not prevent Spring from still constructing the real {@code chatModel}
 * bean during context refresh, so that bean's own lines remain exercised and JaCoCo-covered even under
 * this hermetic profile.
 */
@TestConfiguration(proxyBeanMethods = false)
public class HermeticCodeReviewTestConfiguration {

  @Bean
  @Primary
  public RecordingChatModel recordingChatModel() {
    return new RecordingChatModel();
  }
}
