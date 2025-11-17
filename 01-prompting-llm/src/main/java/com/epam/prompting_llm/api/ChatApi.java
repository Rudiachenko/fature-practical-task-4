package com.epam.prompting_llm.api;

import com.epam.prompting_llm.api.model.PromptRequest;
import com.epam.prompting_llm.api.model.PromptResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/chat")
public interface ChatApi {

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<PromptResponse> sendMessage(final @Valid @RequestBody PromptRequest request);
}
