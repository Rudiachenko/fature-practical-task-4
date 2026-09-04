package com.epam.codereviewagent.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Configuration properties for the coding-convention documents loaded by {@link
 * com.epam.codereviewagent.service.ConventionService}, bound under the {@code app.conventions}
 * prefix.
 */
@ConfigurationProperties(prefix = "app.conventions")
@Getter
@Setter
public class ConventionProperties {

  /**
   * List of convention resource locations (e.g., classpath:documents/java-convention.md)
   */
  private List<Resource> resources;
}

