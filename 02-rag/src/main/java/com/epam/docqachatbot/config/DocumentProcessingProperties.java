package com.epam.docqachatbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.documents.processing")
public class DocumentProcessingProperties {

  private long maxResourceBytes = 5 * 1024 * 1024;
  private int maxResourcesPerRequest = 20;
  private String embeddingModel = "text-embedding-3-small-1";
  private List<String> allowedFileRoots = List.of(".");
  private Chunking chunking = new Chunking();
  // maxDeleteIdsPerRequest - maximum number of opaque chunk ids accepted per delete request
  private int maxDeleteIdsPerRequest = 100;
  // maxDocumentNameLength - maximum length of a documentName delete selector
  private int maxDocumentNameLength = 512;
  // maxDeleteResolutionPasses - bounded pages searched/deleted while resolving a documentName delete
  private int maxDeleteResolutionPasses = 3;

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

