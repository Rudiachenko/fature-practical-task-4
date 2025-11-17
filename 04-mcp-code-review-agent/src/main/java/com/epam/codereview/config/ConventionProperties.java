package com.epam.codereview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.util.List;

@ConfigurationProperties(prefix = "app.conventions")
@Getter
@Setter
public class ConventionProperties {

  /**
   * List of convention resource locations (e.g., classpath:documents/java-convention.md)
   */
  private List<Resource> resources;
}

