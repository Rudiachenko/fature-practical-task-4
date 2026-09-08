package com.epam.docqachatbot.api;

import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.DocumentIngestionRequest;
import com.epam.docqachatbot.api.model.DocumentReplacementRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RequestMapping(path = "/doc-qa")
public interface DocQaApi {

  @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<RagChatResponse> chat(@Valid @RequestBody ChatRequest request);

  @PostMapping(path = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> ingestDocuments(@Valid @RequestBody DocumentIngestionRequest request);

  @PutMapping(path = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> replaceDocument(@Valid @RequestBody DocumentReplacementRequest request);

  @DeleteMapping(path = "/documents")
  ResponseEntity<Void> deleteDocuments(
    @RequestParam(value = "ids", required = false) List<String> ids,
    @RequestParam(value = "documentName", required = false) String documentName);

  @GetMapping(path = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> listModels();
}
