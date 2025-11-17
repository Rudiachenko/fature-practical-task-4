package com.epam.docqachatbot;

import com.epam.docqachatbot.config.AzureProperties;
import com.epam.docqachatbot.config.ChromaProperties;
import com.epam.docqachatbot.config.DocumentProcessingProperties;
import com.epam.docqachatbot.config.DocumentsProperties;
import com.epam.docqachatbot.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  AzureProperties.class,
  ChromaProperties.class,
  DocumentsProperties.class,
  DocumentProcessingProperties.class,
  RagProperties.class
})
public class DocQaChatbotApplication {

  public static void main(String[] args) {
    SpringApplication.run(DocQaChatbotApplication.class, args);
  }
}

