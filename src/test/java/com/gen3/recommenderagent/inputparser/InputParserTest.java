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

import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.databind.ObjectMapper;

class InputParserTest {
  @Test
  void aiReceivesExplicitPageRanksSeparatelyFromTheLatestAction() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.UNKNOWN);
    when(client
            .prompt()
            .system(anyString())
            .user(anyString())
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var context =
        new SessionContext(
            null,
            "EXPLAIN_RECOMMENDATION",
            List.of(
                new SessionContext.DisplayedBook(6, "The Hobbit"),
                new SessionContext.DisplayedBook(8, "Dune")));
    org.mockito.Mockito.clearInvocations(client.prompt().system(anyString()));
    new AiParser(client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
        .parse("Find something like the Tolkien book you showed me", context);
    var message = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(client.prompt().system(anyString())).user(message.capture());
    org.assertj.core.api.Assertions.assertThat(message.getValue())
        .contains(
            "Last action: EXPLAIN_RECOMMENDATION",
            "Retained recommendations (original ranks): rank 6: The Hobbit | rank 8: Dune",
            "User text (untrusted data):",
            "Find something like the Tolkien book you showed me");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Recommend zero gravity audiobooks",
        "Recommend zero waste books",
        "zero tolerance books"
      })
  void descriptiveZeroPhrasesReachAiWithoutBeingRewritten(String text) {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    parsed.setInvalidBookCount(true);
    parsed.setBookCount(java.math.BigInteger.ZERO);
    var query = new SemanticQuery();
    String subject =
        text.substring(text.toLowerCase(java.util.Locale.ROOT).indexOf("zero"))
            .replaceFirst(" (?:audiobooks|books)$", "");
    query.setKeywords(List.of(subject));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse(text)).thenReturn(parsed);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(false, result.isInvalidBookCount());
    assertEquals(List.of(subject), result.getPositiveSemanticQuery().getKeywords());
    assertEquals(null, result.getBookCount());
    org.mockito.Mockito.verify(ai).parse(text);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Recommend zero  gravity audiobooks",
        "Recommend zero\tgravity audiobooks",
        "Recommend zero\ngravity audiobooks",
        "Recommend zero\u00a0gravity audiobooks",
        "Recommend zero-gravity audiobooks",
        "Recommend zero\u2010gravity audiobooks",
        "Recommend zero\u2011gravity audiobooks",
        "Recommend zero\u2013gravity audiobooks"
      })
  void zeroSubjectChecksIgnoreWhitespaceDifferences(String text) {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    parsed.setInvalidBookCount(true);
    var query = new SemanticQuery();
    query.setKeywords(List.of("zero gravity"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse(text)).thenReturn(parsed);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(false, result.isInvalidBookCount());
    assertEquals(text, result.getRawText());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"explain .5", "explain 2.5", "why book .5"})
  void fractionalExplanationPositionsCannotBecomeWholeRanks(String text) {
    var ai = mock(AiParser.class);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(ConversationAction.EXPLAIN_RECOMMENDATION, result.getIntent());
    assertEquals(0, result.getReferenceRank());
    verifyNoInteractions(ai);
  }

  @Test
  void zeroTopicComparisonDoesNotSuppressAnExplicitInvalidQuantityOrChangePeople() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setKeywords(List.of("zero gravity"));
    parsed.setPositiveSemanticQuery(query);
    var exact = new com.gen3.recommenderagent.domain.search.ExactCriteria();
    exact.setAuthors(List.of("Anne-Marie Smith"));
    parsed.setMustInclude(exact);
    when(ai.parse(anyString())).thenReturn(parsed);
    var result =
        new InputParser(new CommandParser(), ai)
            .parse("Recommend 0 books about zero-gravity by Anne-Marie Smith");
    assertEquals(true, result.isInvalidBookCount());
    assertEquals(List.of("Anne-Marie Smith"), result.getMustInclude().getAuthors());
  }

  @Test
  void ambiguousWordCountsCanStillBeRejectedByAiWithoutLosingCriteria() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    parsed.setInvalidBookCount(true);
    var query = new SemanticQuery();
    query.setGenres(List.of("mystery"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse("Give me zero mystery books")).thenReturn(parsed);
    var result = new InputParser(new CommandParser(), ai).parse("Give me zero mystery books");
    assertEquals(true, result.isInvalidBookCount());
    assertEquals(List.of("mystery"), result.getPositiveSemanticQuery().getGenres());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"give me some books", "Give me some audiobooks please", "recommend some books"})
  void genericBookRequestsUsePersonalisedRecommendationsWithoutAi(String text) {
    var ai = mock(AiParser.class);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(ConversationAction.RECOMMENDATION, result.getIntent());
    assertEquals(true, result.isPersonalised());
    assertEquals(true, result.isExplicitSearch());
    assertEquals(null, result.getMustInclude());
    assertEquals(null, result.getPositiveSemanticQuery());
    verifyNoInteractions(ai);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "any author is fine now,mustInclude.authors",
    "any narrator,mustInclude.narrators",
    "remove the time limit,mustInclude.duration",
    "any publication year,mustInclude.publicationYear"
  })
  void clearingOneSearchFilterUsesTheFastPath(String text, String field) {
    var ai = mock(AiParser.class);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(ConversationAction.REFINE, result.getIntent());
    assertEquals(List.of(field), result.getReplaceSearchFields());
    verifyNoInteractions(ai);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Also include mystery in that search",
        "Please add mystery",
        "Additionally include mystery",
        "Make it more horror",
        "Make it more romance",
        "Make them more romantic",
        "More mystery please"
      })
  void explicitAdditionsIgnoreErroneousModelReplacementFlags(String text) {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.REFINE);
    parsed.setReplaceSearchFields(List.of("positiveSemanticQuery.genres"));
    var query = new SemanticQuery();
    query.setGenres(List.of("mystery"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse(anyString())).thenReturn(parsed);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(List.of(), result.getReplaceSearchFields());
    assertEquals(List.of("mystery"), result.getPositiveSemanticQuery().getGenres());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(
      delimiter = '|',
      value = {
        "I want two and a half mystery books|I want some mystery books",
        "Give me 0 mystery books|Give me some mystery books",
        "Give me minus two mystery books|Give me some mystery books",
        "Give me 2.5 mystery books|Give me some mystery books"
      })
  void invalidRecommendationCountsDoNotInventANumericCountForAi(String text, String modelText) {
    var ai = mock(AiParser.class);
    var countOnly = new UserIntent();
    countOnly.setIntent(ConversationAction.CHANGE_COUNT);
    when(ai.parse(anyString())).thenReturn(countOnly);
    var recommendation = new UserIntent();
    recommendation.setIntent(ConversationAction.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("mystery"));
    recommendation.setPositiveSemanticQuery(query);
    when(ai.parse(modelText)).thenReturn(recommendation);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(true, result.isInvalidBookCount());
    assertEquals(null, result.getBookCount());
    assertNotNull(result.getPositiveSemanticQuery());
    assertEquals(List.of("mystery"), result.getPositiveSemanticQuery().getGenres());
    org.mockito.Mockito.verify(ai).parse(modelText);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(
      delimiter = '|',
      value = {
        "0 more|1 more",
        "zero books|some books",
        "set count to zero|set count to 1",
        "2.5|1",
        "more and make it zero at a time|more and make it 1 at a time"
      })
  void invalidControlCountsKeepARecognisableNumericCommand(String text, String modelText) {
    var check = CountInput.check(text);
    assertEquals(true, check.invalid());
    assertEquals(modelText, check.modelText());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "books from around 2000", "books from the period 2000",
        "Recommend audiobooks from around 2000",
            "Please give me some books from the period 2000, thanks!"
      })
  void completeApproximateYearRequestsUseRecommendationsWithoutAi(String text) {
    var ai = mock(AiParser.class);
    var wrongIntent = new UserIntent();
    wrongIntent.setIntent(ConversationAction.REFINE);
    when(ai.parse(anyString())).thenReturn(wrongIntent);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(ConversationAction.RECOMMENDATION, result.getIntent());
    assertEquals(true, result.isExplicitSearch());
    assertEquals(false, result.isPersonalised());
    var bounds =
        new com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy()
            .filters(result, java.util.Set.of())
            .mustInclude();
    assertEquals(1995, bounds.minimumPublicationYear());
    assertEquals(2005, bounds.maximumPublicationYear());
    assertEquals(null, result.getPositiveSemanticQuery());
    verifyNoInteractions(ai);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "books from around 2000 under two hours",
        "three books from around 2000",
        "a book from around 2000",
        "change the year to around 2000",
        "books from around 2000 by Suzanne Collins"
      })
  void approximateYearFastPathDoesNotSwallowOtherCriteriaOrContext(String text) {
    assertEquals(null, new CommandParser().parse(text));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "{\"positiveSemanticQuery\":{\"genres\":[\"fantasy\"]}}",
        "{\"intent\":null,\"positiveSemanticQuery\":{\"genres\":[\"fantasy\"]}}"
      })
  void missingOrNullModelIntentsBecomeUnknown(String json) {
    var ai = mock(AiParser.class);
    var parsed = new ObjectMapper().readValue(json, UserIntent.class);
    when(ai.parse(anyString())).thenReturn(parsed);
    var result = new InputParser(new CommandParser(), ai).parse("an unrecognised request");
    assertEquals(ConversationAction.UNKNOWN, result.getIntent());
    assertEquals(null, result.getPositiveSemanticQuery());
  }

  @Test
  void invalidCountIntentCorrectionHappensBeforeSemanticFieldsCanBeCleared() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.CHANGE_COUNT);
    parsed.setBookCount(java.math.BigInteger.ONE);
    var genre = new SemanticQuery();
    genre.setGenres(List.of("mystery"));
    parsed.setPositiveSemanticQuery(genre);
    when(ai.parse(anyString())).thenReturn(parsed);
    var result =
        new InputParser(new CommandParser(), ai).parse("I want two and a half mystery books");
    assertEquals(ConversationAction.RECOMMENDATION, result.getIntent());
    assertEquals(true, result.isInvalidBookCount());
    assertEquals(List.of("mystery"), result.getPositiveSemanticQuery().getGenres());
  }

  @Test
  void plainAuthorRequestsCannotAccidentallyRequirePersonalisationButKeepNameCasing() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    parsed.setPersonalised(true);
    var exact = new com.gen3.recommenderagent.domain.search.ExactCriteria();
    exact.setAuthors(List.of("Suzanne Collins"));
    parsed.setMustInclude(exact);
    when(ai.parse(anyString())).thenReturn(parsed);
    var parser = new InputParser(new CommandParser(), ai);
    var result = parser.parse("I want a book by Suzanne Collins");
    assertEquals(false, result.isPersonalised());
    assertEquals(List.of("Suzanne Collins"), result.getMustInclude().getAuthors());
    assertEquals(
        true,
        parser
            .parse("I want a book by Suzanne Collins for me based on my preferences")
            .isPersonalised());
  }

  @Test
  void modelRecencyCriteriaNormalizeBeforeLeavingTheParser() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    var exact = new com.gen3.recommenderagent.domain.search.ExactCriteria();
    exact.setPublicationYear("more recent");
    parsed.setMustInclude(exact);
    when(ai.parse(anyString())).thenReturn(parsed);
    var result =
        new InputParser(new CommandParser(), ai)
            .parse("I want the same kinds of books but from more recent times");
    assertEquals(ConversationAction.REFINE, result.getIntent());
    assertEquals("more recent", result.getMustInclude().getPublicationYear());
    assertEquals(true, result.isExplicitSearch());
  }

  @Test
  void anAdditionCanAlsoExplicitlyReplaceAnotherField() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.REFINE);
    parsed.setReplaceSearchFields(List.of("mustInclude.duration"));
    when(ai.parse(anyString())).thenReturn(parsed);
    assertEquals(
        List.of("mustInclude.duration"),
        new InputParser(new CommandParser(), ai)
            .parse("also remove the time limit")
            .getReplaceSearchFields());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Also include mystery and remove the time limit",
        "Make it more mystery and remove the time limit"
      })
  void compoundAdditionKeepsTheRemovalButCannotReplaceTheAddedGenre(String text) {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.REFINE);
    parsed.setReplaceSearchFields(List.of("positiveSemanticQuery.genres", "mustInclude.duration"));
    var query = new SemanticQuery();
    query.setGenres(List.of("mystery"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse(anyString())).thenReturn(parsed);
    var result = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(List.of("mustInclude.duration"), result.getReplaceSearchFields());
    assertEquals(List.of("mystery"), result.getPositiveSemanticQuery().getGenres());
  }

  @Test
  void modelSearchReplacementsAreCopiedAndInvalidPathsAreRejected() {
    var ai = mock(AiParser.class);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.REFINE);
    parsed.setReplaceSearchFields(List.of("positiveSemanticQuery.genres"));
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse(anyString())).thenReturn(parsed);
    assertEquals(
        List.of("positiveSemanticQuery.genres"),
        new InputParser(new CommandParser(), ai)
            .parse("make it horror instead")
            .getReplaceSearchFields());
    parsed.setReplaceSearchFields(List.of("requestId"));
    assertThrows(
        InvalidRequestException.class,
        () -> new InputParser(new CommandParser(), ai).parse("remove that filter"));
  }

  @Test
  void moreRecentIsARefinementComparedWithTheDisplayedPageWithoutAi() {
    var ai = mock(AiParser.class);
    var result = new InputParser(new CommandParser(), ai).parse("more recent");
    assertEquals(ConversationAction.REFINE, result.getIntent());
    assertEquals("more recent", result.getMustInclude().getPublicationYear());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(false, result.isPersonalised());
    verifyNoInteractions(ai);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"no", "keep my preferences", "nothing", "No, thanks!"})
  void preferenceDeclinesSkipAiButNeedContextToCancel(String text) {
    var ai = mock(AiParser.class);
    var parsed = new InputParser(new CommandParser(), ai).parse(text);
    assertEquals(ConversationAction.UNKNOWN, parsed.getIntent());
    verifyNoInteractions(ai);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"yes", "yes please", "yeah", "sure", "go ahead", "okay"})
  void confirmationsSkipAiAndLetTheSessionResolveTheirMeaning(String text) {
    var ai = mock(AiParser.class);
    assertEquals(
        ConversationAction.UNKNOWN,
        new InputParser(new CommandParser(), ai).parse(text).getIntent());
    verifyNoInteractions(ai);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "change count to 3 at a time,3",
    "4 books at a time,4",
    "three audiobooks at a time,3",
    "set the count to two,2",
    "give me four recommendations at a time,4",
    "only three at a time,3",
    "3 per page,3",
    "change count to three books,3",
    "only 2 audiobooks per page,2"
  })
  void explicitCountCommandsDoNotBecomePreferenceUpdates(String text, int count) {
    var parsed = new CommandParser().parse(text);
    assertNotNull(parsed);
    assertEquals(ConversationAction.CHANGE_COUNT, parsed.getIntent());
    assertEquals(count, parsed.getBookCount().intValue());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "explain two more",
        "explain book two more",
        "explain the second book in more detail",
        "Could you explain number 2 more please?",
        "explain 999999999999999999999 more"
      })
  void explanationMoreSelectsOneOriginalRankWithoutAi(String text) {
    var parsed = new CommandParser().parse(text);
    assertNotNull(parsed);
    assertEquals(ConversationAction.EXPLAIN_RECOMMENDATION, parsed.getIntent());
    assertEquals(
        text.contains("999") ? Integer.MAX_VALUE : 2, parsed.getReferenceRank().intValue());
    assertEquals(null, parsed.getBookCount());
  }

  @Test
  void referencePromptDistinguishesPluralRecommendationsFromMultipleReferenceTitles() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    parsed.setSimilarToTitle("The Hunger Games");
    String text = "books similar to The Hunger Games";
    when(client
            .prompt()
            .system(anyString())
            .user(text)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    org.mockito.Mockito.clearInvocations(client.prompt());
    var result =
        new AiParser(client, com.gen3.recommenderagent.observability.AiOperationObserver.noop())
            .parse(text);
    assertEquals("The Hunger Games", result.getSimilarToTitle());
    assertEquals(false, result.isSimilarToClarificationRequired());
    var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(client.prompt()).system(prompt.capture());
    org.assertj.core.api.Assertions.assertThat(prompt.getValue())
        .contains(
            "Plural recommendation words such as books or audiobooks do not imply multiple references or series scope.",
            "\"books similar to The Hunger Games\" -> {\"intent\":\"RECOMMENDATION\",\"similarToTitle\":\"The Hunger Games\",\"similarToClarificationRequired\":false}");
  }

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
    org.assertj.core.api.Assertions.assertThat(
            schema.path("properties").path("positiveSemanticQuery").path("description").asText())
        .contains("every stated positive taste", "exact filters");
  }

  @Test
  void sparseExplanationOutputUsesExistingDefaultsWithoutInventingCountOrCriteria() {
    var request =
        parseModelJson(
            "Tell me the reason for position two",
            "{\"intent\":\"EXPLAIN_RECOMMENDATION\",\"referenceRank\":2}");
    assertEquals(ConversationAction.EXPLAIN_RECOMMENDATION, request.getIntent());
    assertEquals(2, request.getReferenceRank());
    assertEquals(null, request.getBookCount());
    assertEquals(null, request.getPositiveSemanticQuery());
    assertEquals(null, request.getMustInclude());
    assertEquals(null, request.getPreferenceUpdates());
    assertEquals(false, request.isExplicitSearch());
    assertEquals(false, request.isPersonalised());
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
            "Amend my tastes to stop liking romance",
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
            new ResponseEntity<ChatResponse, UserIntent>(
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
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse("avoid romance and any language is fine");
    assertEquals(ConversationAction.UPDATE_PREFERENCES, result.getIntent());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(null, result.getNegativeSemanticQuery());
    assertEquals(
        List.of("romance"), result.getPreferenceUpdates().getNegativeSemanticQuery().getGenres());
    assertEquals(
        List.of("romance"), result.getPreferenceRemovals().getPositiveSemanticQuery().getGenres());
    assertEquals(List.of("mustInclude.language"), result.getReplacePreferenceFields());
  }

  @Test
  void routesStandaloneCriteriaWithTheCurrentSinglePersonPrompt() {
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("fantasy under five hours")
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse("fantasy under five hours");
    assertEquals(ConversationAction.RECOMMENDATION, result.getIntent());
    org.mockito.Mockito.verify(client.prompt())
        .system(
            org.mockito.ArgumentMatchers.<String>argThat(
                prompt ->
                    prompt.contains("self-contained criteria")
                        && prompt.contains(
                            "Each author or narrator field supports at most one person")));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "help,HELP",
    "show help,HELP",
    "what can you do,HELP",
    "which operations do you support,HELP",
    "what operations do you support,HELP",
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
    "show me the same books again,REPEAT_RECOMMENDATIONS",
    "show me the same audiobook list you gave me earlier,REPEAT_RECOMMENDATIONS",
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
    "reset,RESET_PREFERENCES",
    "Reset please,RESET_PREFERENCES",
    "reset preferences,RESET_PREFERENCES",
    "reset my preferences,RESET_PREFERENCES",
    "reset session preferences,RESET_PREFERENCES",
    "clear preferences,RESET_PREFERENCES",
    "clear my preferences,RESET_PREFERENCES",
    "reset my tastes,RESET_PREFERENCES",
    "reset preferences and count,RESET_PREFERENCES",
    "clear,CLEAR_HISTORY",
    "Clear please,CLEAR_HISTORY",
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
  void exactCommandsSkipAiAndPreserveRequestMetadata(String text, ConversationAction intent) {
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
        "give me some books by Suzanne Collins",
        "more recent than 2000",
        "do not reset",
        "clear and recommend fantasy",
        "only three at a time by Jane Austen",
        "not now but add horror to my preferences",
        "no horror",
        "4 books at a time by Jane Austen",
        "do not change count to 3 at a time",
        "more, but shorter books",
        "2 more but shorter",
        "2 more by Jane Austen",
        "another five but shorter",
        "another book by Jane Austen",
        "not another one",
        "another five fantasy books",
        "repeat the second explanation",
        "repeat books by X",
        "cancelled flight books",
        "clear history and recommend fantasy",
        "help/more",
        "please do not clear history",
        "could you repeat the recommendations but exclude romance please",
        "no more please",
        "hello please help me find fantasy books thanks",
        "no more, thanks",
        "more British authors, cheers",
        "don’t clear history, thanks",
        "don‘t repeat the recommendations, cheers",
        "recommend books titled Cheers",
        "ta more",
        "would you mind recommending fantasy audiobooks",
        "repeat your last response but shorter",
        "repeat those recommendations except the first",
        "clear my history of horror preferences",
        "cancel that and recommend romance"
      })
  void requestsOutsideExactWhitelistStillUseAi(String text) {
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.UNKNOWN);
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(text)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(ConversationAction.UNKNOWN, result.getIntent());
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
    assertEquals(ConversationAction.UNKNOWN, result.getIntent());
    assertEquals(text, result.getRawText());
    assertEquals(null, result.getBookCount());
    assertEquals(null, result.getReferenceRank());
    assertEquals(null, result.getSimilarToTitle());
    assertEquals(false, result.isExplicitSearch());
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "make them shorter",
        "make those audiobooks shorter",
        "Can you make the previous books shorter",
        "make it shorter",
        "shorter ones",
        "something shorter",
        "Please make them shorter, thanks!"
      })
  void shorterFollowUpsAreRefinementsWithoutInventedGenreOrDuration(String text) {
    var client = mock(ChatClient.class);
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(ConversationAction.REFINE, result.getIntent());
    assertEquals(true, result.isShorter());
    assertEquals(true, result.isExplicitSearch());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(null, result.getMustInclude());
    assertEquals(null, result.getPreferenceUpdates());
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
        ConversationAction.REPEAT_RECOMMENDATIONS,
        parser.parse("repeat please the recommendations").getIntent());
    assertEquals(
        ConversationAction.REPEAT, parser.parse("could you just repeat that, thanks").getIntent());
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"the second one’s reason", "the second one's reason"})
  void ordinalExplanationGuidanceSeparatesRankFromCount(String text) {
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
    parsed.setReferenceRank(java.math.BigInteger.valueOf(2));
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(text)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(ConversationAction.EXPLAIN_RECOMMENDATION, result.getIntent());
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
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
    parsed.setReferenceRank(java.math.BigInteger.valueOf(6));
    parsed.setReferenceTitle("Harry Potter");
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user("why")
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse("why");
    assertEquals(ConversationAction.EXPLAIN_RECOMMENDATION, result.getIntent());
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
    String raw = "why was the " + number + "th book recommended";
    when(client
            .prompt()
            .system(anyString())
            .user(raw)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(raw);
    assertEquals(
        number.startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE, result.getReferenceRank());
    assertEquals(null, result.getBookCount());
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
      value = ConversationAction.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
        "REPEAT_RECOMMENDATIONS",
        "EXPLAIN_RECOMMENDATION",
        "CHANGE_COUNT",
        "UPDATE_PREFERENCES",
        "RESET_PREFERENCES",
        "CLEAR_HISTORY",
        "HELP",
        "CANCEL",
        "UNKNOWN"
      })
  void stripsUnexpectedSemanticInformationFromControlIntents(ConversationAction intent) {
    var parsed = new UserIntent();
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
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
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
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.HELP);
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
    assertEquals(ConversationAction.HELP, result.getIntent());
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
        "two more please, ta",
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
    assertEquals(ConversationAction.MORE_RESULTS, result.getIntent());
    assertEquals(2, result.getBookCount());
    assertEquals(null, result.getReferenceRank());
    assertEquals(null, result.getPositiveSemanticQuery());
    assertEquals(text, result.getRawText());
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "another,1",
    "another 1,1",
    "another one,1",
    "another five,5",
    "another book,1",
    "another audiobook,1",
    "another recommendation,1",
    "another 5 books,5",
    "another two audiobooks,2",
    "another ten recommendations,10",
    "Give me another five,5",
    "Show me another 3 books,3",
    "Can I have another one?,1",
    "Could I have another five please?,5",
    "Could you please give me another five? Thanks!,5",
    "I'd like another book,1",
    "I would like another five,5",
    "Recommend another one,1",
    "another 999999999999999999999999,2147483647"
  })
  void anotherCommandsPreserveCountWithoutAi(String text, int count) {
    var client = mock(ChatClient.class);
    var result =
        new InputParser(
                new CommandParser(),
                new AiParser(
                    client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()))
            .parse(text);
    assertEquals(ConversationAction.MORE_RESULTS, result.getIntent());
    assertEquals(count, result.getBookCount());
    assertEquals(null, result.getReferenceRank());
    assertEquals(null, result.getPositiveSemanticQuery());
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
    var parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
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
    UserIntent parsed = new UserIntent();
    parsed.setIntent(ConversationAction.RECOMMENDATION);
    parsed.setPersonalised(true);
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(anyString())
            .user(rawText)
            .call()
            .responseEntity(AiParser.REQUEST_CONVERTER))
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsed));
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

    UserIntent parsedRequest = new UserIntent();
    SemanticQuery negativeSemanticQuery = new SemanticQuery();
    negativeSemanticQuery.setKeywords(List.of("romance"));
    parsedRequest.setIntent(ConversationAction.RECOMMENDATION);
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
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, parsedRequest));

    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    SessionRequest result =
        new InputParser(new CommandParser(), new AiParser(builder)).parse(rawText);

    assertNotNull(result);
    assertEquals(rawText, result.getRawText());
    assertEquals(ConversationAction.RECOMMENDATION, result.getIntent());
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
        .thenReturn(new ResponseEntity<ChatResponse, UserIntent>(null, null));

    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    when(builder.build()).thenReturn(chatClient);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> new InputParser(new CommandParser(), new AiParser(builder)).parse(rawText));

    assertEquals("AI did not return a UserIntent", exception.getMessage());
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
}
