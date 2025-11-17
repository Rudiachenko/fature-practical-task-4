package com.epam.codereviewagent;

import com.epam.codereviewagent.config.CodeReviewProperties;
import com.epam.codereviewagent.config.ConventionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  CodeReviewProperties.class,
  ConventionProperties.class
})
public class CodeReviewAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(CodeReviewAgentApplication.class, args);
  }
}

