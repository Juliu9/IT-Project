package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
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

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "2,the last one,2",
    "3,last book,3",
    "2,the latter,2",
    "2,the former,1",
    "5,the final one please,5"
  })
  void relativeSelectionsUseTheDisplayedPageAfterHelpAndReload(
      int count, String text, int expectedRank) {
    var setting = new ConversationDraft();
    setting.setIntent(ConversationAction.CHANGE_COUNT);
    setting.setBookCount(count);
    service.process("session", "user", setting);
    search();
    explain(99, null);
    process(ConversationAction.HELP);
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    var ai = mock(AiParser.class);
    var result =
        service.process("session", "user", new InputParser(new CommandParser(), ai).parse(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(expectedRank);
    verifyNoInteractions(ai);
  }

  @Test
  void latterWithMoreThanTwoBooksSelectsTheLast() {
    search();
    explain(99, null);
    var result =
        service.process(
            "session",
            "user",
            new InputParser(new CommandParser(), mock(AiParser.class)).parse("the latter"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(5);
  }

  @Test
  void numericTitleIsUsedWhenTheNumberIsNotADisplayedRank() {
    search();
    var session = sessions.getSession("session", "user");
    session.getActiveSearch().getSearch().getRecommendations().get(1).setTitle("1984");
    sessions.updateSession(session);
    explain(99, null);
    var ai = mock(AiParser.class);
    var result =
        service.process("session", "user", new InputParser(new CommandParser(), ai).parse("1984"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getReferenceRank()).isNull();
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getTitle)
        .containsExactly("1984");
    verifyNoInteractions(ai);
  }

  @Test
  void validRanksKeepPriorityOverNumericTitlesAndDuplicateTitlesStayAmbiguous() {
    search();
    var session = sessions.getSession("session", "user");
    session.getActiveSearch().getSearch().getRecommendations().get(3).setTitle("2");
    sessions.updateSession(session);
    explain(99, null);
    var chosen =
        service.process(
            "session",
            "user",
            new InputParser(new CommandParser(), mock(AiParser.class)).parse("2"));
    assertThat(chosen.getRecommendations()).extracting(Recommendation::getRank).containsExactly(2);
    session = sessions.getSession("session", "user");
    session.getActiveSearch().getSearch().getRecommendations().get(0).setTitle("1984");
    session.getActiveSearch().getSearch().getRecommendations().get(1).setTitle("1984");
    sessions.updateSession(session);
    explain(99, null);
    assertThat(
            service
                .process(
                    "session",
                    "user",
                    new InputParser(new CommandParser(), mock(AiParser.class)).parse("1984"))
                .getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"all", "all of them", "all of these please", "every book", "each one"})
  void aWholePageReplyExplainsEveryDisplayedBookAfterHelpAndReload(String reply) {
    search();
    explain(6, null);
    process(ConversationAction.HELP);
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    clearInvocations(workflow);
    var result =
        service.process(
            "session",
            "user",
            new com.gen3.recommenderagent.inputparser.InputParser(
                    new com.gen3.recommenderagent.inputparser.CommandParser(),
                    mock(com.gen3.recommenderagent.inputparser.AiParser.class))
                .parse(reply));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).hasSize(5);
    assertThat(result.getReferenceRank()).isNull();
    assertThat(result.getReferenceTitle()).isNull();
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    verifyNoInteractions(workflow);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "please, the second one",
        "Could you please explain number two?",
        "Hello, could you please choose number two? Thanks!"
      })
  void politeSelectionsWorkAfterHelpAndSessionReload(String reply) {
    search();
    explain(6, null);
    var help = process(ConversationAction.HELP);
    assertThat(responses.generate(help.getRecommendations(), help))
        .contains("Which one would you like me to explain?");
    var repeated = process(ConversationAction.REPEAT);
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .isEqualTo(responses.generate(help.getRecommendations(), help));
    var mapper = new ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")),
            com.gen3.recommenderagent.domain.session.Session.class));
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new InputParser(
            new CommandParser(),
            new AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var result = service.process("session", "user", parser.parse(reply));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(2);
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    verifyNoInteractions(client);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"CHANGE_COUNT", "UPDATE_PREFERENCES", "RESET_PREFERENCES"})
  void settingsChangesAbandonPendingBookSelection(ConversationAction action) {
    search();
    explain(6, null);
    var request = new ConversationDraft();
    request.setIntent(action);
    if (action == ConversationAction.CHANGE_COUNT) {
      request.setBookCount(2);
    }
    if (action == ConversationAction.UPDATE_PREFERENCES) {
      var preferences = new SessionPreferences();
      var query = new SemanticQuery();
      query.setGenres(List.of("horror"));
      preferences.setPositiveSemanticQuery(query);
      request.setPreferenceUpdates(preferences);
    }
    service.process("session", "user", request);
    var selection = new ConversationDraft();
    selection.setIntent(ConversationAction.UNKNOWN);
    selection.setRawText("2");
    var result = service.process("session", "user", selection);
    assertThat(result.getIntent()).isEqualTo(ConversationAction.UNKNOWN);
    assertThat(result.getRecommendations()).isEmpty();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {
        "RECOMMENDATION",
        "REFINE",
        "MORE_RESULTS",
        "REPEAT_RECOMMENDATIONS",
        "CANCEL",
        "CLEAR_HISTORY"
      })
  void searchAndPageActionsReplacePendingSelection(ConversationAction action) {
    search();
    explain(6, null);
    var request = new ConversationDraft();
    request.setIntent(action);
    if (action == ConversationAction.RECOMMENDATION || action == ConversationAction.REFINE) {
      request.setExplicitSearch(true);
      var query = new SemanticQuery();
      query.setGenres(List.of("horror"));
      request.setPositiveSemanticQuery(query);
    }
    service.process("session", "user", request);
    assertThat(sessions.getSession("session", "user").getPendingDecision()).isNull();
    var selection = new ConversationDraft();
    selection.setIntent(ConversationAction.UNKNOWN);
    selection.setRawText("2");
    assertThat(service.process("session", "user", selection).getDecision())
        .isEqualTo(ConversationDecision.UNKNOWN);
  }

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
    var more = process(ConversationAction.MORE_RESULTS);
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
    request.setIntent(ConversationAction.RECOMMENDATION);
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
        .doesNotContain("Which one would you like me to explain?");
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
    request.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
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
            "I shared 5 books with you: 1. Harry Potter 1. 2. Harry Potter 2. 3. Harry Potter 3. 4. Harry Potter 4. 5. Harry Potter 5. Which one would you like me to explain?");
    assertThat(explain(4, null).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(saved()).isEqualTo(before);
    assertThat(process(ConversationAction.REPEAT).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"UNKNOWN", "CHANGE_COUNT", "REFINE", "RECOMMENDATION"})
  void numericClarificationReplyExplainsBookWithoutChangingCountOrQueue(
      ConversationAction parsedIntent) throws Exception {
    search();
    String before = saved();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(parsedIntent);
    reply.setBookCount(1);
    reply.setRawText(" 1 ");
    var result = service.process("session", "user", reply);
    assertThat(result.getIntent()).isEqualTo(ConversationAction.EXPLAIN_RECOMMENDATION);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(1);
    assertThat(responses.generate(result.getRecommendations(), result))
        .contains("Number 1, Harry Potter 1");
    assertThat(saved()).isEqualTo(before);
    assertThat(process(ConversationAction.REPEAT).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1);
    assertThat(process(ConversationAction.MORE_RESULTS).getRecommendations()).hasSize(5);
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"UNKNOWN", "RECOMMENDATION", "REFINE"})
  void titleReplyUsesThePageRegardlessOfParserLabel(ConversationAction intent) throws Exception {
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
    reply.setIntent(ConversationAction.RECOMMENDATION);
    reply.setRawText("Recommend Harry Potter 1");
    reply.setExplicitSearch(true);
    var criteria = new SemanticQuery();
    criteria.setKeywords(List.of("Harry Potter 1"));
    reply.setPositiveSemanticQuery(criteria);
    assertThat(service.process("session", "user", reply).getIntent())
        .isEqualTo(ConversationAction.RECOMMENDATION);
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
    reply.setIntent(ConversationAction.UNKNOWN);
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
    reply.setIntent(ConversationAction.UNKNOWN);
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
    var question = process(ConversationAction.CHANGE_COUNT);
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    String before =
        new ObjectMapper()
            .writeValueAsString(
                sessions
                    .getSession("session", "user")
                    .getActiveSearch()
                    .getPendingRecommendations());
    var reply = new ConversationDraft();
    reply.setIntent(ConversationAction.UNKNOWN);
    reply.setRawText(text);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.COUNT_CHANGED);
    assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(3);
    assertThat(
            new ObjectMapper()
                .writeValueAsString(
                    sessions
                        .getSession("session", "user")
                        .getActiveSearch()
                        .getPendingRecommendations()))
        .isEqualTo(before);
    assertThat(process(ConversationAction.MORE_RESULTS).getRecommendations()).hasSize(3);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitlyAskingForTwoMoreReturnsTwoAndKeepsTheRest() {
    search();
    explain(null, "Harry Potter");
    var more = new ConversationDraft();
    more.setIntent(ConversationAction.MORE_RESULTS);
    more.setRawText("2 more");
    more.setBookCount(2);
    assertThat(service.process("session", "user", more).getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-6", "book-7");
    assertThat(sessions.getSession("session", "user").getActiveSearch().getPendingRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitTwoBookSearchStartsNewSearchDuringSelection() {
    search();
    explain(null, "Harry Potter");
    var reply = new ConversationDraft();
    reply.setIntent(ConversationAction.RECOMMENDATION);
    reply.setRawText("recommend 2 books");
    reply.setExplicitSearch(true);
    reply.setBookCount(2);
    var result = service.process("session", "user", reply);
    assertThat(result.getIntent()).isEqualTo(ConversationAction.RECOMMENDATION);
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
    reply.setIntent(ConversationAction.UNKNOWN);
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
  void numericReplyNeedsAQuestionAndHelpPreservesThatQuestion(String text) {
    search();
    var reply = new ConversationDraft();
    reply.setIntent(ConversationAction.UNKNOWN);
    reply.setRawText(text);
    assertThat(service.process("session", "user", reply).getDecision())
        .isEqualTo(ConversationDecision.UNKNOWN);
    explain(null, "Harry Potter");
    process(ConversationAction.HELP);
    assertThat(service.process("session", "user", reply).getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION);
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
        .isEqualTo(
            "I haven't recommended any books in this session yet. What kind of audiobook would you like?");
    verifyNoInteractions(workflow);
    search();
    process(ConversationAction.CLEAR_HISTORY);
    assertThat(explain(1, null).getDecision()).isEqualTo(ConversationDecision.NOTHING_TO_EXPLAIN);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void emptyMoreDoesNotExplainAnOlderNonemptyPage() {
    search();
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(ConversationAction.MORE_RESULTS);
    process(ConversationAction.MORE_RESULTS);
    var result = explain(null, null);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_PAGE);
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo(
            "I didn't find any books in the latest search to explain. Would you like to try a different search?");
    assertThat(process(ConversationAction.REPEAT).getRecommendations()).isEmpty();
    verify(workflow, times(2)).recommend(any(), any());
  }

  @Test
  void explanationAbandonsPendingPreferenceQuestion() {
    search();
    var pending = new ConversationDraft();
    pending.setIntent(ConversationAction.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    var session = sessions.getSession("session", "user");
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    sessions.updateSession(session);
    var explain = explain(4, null);
    assertThat(explain.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
    assertThat(explain.getRecommendations()).extracting(Recommendation::getRank).containsExactly(4);
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
  }

  @Test
  void invalidRankListsOnlyTheActualShortPage() {
    search();
    var session = sessions.getSession("session", "user");
    session
        .getActiveSearch()
        .setPendingRecommendations(
            session.getActiveSearch().getPendingRecommendations().subList(0, 2));
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(ConversationAction.MORE_RESULTS);
    var result = explain(6, null);
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo(
            "I shared 2 books with you: 1. Harry Potter 6. 2. Harry Potter 7. Which one would you like me to explain?");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Why the fourth book?", "1"})
  void apiClarifiesThenExplainsSelectionWithoutReplacingThePage(String reply) throws Exception {
    search();
    String before = saved();
    InputParser parser = mock(InputParser.class);
    when(parser.parseUserIntent(
            any(),
            org.mockito.ArgumentMatchers
                .<java.util.function.Supplier<com.gen3.recommenderagent.inputparser.SessionContext>>
                    any()))
        .thenAnswer(
            invocation -> {
              String raw = invocation.getArgument(0);
              var intent = new UserIntent();
              intent.setIntent(
                  raw.equals("1")
                      ? ConversationAction.UNKNOWN
                      : ConversationAction.EXPLAIN_RECOMMENDATION);
              intent.setReferenceRank(java.math.BigInteger.valueOf(raw.contains("sixth") ? 6 : 4));
              return intent;
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
                .string(
                    org.hamcrest.Matchers.containsString(
                        "Which one would you like me to explain?")));
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
                            com.gen3.recommenderagent.inputparser.UserIntent>>
                        any()))
        .thenAnswer(
            ignored ->
                new org.springframework.ai.chat.client.ResponseEntity<>(
                    null,
                    new org.springframework.ai.converter.BeanOutputConverter<>(
                            com.gen3.recommenderagent.inputparser.UserIntent.class)
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
    assertThat(body)
        .contains("Which one would you like me to explain?", "Harry Potter 1", "Harry Potter 5");
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
    search.setIntent(ConversationAction.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    search.setPositiveSemanticQuery(query);
    service.process("session", "user", search);
  }

  private ConversationTurn explain(Integer rank, String title) {
    var request = new ConversationDraft();
    request.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
    request.setReferenceRank(rank);
    request.setReferenceTitle(title);
    return service.process("session", "user", request);
  }

  private ConversationTurn process(ConversationAction intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    return service.process("session", "user", request);
  }

  private String saved() throws Exception {
    var session = sessions.getSession("session", "user");
    return new ObjectMapper()
        .writeValueAsString(
            List.of(
                session.getActiveSearch().getSearch(),
                session.getActiveSearch().getPendingRecommendations(),
                session.getActiveSearch().getShownBookIds()));
  }
}
