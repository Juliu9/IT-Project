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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
        "CHANGE_COUNT",
        "UPDATE_PREFERENCES",
        "CLEAR_HISTORY",
        "HELP",
        "UNKNOWN"
      })
  void stripsUnexpectedSemanticInformationFromControlIntents(Intent intent) {
    var parsed = new ParsedRequest();
    parsed.setIntent(intent);
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    parsed.setPositiveSemanticQuery(query);
    parsed.setNegativeSemanticQuery(query);
    parsed.setPersonalised(true);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("control")
            .call()
            .responseEntity(ParsedRequest.class))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(client);
    var result = new AiInputParser(builder).parse("control");
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(null, result.getNegativeSemanticQuery());
    assertEquals(false, result.isPersonalised());
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void recordsProviderUsageWhileReturningOnlyTheRequest(
      org.springframework.boot.test.system.CapturedOutput output) {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.HELP);
    var response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
    when(response.getMetadata().getModel()).thenReturn("parser-model");
    when(response.getMetadata().getUsage().getPromptTokens()).thenReturn(12);
    when(response.getMetadata().getUsage().getCompletionTokens()).thenReturn(3);
    when(response.getMetadata().getUsage().getTotalTokens()).thenReturn(15);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("help")
            .call()
            .responseEntity(ParsedRequest.class))
        .thenReturn(new ResponseEntity<>(response, parsed));
    var observer =
        new com.gen3.recommenderagent.observability.AiOperationObserver(
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.core.retry.RetryTemplate.class));
    var result = new AiInputParser(client, observer).parse("help");
    assertEquals(Intent.HELP, result.getIntent());
    org.assertj.core.api.Assertions.assertThat(output.getOut())
        .contains(
            "operation=parse",
            "model=parser-model",
            "promptTokens=12",
            "completionTokens=3",
            "totalTokens=15",
            "usageAvailable=true");
  }

  @Test
  void mapsPersonalisedRequestWithoutInventingSearchCriteria() {
    String rawText = "Give me books I would like";
    ParsedRequest parsed = new ParsedRequest();
    parsed.setIntent(Intent.RECOMMENDATION);
    parsed.setPersonalised(true);
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(rawText)
            .call()
            .responseEntity(ParsedRequest.class))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(client);

    SessionRequest result = new AiInputParser(builder).parse(rawText);

    assertEquals(true, result.isPersonalised());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(null, result.getMustInclude());
  }

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

    SessionRequest result = new AiInputParser(builder).parse(rawText);

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

    InvalidRequestException exception =
        assertThrows(InvalidRequestException.class, () -> parser.parse("   "));

    assertEquals("I didn't catch that. What would you like to listen to?", exception.getMessage());
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
                "{\"bookCount\":3,\"mustInclude\":{\"language\":\"English\",\"publicationYear\":\"after 2020\",\"source\":\"NLS\"},"
                    + "\"mustNotInclude\":{\"language\":\"French\",\"publicationYear\":\"before 2000\",\"source\":\"Legacy\"}}",
                SessionRequest.class);

    assertEquals(3, request.getBookCount());
    assertEquals("English", request.getMustInclude().getLanguage());
    assertEquals("after 2020", request.getMustInclude().getPublicationYear());
    assertEquals("NLS", request.getMustInclude().getSource());
    assertEquals("French", request.getMustNotInclude().getLanguage());
    assertEquals("before 2000", request.getMustNotInclude().getPublicationYear());
    assertEquals("Legacy", request.getMustNotInclude().getSource());
  }
}
