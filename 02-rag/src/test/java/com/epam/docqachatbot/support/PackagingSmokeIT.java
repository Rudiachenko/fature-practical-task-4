package com.epam.docqachatbot.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class PackagingSmokeIT {

  @Test
  void shouldCreateExecutableSpringBootJar_whenPackageLifecycleRuns() throws Exception {
    // Arrange
    Path executableJar = Path.of(
      System.getProperty("projectBuildDirectory"),
      System.getProperty("projectBuildFinalName") + "-exec.jar"
    );

    // Act / Assert
    assertThat(executableJar).isRegularFile();
    try (JarFile jarFile = new JarFile(executableJar.toFile())) {
      assertThat(jarFile.getManifest().getMainAttributes().getValue("Main-Class"))
        .isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
      assertThat(jarFile.getEntry("BOOT-INF/classes/com/epam/docqachatbot/DocQaChatbotApplication.class"))
        .isNotNull();
    }
  }
}
