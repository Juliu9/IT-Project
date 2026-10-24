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
  void mapsExplicitAdditionsAndRoutesStandaloneCriteriaToRecommendation() {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.RECOMMENDATION);
    parsed.setAddAuthors(true);
    parsed.setAddNarrators(false);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("fantasy under five hours")
            .call()
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new AiInputParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
            .parse("fantasy under five hours");
    assertEquals(Intent.RECOMMENDATION, result.getIntent());
    org.junit.jupiter.api.Assertions.assertTrue(result.isAddAuthors());
    org.junit.jupiter.api.Assertions.assertFalse(result.isAddNarrators());
    org.mockito.Mockito.verify(client.prompt())
        .system(
            org.mockito.ArgumentMatchers.<String>argThat(
                prompt ->
                    prompt.contains("Self-contained criteria")
                        && prompt.contains("addAuthors/addNarrators")));
  }

  @Test
  void mapsExplanationSelectorsWithoutTreatingRankAsBookCount() {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    parsed.setReferenceRank(java.math.BigInteger.valueOf(6));
    parsed.setReferenceTitle("Harry Potter");
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("why")
            .call()
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new AiInputParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
            .parse("why");
    assertEquals(Intent.EXPLAIN_RECOMMENDATION, result.getIntent());
    assertEquals(6, result.getReferenceRank());
    assertEquals("Harry Potter", result.getReferenceTitle());
    assertEquals(null, result.getBookCount());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"1008392481092349012389201382", "2147483648", "-2147483649"})
  void oversizedModelNumbersCannotOverflowRanksOrCounts(String number) {
    var parsed =
        AiInputParser.REQUEST_CONVERTER.convert(
            "{\"intent\":\"EXPLAIN_RECOMMENDATION\",\"referenceRank\":"
                + number
                + ",\"bookCount\":"
                + number
                + "}");
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    String raw = "explain the " + number + "th one more";
    when(client
            .prompt()
            .system(anyString())
            .user(raw)
            .call()
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new AiInputParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
            .parse(raw);
    assertEquals(
        number.startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE, result.getReferenceRank());
    assertEquals(result.getReferenceRank(), result.getBookCount());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"2.7", "1e3", "tooLong"})
  void invalidModelNumbersReturnFriendlyValidationInsteadOfCrashing(String number) {
    String value = number.equals("tooLong") ? "9".repeat(1001) : number;
    for (String field : List.of("referenceRank", "bookCount")) {
      var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
      when(client
              .prompt()
              .system(anyString())
              .user("bad number")
              .call()
              .responseEntity(AiInputParser.REQUEST_CONVERTER))
          .thenAnswer(
              ignored ->
                  new ResponseEntity<>(
                      null,
                      AiInputParser.REQUEST_CONVERTER.convert(
                          "{\"intent\":\"EXPLAIN_RECOMMENDATION\",\""
                              + field
                              + "\":"
                              + value
                              + "}")));
      var parser =
          new AiInputParser(
              client, com.gen3.recommenderagent.observability.AiOperationObserver.noop());
      assertThrows(InvalidRequestException.class, () -> parser.parse("bad number"));
    }
  }

  @Test
  void rejectsOversizedInputBeforeCallingTheModel() {
    var client = mock(ChatClient.class);
    var parser =
        new AiInputParser(
            client, com.gen3.recommenderagent.observability.AiOperationObserver.noop());
    assertThrows(
        InvalidRequestException.class,
        () -> parser.parse("x".repeat(InputParser.MAX_REQUEST_CHARACTERS + 1)));
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
        "REPEAT_RECOMMENDATIONS",
        "EXPLAIN_RECOMMENDATION",
        "CHANGE_COUNT",
        "UPDATE_PREFERENCES",
        "CLEAR_HISTORY",
        "HELP",
        "CANCEL",
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
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
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
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
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
  void preservesAnExplicitCountOnMoreWithoutAddingSearchCriteria() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("2 more")
            .call()
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
        .thenAnswer(
            ignored ->
                new ResponseEntity<>(
                    null,
                    AiInputParser.REQUEST_CONVERTER.convert(
                        "{\"intent\":\"MORE_RESULTS\",\"bookCount\":2}")));
    var result =
        new AiInputParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
            .parse("2 more");
    assertEquals(Intent.MORE_RESULTS, result.getIntent());
    assertEquals(2, result.getBookCount());
    assertEquals(null, result.getPositiveSemanticQuery());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "teen romance that is recent,recent,5",
    "romance published in the past year,past year,1",
    "romance published in the last 4 years,last 4 years,4",
    "romance published in the last four years,last four years,4"
  })
  void preservesRelativePublicationWindowsForDeterministicFiltering(
      String raw, String window, int years) {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.RECOMMENDATION);
    var include = new MustInclude();
    include.setPublicationYear(window);
    parsed.setMustInclude(include);
    var query = new SemanticQuery();
    query.setGenres(List.of("romance"));
    parsed.setPositiveSemanticQuery(query);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(raw)
            .call()
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<>(null, parsed));
    var result =
        new AiInputParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
            .parse(raw);
    assertEquals(window, result.getMustInclude().getPublicationYear());
    assertEquals(List.of("romance"), result.getPositiveSemanticQuery().getGenres());
    var filters =
        new com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner()
            .plan(50, result, java.util.Set.of())
            .filters()
            .mustInclude();
    int currentYear = java.time.Year.now(java.time.Clock.systemUTC()).getValue();
    assertEquals(currentYear - years, filters.minimumPublicationYear());
    assertEquals(currentYear, filters.maximumPublicationYear());
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
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
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
    parsedRequest.setBookCount(java.math.BigInteger.valueOf(3));

    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient
            .prompt()
            .system(anyString())
            .user(rawText)
            .call()
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
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
            .responseEntity(AiInputParser.REQUEST_CONVERTER))
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
