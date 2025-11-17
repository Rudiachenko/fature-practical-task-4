package com.epam.prompting_llm;

import com.epam.prompting_llm.config.ChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ChatProperties.class)
public class PromptingLlmApplication {

  public static void main(String[] args) {
    SpringApplication.run(PromptingLlmApplication.class, args);
  }

}
