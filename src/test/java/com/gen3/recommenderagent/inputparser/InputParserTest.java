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
import com.gen3.recommenderagent.domain.session.ExactCriteria;
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
  void referenceTitleAndAuthorAreSeparateFromExplanationAndResultFilters() {
    var result =
        parseModelJson(
            "books similar to The Hunger Games by Suzanne Collins under ten hours",
            """
        {"intent":"RECOMMENDATION","similarToTitle":"The Hunger Games","similarToAuthor":"Suzanne Collins",
         "mustInclude":{"duration":"under 10 hours"}}
        """);
    assertEquals("The Hunger Games", result.getSimilarToTitle());
    assertEquals(true, result.isExplicitSearch());
    assertEquals("Suzanne Collins", result.getSimilarToAuthor());
    assertEquals("under 10 hours", result.getMustInclude().getDuration());
    assertEquals(null, result.getReferenceTitle());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(null, result.getMustInclude().getAuthors());
    assertEquals(false, result.isPersonalised());
  }

  @Test
  void parserSchemaRequiresOnlyIntentIncludingInsideNestedCriteria() {
    var schema = new ObjectMapper().readTree(AiParser.REQUEST_CONVERTER.getJsonSchema());
    org.assertj.core.api.Assertions.assertThat(schema.path("required"))
        .containsExactly(new ObjectMapper().readTree("\"intent\""));
    org.assertj.core.api.Assertions.assertThat(schema.findValues("required")).hasSize(1);
    assertEquals("boolean", schema.path("properties").path("explicitSearch").path("type").asText());
    assertEquals(false, schema.path("additionalProperties").asBoolean());
  }

  @Test
  void sparseExplanationOutputUsesExistingDefaultsWithoutInventingCountOrCriteria() {
    var request =
        parseModelJson(
            "why the second book", "{\"intent\":\"EXPLAIN_RECOMMENDATION\",\"referenceRank\":2}");
    assertEquals(Intent.EXPLAIN_RECOMMENDATION, request.getIntent());
    assertEquals(2, request.getReferenceRank());
    assertEquals(null, request.getBookCount());
    assertEquals(null, request.getPositiveSemanticQuery());
    assertEquals(null, request.getMustInclude());
    assertEquals(null, request.getPreferenceUpdates());
    assertEquals(false, request.isExplicitSearch());
    assertEquals(false, request.isPersonalised());
    assertEquals(false, request.isAddAuthors());
    assertEquals(false, request.isAddNarrators());
  }

  @Test
  void sparseTasteCriteriaDoNotRequireDuplicatePreferenceUpdates() {
    var request =
        parseModelJson(
            "fantasy under ten hours",
            """
        {"intent":"RECOMMENDATION","positiveSemanticQuery":{"genres":["fantasy"]},
         "mustInclude":{"duration":"under 10 hours"}}
        """);
    assertEquals(List.of("fantasy"), request.getPositiveSemanticQuery().getGenres());
    assertEquals("under 10 hours", request.getMustInclude().getDuration());
    assertEquals(null, request.getPositiveSemanticQuery().getAuthors());
    assertEquals(null, request.getMustInclude().getLanguage());
    assertEquals(null, request.getPreferenceUpdates());
    assertEquals(null, request.getPreferenceRemovals());
  }

  @Test
  void sparseRemovalAndClearingEditsDoNotNeedEmptyAdditionObjects() {
    var removal =
        parseModelJson(
            "I no longer like romance",
            """
        {"intent":"UPDATE_PREFERENCES",
         "preferenceRemovals":{"positiveSemanticQuery":{"genres":["romance"]}}}
        """);
    assertEquals(null, removal.getPreferenceUpdates());
    assertEquals(
        List.of("romance"), removal.getPreferenceRemovals().getPositiveSemanticQuery().getGenres());
    var clearing =
        parseModelJson(
            "clear my language preference",
            """
        {"intent":"UPDATE_PREFERENCES","replacePreferenceFields":["mustInclude.language"]}
        """);
    assertEquals(null, clearing.getPreferenceUpdates());
    assertEquals(List.of("mustInclude.language"), clearing.getReplacePreferenceFields());
  }

  private SessionRequest parseModelJson(String text, String json) {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(text)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(
            new ResponseEntity<ChatResponse, ParsedRequest>(
                null, AiParser.REQUEST_CONVERTER.convert(json)));
    var request =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    org.mockito.Mockito.verify(client.prompt())
        .system(
            org.mockito.ArgumentMatchers.<String>argThat(
                prompt ->
                    prompt.contains("omit nulls, empty arrays/objects/strings and false flags")
                        && prompt.contains("omit preferenceUpdates")));
    return request;
  }

  @Test
  void mapsPreferenceEditsWithoutPuttingThemInControlSearchSemantics() {
    var parsed =
        AiParser.REQUEST_CONVERTER.convert(
            """
        {"intent":"UPDATE_PREFERENCES",
         "preferenceUpdates":{"negativeSemanticQuery":{"genres":["romance"]}},
         "preferenceRemovals":{"positiveSemanticQuery":{"genres":["romance"]}},
         "replacePreferenceFields":["mustInclude.language"], "explicitSearch":false}
        """);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("avoid romance and any language is fine")
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse("avoid romance and any language is fine");
    assertEquals(Intent.UPDATE_PREFERENCES, result.getIntent());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(null, result.getNegativeSemanticQuery());
    assertEquals(
        List.of("romance"), result.getPreferenceUpdates().getNegativeSemanticQuery().getGenres());
    assertEquals(
        List.of("romance"), result.getPreferenceRemovals().getPositiveSemanticQuery().getGenres());
    assertEquals(List.of("mustInclude.language"), result.getReplacePreferenceFields());
  }

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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse("fantasy under five hours");
    assertEquals(Intent.RECOMMENDATION, result.getIntent());
    org.junit.jupiter.api.Assertions.assertTrue(result.isAddAuthors());
    org.junit.jupiter.api.Assertions.assertFalse(result.isAddNarrators());
    org.mockito.Mockito.verify(client.prompt())
        .system(
            org.mockito.ArgumentMatchers.<String>argThat(
                prompt ->
                    prompt.contains("self-contained criteria")
                        && prompt.contains("addAuthors/addNarrators")));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "help,HELP",
    "show help,HELP",
    "what can you do,HELP",
    "what else can you do,HELP",
    "what do you do,HELP",
    "how do i use this,HELP",
    "how does this work,HELP",
    "what can i ask,HELP",
    "more,MORE_RESULTS",
    "some more,MORE_RESULTS",
    "more recommendations,MORE_RESULTS",
    "more books,MORE_RESULTS",
    "more audiobooks,MORE_RESULTS",
    "show me more,MORE_RESULTS",
    "give me more,MORE_RESULTS",
    "can i have more,MORE_RESULTS",
    "can i have some more,MORE_RESULTS",
    "could i have more,MORE_RESULTS",
    "could i have some more,MORE_RESULTS",
    "next,MORE_RESULTS",
    "next batch,MORE_RESULTS",
    "next page,MORE_RESULTS",
    "repeat,REPEAT",
    "repeat that,REPEAT",
    "say that again,REPEAT",
    "say it again,REPEAT",
    "repeat yourself,REPEAT",
    "repeat your last response,REPEAT",
    "repeat the last response,REPEAT",
    "repeat your last answer,REPEAT",
    "repeat the books,REPEAT_RECOMMENDATIONS",
    "repeat the recommendations,REPEAT_RECOMMENDATIONS",
    "repeat recommendations,REPEAT_RECOMMENDATIONS",
    "repeat the audiobooks,REPEAT_RECOMMENDATIONS",
    "repeat those books,REPEAT_RECOMMENDATIONS",
    "repeat those recommendations,REPEAT_RECOMMENDATIONS",
    "show those recommendations again,REPEAT_RECOMMENDATIONS",
    "show me the recommendations again,REPEAT_RECOMMENDATIONS",
    "show those books again,REPEAT_RECOMMENDATIONS",
    "explain,EXPLAIN_RECOMMENDATION",
    "explain the recommendations,EXPLAIN_RECOMMENDATION",
    "explain these recommendations,EXPLAIN_RECOMMENDATION",
    "why these books,EXPLAIN_RECOMMENDATION",
    "why these recommendations,EXPLAIN_RECOMMENDATION",
    "why those recommendations,EXPLAIN_RECOMMENDATION",
    "why did i get these recommendations,EXPLAIN_RECOMMENDATION",
    "why did i get these books,EXPLAIN_RECOMMENDATION",
    "why did you recommend these books,EXPLAIN_RECOMMENDATION",
    "why did you recommend these audiobooks,EXPLAIN_RECOMMENDATION",
    "why did you recommend these,EXPLAIN_RECOMMENDATION",
    "why were these recommended,EXPLAIN_RECOMMENDATION",
    "why were these audiobooks recommended,EXPLAIN_RECOMMENDATION",
    "explain all the recommendations,EXPLAIN_RECOMMENDATION",
    "explain these books,EXPLAIN_RECOMMENDATION",
    "why were these books recommended,EXPLAIN_RECOMMENDATION",
    "clear history,CLEAR_HISTORY",
    "clear my history,CLEAR_HISTORY",
    "clear recommendation history,CLEAR_HISTORY",
    "clear my recommendation history,CLEAR_HISTORY",
    "cancel,CANCEL",
    "cancel that,CANCEL",
    "cancel this,CANCEL",
    "cancel the request,CANCEL",
    "never mind,CANCEL",
    "nevermind,CANCEL",
    "stop,CANCEL",
    "forget it,CANCEL",
    "more please,MORE_RESULTS"
  })
  void exactCommandsSkipAiAndPreserveRequestMetadata(String text, Intent intent) {
    var client = mock(ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    for (String raw :
        List.of(
            text,
            "  " + text.toUpperCase(java.util.Locale.ROOT).replace(" ", " \t ") + "?!  ",
            "Hello, could you please " + text + ", thank you!",
            "kindly " + text + " please",
            "Sorry, " + text + ", cheers!",
            "Excuse me—" + text + ", ta.",
            "Hello. " + text + ", many thanks…")) {
      var result = parser.parse(raw);
      assertEquals(intent, result.getIntent());
      assertEquals(raw, result.getRawText());
      assertEquals(7, UUID.fromString(result.getRequestId()).version());
      assertEquals(null, result.getBookCount());
      assertEquals(null, result.getReferenceRank());
      assertEquals(null, result.getReferenceTitle());
      assertEquals(null, result.getPositiveSemanticQuery());
      assertEquals(null, result.getNegativeSemanticQuery());
      assertEquals(false, result.isPersonalised());
    }
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "help me find fantasy books",
        "more, but shorter books",
        "2 more but shorter",
        "2 more by Jane Austen",
        "repeat the second explanation",
        "repeat books by X",
        "cancelled flight books",
        "clear history and recommend fantasy",
        "help/more",
        "please do not clear history",
        "could you repeat the recommendations but exclude romance please",
        "no more please",
        "please explain the second book",
        "hello please help me find fantasy books thanks",
        "no more, thanks",
        "two more please, ta",
        "more British authors, cheers",
        "don’t clear history, thanks",
        "don‘t repeat the recommendations, cheers",
        "recommend books titled Cheers",
        "ta more",
        "would you mind recommending fantasy audiobooks",
        "repeat your last response but shorter",
        "repeat those recommendations except the first",
        "explain the second recommendation",
        "clear my history of horror preferences",
        "cancel that and recommend romance"
      })
  void requestsOutsideExactWhitelistStillUseAi(String text) {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.UNKNOWN);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(text)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(Intent.UNKNOWN, result.getIntent());
    org.mockito.Mockito.verify(client.prompt().system(anyString()).user(text).call())
        .responseEntity(AiParser.REQUEST_CONVERTER);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "1",
        "one",
        "2",
        "the second audiobook",
        "the first one",
        "999999999999999999999th"
      })
  void bareSelectionsSkipAiAndLeaveMeaningToPendingSessionContext(String text) {
    var client = mock(ChatClient.class);
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(Intent.UNKNOWN, result.getIntent());
    assertEquals(text, result.getRawText());
    assertEquals(null, result.getBookCount());
    assertEquals(null, result.getReferenceRank());
    assertEquals(null, result.getSimilarToTitle());
    assertEquals(false, result.isExplicitSearch());
    verifyNoInteractions(client);
  }

  @Test
  void ignoresPolitenessInsideACommandWithoutChangingItsRepeatMeaning() {
    var client = mock(ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    assertEquals(
        Intent.REPEAT_RECOMMENDATIONS,
        parser.parse("repeat please the recommendations").getIntent());
    assertEquals(Intent.REPEAT, parser.parse("could you just repeat that, thanks").getIntent());
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"the second one’s reason", "the second one's reason", "why the second book"})
  void ordinalExplanationGuidanceSeparatesRankFromCount(String text) {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    parsed.setReferenceRank(java.math.BigInteger.valueOf(2));
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(text)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(Intent.EXPLAIN_RECOMMENDATION, result.getIntent());
    assertEquals(2, result.getReferenceRank());
    assertEquals(null, result.getBookCount());
    org.mockito.Mockito.verify(client.prompt())
        .system(
            org.mockito.ArgumentMatchers.<String>argThat(
                prompt ->
                    prompt.contains("the second one’s reason")
                        && prompt.contains("Explanation ordinals select ONE book")));
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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
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
        AiParser.REQUEST_CONVERTER.convert(
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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
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
              .responseEntity(AiParser.REQUEST_CONVERTER))
          .thenAnswer(
              ignored ->
                  new ResponseEntity<>(
                      null,
                      AiParser.REQUEST_CONVERTER.convert(
                          "{\"intent\":\"EXPLAIN_RECOMMENDATION\",\""
                              + field
                              + "\":"
                              + value
                              + "}")));
      var parser =
          new InputParser(
              new CommandParser(),
              new AiParser(
                  client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
      assertThrows(InvalidRequestException.class, () -> parser.parse("bad number"));
    }
  }

  @Test
  void rejectsOversizedInputBeforeCallingTheModel() {
    var client = mock(ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    var builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(client);
    var result = new InputParser(new CommandParser(), new AiParser(builder)).parse("control");
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
            .user("Could you explain how this service works?")
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<>(response, parsed));
    var observer =
        new com.gen3.recommenderagent.observability.AiOperationObserver(
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.core.retry.RetryTemplate.class));
    var result =
        new InputParser(new CommandParser(), new AiParser(client, observer))
            .parse("Could you explain how this service works?");
    assertEquals(Intent.HELP, result.getIntent());
    org.assertj.core.api.Assertions.assertThat(output.getOut())
        .contains(
            "parser.completed mode=AI outcome=SUCCESS durationMs=",
            "operation=parse",
            "model=parser-model",
            "promptTokens=12",
            "completionTokens=3",
            "totalTokens=15",
            "usageAvailable=true");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "2 more",
        "2 more books",
        "2 more recommendations",
        "show me 2 more",
        "please give me 2 more thanks",
        "Could you please show me 2 more audiobooks?"
      })
  void preservesAnExplicitCountOnMoreWithoutCallingAi(String text) {
    var client = mock(ChatClient.class);
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(Intent.MORE_RESULTS, result.getIntent());
    assertEquals(2, result.getBookCount());
    assertEquals(null, result.getReferenceRank());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(text, result.getRawText());
    verifyNoInteractions(client);
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
    var include = new ExactCriteria();
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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsed));
    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(client);

    SessionRequest result =
        new InputParser(new CommandParser(), new AiParser(builder)).parse(rawText);

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
    ExactCriteria mustInclude = new ExactCriteria();
    mustInclude.setLanguage("English");
    mustInclude.setSource("NLS");
    ExactCriteria mustNotInclude = new ExactCriteria();
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
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, parsedRequest));

    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    SessionRequest result =
        new InputParser(new CommandParser(), new AiParser(builder)).parse(rawText);

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
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void logsFastPathTimingWithoutAiUsage(
      org.springframework.boot.test.system.CapturedOutput output) {
    var client = mock(ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    parser.parse("help");
    org.assertj.core.api.Assertions.assertThat(output.getOut())
        .containsPattern("parser.completed mode=FAST_PATH outcome=SUCCESS durationMs=[0-9]+")
        .doesNotContain("ai.operation.completed", "promptTokens=", "completionTokens=");
    verifyNoInteractions(client);
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void logsRejectedInputWithoutClaimingAnAiCall(
      org.springframework.boot.test.system.CapturedOutput output) {
    var client = mock(ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    assertThrows(InvalidRequestException.class, () -> parser.parse(" "));
    org.assertj.core.api.Assertions.assertThat(output.getOut())
        .containsPattern("parser.completed mode=VALIDATION outcome=FAILURE durationMs=[0-9]+")
        .doesNotContain("ai.operation.completed");
    verifyNoInteractions(client);
  }

  @Test
  void commandMatchingCannotBypassInputLengthValidation() {
    var client = mock(ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    assertThrows(
        InvalidRequestException.class,
        () -> parser.parse("help" + " ".repeat(InputParser.MAX_REQUEST_CHARACTERS)));
    verifyNoInteractions(client);
  }

  @Test
  void shouldRejectBlankTextBeforeCallingAi() {
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    InputParser parser = new InputParser(new CommandParser(), new AiParser(builder));

    InvalidRequestException exception =
        assertThrows(InvalidRequestException.class, () -> parser.parse("   "));

    assertEquals("I didn't catch that. What would you like to listen to?", exception.getMessage());
    verifyNoInteractions(chatClient);
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void shouldFailClearlyWhenMockAiReturnsNoParsedRequest(
      org.springframework.boot.test.system.CapturedOutput output) {
    String rawText = "Recommend an audiobook.";

    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient
            .prompt()
            .system(anyString())
            .user(rawText)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, ParsedRequest>(null, null));

    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> new InputParser(new CommandParser(), new AiParser(builder)).parse(rawText));

    assertEquals("AI did not return a ParsedRequest", exception.getMessage());
    org.assertj.core.api.Assertions.assertThat(output.getOut())
        .containsPattern("parser.completed mode=AI outcome=FAILURE durationMs=[0-9]+");
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

  @Test
  void sharedExactCriteriaRetainsBothParserSchemaPropertiesAndLegacySessionJson() {
    var mapper = new ObjectMapper();
    var schema = mapper.readTree(AiParser.REQUEST_CONVERTER.getJsonSchema());
    for (String name : List.of("mustInclude", "mustNotInclude")) {
      var criteria = schema.path("properties").path(name);
      if (criteria.has("$ref")) {
        criteria = schema.at(criteria.path("$ref").asText().substring(1));
      }
      org.assertj.core.api.Assertions.assertThat(criteria.path("properties").propertyNames())
          .containsExactlyInAnyOrder(
              "authors", "narrators", "language", "duration", "publicationYear", "source");
    }
    var session =
        mapper.readValue(
            """
        {"requests":[{"intent":"RECOMMENDATION",
          "mustInclude":{"authors":["Author"],"language":"English","duration":"under 5 hours","publicationYear":"recent","source":"catalogue"},
          "mustNotInclude":{"narrators":["Narrator"],"language":"French"}}]}
        """,
            com.gen3.recommenderagent.domain.session.Session.class);
    var turn = session.getRequests().getFirst();
    org.assertj.core.api.Assertions.assertThat(turn.getMustInclude().getAuthors())
        .containsExactly("Author");
    org.assertj.core.api.Assertions.assertThat(turn.getMustNotInclude().getNarrators())
        .containsExactly("Narrator");
    turn.getMustInclude().setLanguage("German");
    org.assertj.core.api.Assertions.assertThat(turn.getMustNotInclude().getLanguage())
        .isEqualTo("French");
    var json = mapper.readTree(mapper.writeValueAsString(session)).path("requests").get(0);
    org.assertj.core.api.Assertions.assertThat(json.path("mustInclude").path("language").asText())
        .isEqualTo("German");
    org.assertj.core.api.Assertions.assertThat(
            json.path("mustNotInclude").path("narrators").get(0).asText())
        .isEqualTo("Narrator");
  }
}
