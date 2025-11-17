package com.epam.docqachatbot.controller;

import com.epam.docqachatbot.api.DocQaApi;
import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.DocumentIngestionRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.service.AzureModelService;
import com.epam.docqachatbot.service.DocumentIngestionService;
import com.epam.docqachatbot.service.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class RagChatController implements DocQaApi {

  private final RagChatService ragChatService;
  private final DocumentIngestionService documentIngestionService;
  private final AzureModelService azureModelService;

  public ResponseEntity<RagChatResponse> chat(ChatRequest request) {
    return ResponseEntity.ok(ragChatService.chat(request));
  }

  public ResponseEntity<Void> ingestDocuments(DocumentIngestionRequest request) throws IOException {
    documentIngestionService.ingestResources(request.resourceLocations(), request.metadata());
    return ResponseEntity.accepted().build();
  }

  public ResponseEntity<Void> deleteDocuments(List<String> ids) {
    documentIngestionService.deleteByIds(ids);
    return ResponseEntity.noContent().build();
  }

  public ResponseEntity<String> listModels() {
    return ResponseEntity.ok(azureModelService.listModels());
  }
}

