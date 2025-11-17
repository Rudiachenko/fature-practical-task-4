package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.documents.processing")
public class DocumentProcessingProperties {

  private Chunking chunking = new Chunking();

  @Data
  public static class Chunking {
    private boolean enabled = true;
    // defaultChunkSize - target size of each text block in tokens
    private int tokensPerChunk = 800;
    // minChunkSizeChars - minimum number of characters in each text block
    private int minChunkSizeChars = 100;
    // minChunkLengthToEmbed - minimum block length required to be included
    private int minChunkLengthToEmbed = 50;
    // maxNumChunks - maximum number of chunks generated from a text
    private int maxNumChunks = 10000;
    // keepSeparator - whether to keep separators in the chunks
    private boolean keepSeparator = true;
  }
}

