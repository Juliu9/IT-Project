package com.gen3.recommenderagent.inputparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.databind.ObjectMapper;

class InputParserTest {

  @Test
  void shouldMapAiResultWithoutCallingARealAiService() {
    String rawText = "Recommend science fiction audiobooks.";

    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("science fiction"));

    ParsedRequest parsedRequest = new ParsedRequest();
    SemanticQuery negativeSemanticQuery = new SemanticQuery();
    negativeSemanticQuery.setKeywords(List.of("romance"));
    parsedRequest.setIntent(Intent.RECOMMENDATION);
    parsedRequest.setPositiveSemanticQuery(semanticQuery);
    parsedRequest.setNegativeSemanticQuery(negativeSemanticQuery);
    MustInclude mustInclude = new MustInclude();
    mustInclude.setLanguage("English");
    mustInclude.setSource("NLS");
    MustNotInclude mustNotInclude = new MustNotInclude();
    mustNotInclude.setLanguage("French");
    parsedRequest.setMustInclude(mustInclude);
    parsedRequest.setMustNotInclude(mustNotInclude);
    parsedRequest.setBookCount(3);

    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient
            .prompt()
            .system(anyString())
            .user(rawText)
            .call()
            .responseEntity(ParsedRequest.class))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsedRequest));

    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    SessionRequest result = new AiInputParser(builder).parse(rawText).entity();

    assertNotNull(result);
    assertEquals(rawText, result.getRawText());
    assertEquals(Intent.RECOMMENDATION, result.getIntent());
    assertSame(semanticQuery, result.getPositiveSemanticQuery());
    assertSame(negativeSemanticQuery, result.getNegativeSemanticQuery());
    assertSame(mustInclude, result.getMustInclude());
    assertSame(mustNotInclude, result.getMustNotInclude());
    assertEquals(3, result.getBookCount());

    UUID requestId = UUID.fromString(result.getRequestId());
    assertEquals(7, requestId.version());
    assertEquals(2, requestId.variant());
  }

  @Test
  void shouldRejectBlankTextBeforeCallingAi() {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    InputParser parser = new AiInputParser(builder);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> parser.parse("   "));

    assertEquals("Please enter your request", exception.getMessage());
    verifyNoInteractions(chatClient);
  }

  @Test
  void shouldFailClearlyWhenMockAiReturnsNoParsedRequest() {
    String rawText = "Recommend an audiobook.";

    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient
            .prompt()
            .system(anyString())
            .user(rawText)
            .call()
            .responseEntity(ParsedRequest.class))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, null));

    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> new AiInputParser(builder).parse(rawText));

    assertEquals("AI did not return a ParsedRequest", exception.getMessage());
  }

  @Test
  void shouldReadIncludeAndExcludeFilters() throws Exception {
    SessionRequest request =
        new ObjectMapper()
            .readValue(
                "{\"bookCount\":3,\"mustInclude\":{\"language\":\"English\",\"source\":\"NLS\"},"
                    + "\"mustNotInclude\":{\"language\":\"French\",\"source\":\"Legacy\"}}",
                SessionRequest.class);

    assertEquals(3, request.getBookCount());
    assertEquals("English", request.getMustInclude().getLanguage());
    assertEquals("NLS", request.getMustInclude().getSource());
    assertEquals("French", request.getMustNotInclude().getLanguage());
    assertEquals("Legacy", request.getMustNotInclude().getSource());
  }
}
