package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ExplanationFlowTest {
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(sessions, workflow);
  private final ConversationResponseGenerator responses =
      new ConversationResponseGenerator(new AiResponseGenerator(null));

  @Test
  void explainsPageRankAndTitleWithoutChangingHistoryOrConsumingMore() throws Exception {
    search();
    String before = saved();
    var all = explain(null, null);
    assertThat(all.getRecommendations()).hasSize(5);
    assertThat(all.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(all.getRecommendations().get(3).isReadingPreferencesUsed()).isTrue();
    assertThat(explain(2, null).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(2);
    assertThat(explain(null, "HARRY POTTER: 4").getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(explain(null, "potter 4").getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(saved()).isEqualTo(before);
    var more = process(Intent.MORE_RESULTS);
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"why these recommendations", "why did I get these recommendations?"})
  void wholePagePhrasesExplainEveryDisplayedBookWithoutAiParsing(String text) throws Exception {
    search();
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var result = service.process("session", "user", parser.parse(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getReferenceRank()).isNull();
    assertThat(result.getReferenceTitle()).isNull();
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1, 2, 3, 4, 5);
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 5})
  void bareExplainUsesTheDisplayedPageWithoutAiParsing(int requestedCount) throws Exception {
    search();
    when(workflow.recommend(any(), any()))
        .thenReturn(
            List.of(new Recommendation("only-book", 1, 0.8, "Only Book", "A fantasy adventure.")));
    var request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setBookCount(requestedCount);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    var page = service.process("session", "user", request);
    assertThat(page.getRecommendations()).hasSize(1);
    String before = saved();
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var result = service.process("session", "user", parser.parse("explain"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("only-book");
    assertThat(responses.generate(result.getRecommendations(), result))
        .contains("Only Book")
        .doesNotContain("Which one did you mean?");
    assertThat(saved()).isEqualTo(before);
    verifyNoInteractions(client);
    verify(workflow, times(2)).recommend(any(), any());
  }

  @Test
  void bareExplainWithoutHistoryReturnsTheExistingFallback() {
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var result = service.process("session", "user", parser.parse("explain"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.NOTHING_TO_EXPLAIN);
    verifyNoInteractions(client, workflow);
  }

  @Test
  void secondBooksReasonExplainsOnlyRankTwoWithoutPendingClarification() {
    search();
    var request = new ConversationDraft();
    request.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    request.setRawText("the second one’s reason");
    request.setReferenceRank(2);
    request.setBookCount(2);
    var result = service.process("session", "user", request);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(2);
    assertThat(responses.generate(result.getRecommendations(), result))
        .contains("Number 2, Harry Potter 2")
        .doesNotContain("Harry Potter 1");
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(5);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 6, 100})
  void invalidRanksReadOutThePageThenExplicitRetryExplainsFourth(int rank) throws Exception {
    search();
    String before = saved();
    var invalid = explain(rank, null);
    assertThat(invalid.getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    assertThat(responses.generate(invalid.getRecommendations(), invalid))
        .isEqualTo(
            "I returned 5 books: 1. Harry Potter 1. 2. Harry Potter 2. 3. Harry Potter 3. 4. Harry Potter 4. 5. Harry Potter 5. Which one did you mean?");
    assertThat(explain(4, null).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(saved()).isEqualTo(before);
    assertThat(process(Intent.REPEAT).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {"UNKNOWN", "CHANGE_COUNT", "REFINE", "RECOMMENDATION"})
  void numericClarificationReplyExplainsBookWithoutChangingCountOrQueue(Intent parsedIntent)
      throws Exception {
    search();
    String before = saved();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(parsedIntent);
    reply.setBookCount(1);
    reply.setRawText(" 1 ");
    var result = service.process("session", "user", reply);
    assertThat(result.getIntent()).isEqualTo(Intent.EXPLAIN_RECOMMENDATION);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(1);
    assertThat(responses.generate(result.getRecommendations(), result))
        .contains("Number 1, Harry Potter 1");
    assertThat(saved()).isEqualTo(before);
    assertThat(process(Intent.REPEAT).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1);
    assertThat(process(Intent.MORE_RESULTS).getRecommendations()).hasSize(5);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {"UNKNOWN", "RECOMMENDATION", "REFINE"})
  void titleReplyUsesThePageRegardlessOfParserLabel(Intent intent) throws Exception {
    search();
    explain(null, "Harry Potter");
    String before = saved();
    var reply = new ConversationDraft();
    reply.setIntent(intent);
    reply.setRawText("Harry Potter 1");
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(1);
    assertThat(saved()).isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "what can you do,HELP",
    "cancel,CANCELLED",
    "more,RECOMMENDATIONS",
    "repeat,EXPLANATION_TARGET_CLARIFICATION",
    "repeat the books,RECOMMENDATIONS",
    "please repeat the recommendations thanks,RECOMMENDATIONS",
    "clear history,HISTORY_CLEARED"
  })
  void fastCommandsUseNormalConversationHandlingWhileSelectionIsPending(
      String text, ConversationDecision expected) {
    search();
    explain(null, "Harry Potter");
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var result = service.process("session", "user", parser.parse(text));
    assertThat(result.getDecision()).isEqualTo(expected);
    if (expected == ConversationDecision.RECOMMENDATIONS) {
      assertThat(result.getRecommendations())
          .extracting(Recommendation::getBookId)
          .containsExactlyElementsOf(
              text.equals("more")
                  ? List.of("book-6", "book-7", "book-8", "book-9", "book-10")
                  : List.of("book-1", "book-2", "book-3", "book-4", "book-5"));
    }
    verifyNoInteractions(client);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitSearchByTitleCanStartANewSearchDuringClarification() {
    search();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(Intent.RECOMMENDATION);
    reply.setRawText("Recommend Harry Potter 1");
    reply.setExplicitSearch(true);
    var criteria = new SemanticQuery();
    criteria.setKeywords(List.of("Harry Potter 1"));
    reply.setPositiveSemanticQuery(criteria);
    assertThat(service.process("session", "user", reply).getIntent())
        .isEqualTo(Intent.RECOMMENDATION);
    verify(workflow, times(2)).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "first",
        "the first one",
        "1.",
        "1st",
        "number one",
        "book 1 please",
        "Harry Potter 1"
      })
  void naturalSelectionRepliesExplainTheChosenBook(String text) throws Exception {
    search();
    String before = saved();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(Intent.UNKNOWN);
    reply.setRawText(text);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(1);
    assertThat(saved()).isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "the second audiobook",
        "book number 2",
        "number two",
        "2nd",
        "the second one",
        "the audiobook number two please!",
        "audiobook 2"
      })
  void secondBookSelectionRepliesUsePendingExplanation(String text) throws Exception {
    search();
    String before = saved();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(Intent.UNKNOWN);
    reply.setRawText(text);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(2);
    assertThat(saved()).isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"3", "three", "3 please"})
  void countQuestionAcceptsShortRepliesAndPreservesTheQueue(String text) throws Exception {
    search();
    var question = process(Intent.CHANGE_COUNT);
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    String before =
        new ObjectMapper()
            .writeValueAsString(sessions.getSession("session", "user").getPendingRecommendations());
    var reply = new ConversationDraft();
    reply.setIntent(Intent.UNKNOWN);
    reply.setRawText(text);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.COUNT_CHANGED);
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(3);
    assertThat(
            new ObjectMapper()
                .writeValueAsString(
                    sessions.getSession("session", "user").getPendingRecommendations()))
        .isEqualTo(before);
    assertThat(process(Intent.MORE_RESULTS).getRecommendations()).hasSize(3);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitlyAskingForTwoMoreReturnsTwoAndKeepsTheRest() {
    search();
    explain(null, "Harry Potter");
    var more = new ConversationDraft();
    more.setIntent(Intent.MORE_RESULTS);
    more.setRawText("2 more");
    more.setBookCount(2);
    assertThat(service.process("session", "user", more).getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-6", "book-7");
    assertThat(sessions.getSession("session", "user").getPendingRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitTwoBookSearchStartsNewSearchDuringSelection() {
    search();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(Intent.RECOMMENDATION);
    reply.setRawText("recommend 2 books");
    reply.setExplicitSearch(true);
    reply.setBookCount(2);
    var result = service.process("session", "user", reply);
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getRecommendations()).hasSize(2);
    verify(workflow, times(2)).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "6",
        "999999999999999999999",
        "the sixth audiobook",
        "book number 999999999999999999999th"
      })
  void invalidNumericReplyKeepsClarifyingUntilAValidSelection(String number) {
    search();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(Intent.UNKNOWN);
    reply.setRawText(number);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    reply.setRawText("1");
    assertThat(service.process("session", "user", reply).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "the second audiobook", "book number 2"})
  void numericReplyOnlySelectsABookWhileClarificationIsPending(String text) {
    search();
    var reply = new ConversationDraft();
    reply.setIntent(Intent.UNKNOWN);
    reply.setRawText(text);
    assertThat(service.process("session", "user", reply).getDecision())
        .isEqualTo(ConversationDecision.UNKNOWN);
    explain(null, "Harry Potter");
    process(Intent.HELP);
    assertThat(service.process("session", "user", reply).getDecision())
        .isEqualTo(ConversationDecision.UNKNOWN);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void ambiguousMissingAndConflictingTitlesClarifyWithoutSelectingSilently() {
    search();
    for (ConversationTurn result :
        List.of(
            explain(null, "Harry Potter"),
            explain(null, "unknown title"),
            explain(null, "!!!"),
            explain(2, "Harry Potter 4"))) {
      assertThat(result.getDecision())
          .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
      assertThat(result.getRecommendations()).hasSize(5);
    }
    assertThat(explain(4, "Harry Potter 4").getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void noSearchAndClearedHistoryReturnFixedMessagesWithoutSearching() {
    var none = explain(null, null);
    assertThat(responses.generate(none.getRecommendations(), none))
        .isEqualTo("I haven't recommended any books yet. Ask me for recommendations first.");
    verifyNoInteractions(workflow);
    search();
    process(Intent.CLEAR_HISTORY);
    assertThat(explain(1, null).getDecision()).isEqualTo(ConversationDecision.NOTHING_TO_EXPLAIN);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void emptyMoreDoesNotExplainAnOlderNonemptyPage() {
    search();
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(Intent.MORE_RESULTS);
    process(Intent.MORE_RESULTS);
    var result = explain(null, null);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_PAGE);
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo("The latest request returned no recommendations to explain.");
    assertThat(process(Intent.REPEAT).getRecommendations()).isEmpty();
    verify(workflow, times(2)).recommend(any(), any());
  }

  @Test
  void explanationPreservesPendingPreferenceQuestion() {
    search();
    var pending = new ConversationDraft();
    pending.setIntent(Intent.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    sessions.getSession("session", "user").getRequests().add(ConversationTurn.fromDraft(pending));
    var explain = explain(4, null);
    assertThat(explain.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_PAGE);
    assertThat(
            sessions
                .getSession("session", "user")
                .getRequests()
                .getLast()
                .isPreferenceClarificationRequired())
        .isTrue();
  }

  @Test
  void invalidRankListsOnlyTheActualShortPage() {
    search();
    var session = sessions.getSession("session", "user");
    session.setPendingRecommendations(session.getPendingRecommendations().subList(0, 2));
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(Intent.MORE_RESULTS);
    var result = explain(6, null);
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo(
            "I returned 2 books: 1. Harry Potter 6. 2. Harry Potter 7. Which one did you mean?");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Why the fourth book?", "1"})
  void apiClarifiesThenExplainsSelectionWithoutReplacingThePage(String reply) throws Exception {
    search();
    String before = saved();
    InputParser parser = mock(InputParser.class);
    when(parser.parse(any()))
        .thenAnswer(
            invocation -> {
              String raw = invocation.getArgument(0);
              var request = new ConversationDraft();
              request.setIntent(raw.equals("1") ? Intent.UNKNOWN : Intent.EXPLAIN_RECOMMENDATION);
              request.setReferenceRank(raw.contains("sixth") ? 6 : 4);
              request.setRawText(raw);
              return request;
            });
    var gateway = new com.gen3.recommenderagent.api.RequestGateway(parser, service, responses);
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(gateway).build();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content("Why the sixth book?"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Which one did you mean?")));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content(reply))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        reply.equals("1")
                            ? "Number 1, Harry Potter 1"
                            : "Number 4, Harry Potter 4")));
    assertThat(saved()).isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/recommendations", "/api/v1/recommendations/demo"})
  void oversizedOrdinalClarifiesThroughTheRealParserAndBothEndpoints(String endpoint)
      throws Exception {
    search();
    String before = saved();
    String number = "1008392481092349012389201382";
    String raw = "explain the " + number + "th one more";
    var client =
        mock(
            org.springframework.ai.chat.client.ChatClient.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(client
            .prompt()
            .system(org.mockito.ArgumentMatchers.anyString())
            .user(raw)
            .call()
            .responseEntity(
                org.mockito.ArgumentMatchers
                    .<org.springframework.ai.converter.StructuredOutputConverter<
                            com.gen3.recommenderagent.inputparser.ParsedRequest>>
                        any()))
        .thenAnswer(
            ignored ->
                new org.springframework.ai.chat.client.ResponseEntity<>(
                    null,
                    new org.springframework.ai.converter.BeanOutputConverter<>(
                            com.gen3.recommenderagent.inputparser.ParsedRequest.class)
                        .convert(
                            "{\"intent\":\"EXPLAIN_RECOMMENDATION\",\"referenceRank\":"
                                + number
                                + "}")));
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var gateway = new com.gen3.recommenderagent.api.RequestGateway(parser, service, responses);
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                gateway,
                new com.gen3.recommenderagent.api.DemoWebsite(
                    gateway, mock(com.gen3.catalogue.port.AudiobookDetailsLookup.class)))
            .build();
    String body =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                    .header("X-Session-Id", "session")
                    .header("X-User-Id", "user")
                    .contentType("text/plain")
                    .content(raw))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("Which one did you mean?", "Harry Potter 1", "Harry Potter 5");
    assertThat(saved()).isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  private void search() {
    when(workflow.recommend(any(), any()))
        .thenReturn(
            IntStream.rangeClosed(1, 10)
                .mapToObj(
                    i -> {
                      var book =
                          new Recommendation(
                              "book-" + i,
                              i,
                              0.8,
                              "Harry Potter " + i,
                              "A fantasy adventure at a school of magic.");
                      book.setReadingPreferencesUsed(i == 4);
                      return book;
                    })
                .toList());
    var search = new ConversationDraft();
    search.setIntent(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    search.setPositiveSemanticQuery(query);
    service.process("session", "user", search);
  }

  private ConversationTurn explain(Integer rank, String title) {
    var request = new ConversationDraft();
    request.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    request.setReferenceRank(rank);
    request.setReferenceTitle(title);
    return service.process("session", "user", request);
  }

  private ConversationTurn process(Intent intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    return service.process("session", "user", request);
  }

  private String saved() throws Exception {
    var session = sessions.getSession("session", "user");
    return new ObjectMapper()
        .writeValueAsString(
            List.of(
                session.getRequests(),
                session.getPendingRecommendations(),
                session.getShownBooks()));
  }
}
