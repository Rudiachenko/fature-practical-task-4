package com.epam.docqachatbot.api;

import com.epam.docqachatbot.api.model.ChatRequest;
import com.epam.docqachatbot.api.model.RagChatResponse;
import com.epam.docqachatbot.api.model.RagSource;
import com.epam.docqachatbot.controller.RagChatController;
import com.epam.docqachatbot.exception.ApiExceptionHandler;
import com.epam.docqachatbot.service.AzureModelService;
import com.epam.docqachatbot.service.DocumentIngestionService;
import com.epam.docqachatbot.service.RagChatService;
import com.epam.docqachatbot.ingestion.DocumentIngestionException;
import com.epam.docqachatbot.ingestion.IngestionFailure;
import com.epam.docqachatbot.ingestion.IngestionSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RagApiContractTest {

  private MockMvc mockMvc;
  private RagChatService ragChatService;
  private DocumentIngestionService documentIngestionService;
  private AzureModelService azureModelService;
  private LocalValidatorFactoryBean validator;

  @BeforeEach
  void setUp() {
    ragChatService = mock(RagChatService.class);
    documentIngestionService = mock(DocumentIngestionService.class);
    azureModelService = mock(AzureModelService.class);
    validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    RagChatController controller = new RagChatController(
      ragChatService, documentIngestionService, azureModelService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new ApiExceptionHandler())
      .setValidator(validator)
      .build();
  }

  @AfterEach
  void tearDown() {
    validator.close();
  }

  @Test
  void shouldReturnExactStructuredResponse_whenChatRequestIsValid() throws Exception {
    // Arrange
    when(ragChatService.chat(any(ChatRequest.class))).thenReturn(new RagChatResponse(
      "Use prepared statements.",
      List.of(new RagSource("policy.md", "chunk-1"))
    ));

    // Act / Assert
    mockMvc.perform(post("/doc-qa/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"input":"How should SQL queries be built?","conversationId":"contract-test"}
          """))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.response").value("Use prepared statements."))
      .andExpect(jsonPath("$.answer").doesNotExist())
      .andExpect(jsonPath("$.sources[0].documentName").value("policy.md"))
      .andExpect(jsonPath("$.sources[0].chunkId").value("chunk-1"))
      .andExpect(jsonPath("$.sources[0].length()").value(2));

    verify(ragChatService).chat(new ChatRequest(
      "How should SQL queries be built?", "contract-test"));
  }

  @Test
  void shouldRejectBlankChatInput_whenInputIsWhitespace() throws Exception {
    // Act / Assert
    mockMvc.perform(post("/doc-qa/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"input\":\"   \"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.message").value("Request validation failed"))
      .andExpect(jsonPath("$.violations[0].field").value("input"))
      .andExpect(jsonPath("$.violations[0].message").value("Input must not be blank"));

    verifyNoInteractions(ragChatService, documentIngestionService, azureModelService);
  }

  @Test
  void shouldRejectOversizedChatInput_whenInputExceedsBound() throws Exception {
    // Arrange
    String input = "x".repeat(4001);

    // Act / Assert
    mockMvc.perform(post("/doc-qa/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"input\":\"%s\"}".formatted(input)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.violations[0].field").value("input"))
      .andExpect(jsonPath("$.violations[0].message")
        .value("Input must contain at most 4000 characters"));

    verifyNoInteractions(ragChatService);
  }

  @Test
  void shouldRejectEmptyResourceLocations_whenIngestionListIsEmpty() throws Exception {
    // Act / Assert
    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[],\"metadata\":{}}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.violations[0].field").value("resourceLocations"))
      .andExpect(jsonPath("$.violations[0].message")
        .value("ResourceLocations must not be empty"));

    verify(documentIngestionService, never()).ingestResources(anyList(), anyMap());
  }

  @Test
  void shouldRejectOversizedResourceLocations_whenIngestionListExceedsBound() throws Exception {
    // Arrange
    String locations = java.util.stream.IntStream.rangeClosed(1, 21)
      .mapToObj(index -> "\"classpath:document-%d.md\"".formatted(index))
      .collect(java.util.stream.Collectors.joining(","));

    // Act / Assert
    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[%s],\"metadata\":{}}".formatted(locations)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.violations[0].field").value("resourceLocations"))
      .andExpect(jsonPath("$.violations[0].message")
        .value("ResourceLocations must contain at most 20 entries"));

    verify(documentIngestionService, never()).ingestResources(anyList(), anyMap());
  }

  @Test
  void shouldRejectMalformedPayload_whenJsonCannotBeParsed() throws Exception {
    // Act / Assert
    mockMvc.perform(post("/doc-qa/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"input\": \"unfinished\""))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
      .andExpect(jsonPath("$.message").value("Malformed JSON request"))
      .andExpect(jsonPath("$.violations").isEmpty());

    verifyNoInteractions(ragChatService);
  }

  @Test
  void shouldRejectNestedMetadataWithStableError_whenMetadataContainsCollection()
    throws Exception {
    // Act / Assert
    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"resourceLocations":["classpath:policy.md"],
           "metadata":{"tags":["secure-coding"]}}
          """))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.message").value("Request validation failed"))
      .andExpect(jsonPath("$.violations[0].field").value("metadata"))
      .andExpect(jsonPath("$.violations[0].message").value(
        "Metadata values must be flat scalar values and strings must contain at most 512 characters"));

    verify(documentIngestionService, never()).ingestResources(anyList(), anyMap());
  }

  @Test
  void shouldRejectOversizedMetadataStringWithStableError_whenValueExceedsBound()
    throws Exception {
    // Arrange
    String metadataValue = "x".repeat(513);

    // Act / Assert
    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"resourceLocations":["classpath:policy.md"],
           "metadata":{"description":"%s"}}
          """.formatted(metadataValue)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.violations[0].field").value("metadata"))
      .andExpect(jsonPath("$.violations[0].message").value(
        "Metadata values must be flat scalar values and strings must contain at most 512 characters"));

    verify(documentIngestionService, never()).ingestResources(anyList(), anyMap());
  }

  @Test
  void shouldAcceptIngestionRequest_whenPayloadSatisfiesContract() throws Exception {
    when(documentIngestionService.ingestResources(anyList(), anyMap()))
      .thenReturn(new IngestionSummary(1, 0, 1, List.of()));
    // Act / Assert
    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"resourceLocations":["classpath:documents/policy.md"],
           "metadata":{"sourceType":"sample"}}
          """))
      .andExpect(status().isAccepted())
      .andExpect(content().string(""));

    verify(documentIngestionService).ingestResources(
      List.of("classpath:documents/policy.md"), Map.of("sourceType", "sample"));
  }

  @Test
  void shouldReturnMultiStatus_whenClientResourceFailureIsPartial() throws Exception {
    when(documentIngestionService.ingestResources(anyList(), anyMap()))
      .thenReturn(new IngestionSummary(1, 1, 1,
        List.of(new IngestionFailure("classpath:missing.md", "DOCUMENT_NOT_FOUND"))));

    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[\"classpath:valid.md\",\"classpath:missing.md\"]}"))
      .andExpect(status().isMultiStatus())
      .andExpect(content().string(""));
  }

  @Test
  void shouldReturnStableBadRequest_whenTypedIngestionFailureOccurs() throws Exception {
    doThrow(new DocumentIngestionException("INGESTION_FAILED", "sensitive path"))
      .when(documentIngestionService).ingestResources(anyList(), anyMap());

    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[\"classpath:missing.md\"]}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INGESTION_FAILED"))
      .andExpect(jsonPath("$.message").value("Document request was rejected"))
      .andExpect(content().string(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.containsString("sensitive path"))));
  }

  @Test
  void shouldReturnOk_whenReplaceRequestIsValid() throws Exception {
    // Act / Assert
    mockMvc.perform(put("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"resourceLocations":["classpath:documents/policy.md"],
           "metadata":{"sourceType":"sample"}}
          """))
      .andExpect(status().isOk())
      .andExpect(content().string(""));

    verify(documentIngestionService).replaceDocument(
      "classpath:documents/policy.md", Map.of("sourceType", "sample"));
  }

  @Test
  void shouldRejectMultipleResourceLocations_whenReplaceRequestNamesMoreThanOne() throws Exception {
    // Act / Assert
    mockMvc.perform(put("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"resourceLocations":["classpath:policy.md","classpath:other.md"]}
          """))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.violations[0].field").value("resourceLocations"))
      .andExpect(jsonPath("$.violations[0].message")
        .value("ResourceLocations must contain exactly one entry"));

    verify(documentIngestionService, never()).replaceDocument(anyString(), anyMap());
  }

  @Test
  void shouldRejectEmptyResourceLocations_whenReplaceRequestListIsEmpty() throws Exception {
    // Act / Assert
    mockMvc.perform(put("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[],\"metadata\":{}}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
      .andExpect(jsonPath("$.violations[0].field").value("resourceLocations"))
      .andExpect(jsonPath("$.violations[0].message")
        .value("ResourceLocations must not be empty"));

    verify(documentIngestionService, never()).replaceDocument(anyString(), anyMap());
  }

  @Test
  void shouldReturnStableBadRequest_whenReplaceThrowsTypedIngestionFailure() throws Exception {
    // Arrange
    doThrow(new DocumentIngestionException("EMPTY_DOCUMENT", "sensitive path"))
      .when(documentIngestionService).replaceDocument(anyString(), anyMap());

    // Act / Assert
    mockMvc.perform(put("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[\"classpath:policy.md\"]}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("EMPTY_DOCUMENT"))
      .andExpect(jsonPath("$.message").value("Document request was rejected"))
      .andExpect(content().string(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.containsString("sensitive path"))));
  }

  @Test
  void shouldReturnSafeError_whenReplaceThrowsUnexpectedRuntimeException() throws Exception {
    // Arrange
    doThrow(new IllegalStateException("Api-Key=do-not-expose"))
      .when(documentIngestionService).replaceDocument(anyString(), anyMap());

    // Act / Assert
    mockMvc.perform(put("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[\"classpath:policy.md\"]}"))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
      .andExpect(jsonPath("$.message").value("Unable to process the request"))
      .andExpect(content().string(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.containsString("do-not-expose"))));
  }

  @Test
  void shouldReturnNoContent_whenDeleteByIdsIsAccepted() throws Exception {
    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents").param("ids", "chunk-1,chunk-2"))
      .andExpect(status().isNoContent())
      .andExpect(content().string(""));

    verify(documentIngestionService).deleteDocuments(List.of("chunk-1", "chunk-2"), null);
  }

  @Test
  void shouldReturnNoContent_whenDeleteByDocumentNameIsAccepted() throws Exception {
    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents").param("documentName", "policy.md"))
      .andExpect(status().isNoContent())
      .andExpect(content().string(""));

    verify(documentIngestionService).deleteDocuments(null, "policy.md");
  }

  @Test
  void shouldReturnStableBadRequest_whenEmptyDeleteRequestFailureOccurs() throws Exception {
    // Arrange
    doThrow(new DocumentIngestionException("EMPTY_DELETE_REQUEST",
      "Exactly one of ids or documentName is required"))
      .when(documentIngestionService).deleteDocuments(List.of("chunk-1"), null);

    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents").param("ids", "chunk-1"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("EMPTY_DELETE_REQUEST"))
      .andExpect(jsonPath("$.message").value("Document request was rejected"));
  }

  @Test
  void shouldReturnStableBadRequest_whenAmbiguousDeleteSelectorFailureOccurs() throws Exception {
    // Arrange
    doThrow(new DocumentIngestionException("AMBIGUOUS_DELETE_SELECTOR",
      "Exactly one of ids or documentName may be supplied, not both"))
      .when(documentIngestionService).deleteDocuments(List.of("chunk-1"), "policy.md");

    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents")
        .param("ids", "chunk-1")
        .param("documentName", "policy.md"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("AMBIGUOUS_DELETE_SELECTOR"))
      .andExpect(jsonPath("$.message").value("Document request was rejected"));
  }

  @Test
  void shouldReturnStableBadRequest_whenInvalidDeleteSelectorFailureOccurs() throws Exception {
    // Arrange
    doThrow(new DocumentIngestionException("INVALID_DELETE_SELECTOR",
      "documentName must not be blank"))
      .when(documentIngestionService).deleteDocuments(null, "   ");

    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents").param("documentName", "   "))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_DELETE_SELECTOR"))
      .andExpect(jsonPath("$.message").value("Document request was rejected"));
  }

  @Test
  void shouldReturnStableBadRequest_notInternalServerError_whenDocumentNameContainsQuoteCharacter()
    throws Exception {
    // Arrange: an ordinary filename such as "O'Brien_report.md" must be rejected as a stable
    // client 400, never leak as an opaque 500 caused by an unescaped store-filter delimiter.
    doThrow(new DocumentIngestionException("INVALID_DELETE_SELECTOR",
      "documentName contains a character that is not supported by the delete filter; "
        + "delete the affected chunks by id instead"))
      .when(documentIngestionService).deleteDocuments(null, "O'Brien_report.md");

    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents").param("documentName", "O'Brien_report.md"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_DELETE_SELECTOR"))
      .andExpect(jsonPath("$.message").value("Document request was rejected"));
  }

  @Test
  void shouldReturnSafeError_whenDeleteResolutionCapIsExhausted() throws Exception {
    // Arrange
    doThrow(new IllegalStateException("Document deletion resolution exceeded the pass cap"))
      .when(documentIngestionService).deleteDocuments(null, "policy.md");

    // Act / Assert
    mockMvc.perform(delete("/doc-qa/documents").param("documentName", "policy.md"))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
      .andExpect(jsonPath("$.message").value("Unable to process the request"));
  }

  @Test
  void shouldReturnModelPayload_whenModelListingSucceeds() throws Exception {
    // Arrange
    when(azureModelService.listModels()).thenReturn("{\"data\":[]}");

    // Act / Assert
    mockMvc.perform(get("/doc-qa/models"))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
      .andExpect(content().json("{\"data\":[]}"));

    verify(azureModelService).listModels();
  }

  @Test
  void shouldReturnSafeError_whenUnexpectedServiceFailureContainsSensitiveDetails()
    throws Exception {
    // Arrange
    doThrow(new IllegalStateException("Api-Key=do-not-expose"))
      .when(documentIngestionService).ingestResources(anyList(), anyMap());

    // Act / Assert
    mockMvc.perform(post("/doc-qa/documents")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceLocations\":[\"classpath:policy.md\"]}"))
      .andExpect(status().isInternalServerError())
      .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
      .andExpect(jsonPath("$.message").value("Unable to process the request"))
      .andExpect(content().string(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.containsString("do-not-expose"))));
  }
}
