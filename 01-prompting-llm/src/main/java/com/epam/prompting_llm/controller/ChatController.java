package com.epam.prompting_llm.controller;

import com.epam.prompting_llm.api.ChatApi;
import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.api.model.PromptResponse;
import com.epam.prompting_llm.service.ChatProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatController implements ChatApi {

  private final ChatProcessor chatProcessor;

  public ResponseEntity<PromptResponse> sendMessage(PromptRequest request) {
    return ResponseEntity.ok(chatProcessor.sendMessage(request));
  }
}
