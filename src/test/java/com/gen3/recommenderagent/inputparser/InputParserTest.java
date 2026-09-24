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
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;

class InputParserTest {

  @Test
  void shouldMapAiResultWithoutCallingARealAiService() {
    String rawText = "Recommend science fiction audiobooks.";

    Query query = new Query();
    query.setGenres(List.of("science fiction"));

    ParsedRequest parsedRequest = new ParsedRequest();
    parsedRequest.setIntent(Intent.NEW_RECOMMENDATION);
    parsedRequest.setQuery(query);
    Constraints constraints = new Constraints();
    constraints.setLanguage("English");
    parsedRequest.setConstraints(constraints);

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
    assertEquals(Intent.NEW_RECOMMENDATION, result.getIntent());
    assertSame(query, result.getQuery());
    assertSame(constraints, result.getConstraints());

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
}
