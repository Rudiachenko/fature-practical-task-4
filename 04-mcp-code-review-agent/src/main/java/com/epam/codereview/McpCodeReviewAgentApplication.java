package com.epam.codereview;

import com.epam.codereview.config.CodeReviewProperties;
import com.epam.codereview.config.ConventionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  CodeReviewProperties.class,
  ConventionProperties.class
})
public class McpCodeReviewAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(McpCodeReviewAgentApplication.class, args);
  }
}

