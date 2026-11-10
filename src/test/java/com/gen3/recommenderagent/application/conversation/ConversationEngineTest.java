package com.gen3.recommenderagent.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.application.conversation.intent.CancelHandler;
import com.gen3.recommenderagent.application.conversation.intent.ExplainRecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.HelpHandler;
import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RefineHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatHandler;
import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.intent.UpdatePreferencesHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConversationEngineTest {
  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "nothing",
        "Nothing, thanks!",
        "no changes",
        "no",
        "not now",
        "keep my preferences"
      })
  void decliningPreferenceChangesCancelsTheQuestion(String text) {
    var session = new Session();
    session.setBookCount(3);
    var preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(queryWithGenres("romance"));
    session.setPreferences(preferences);
    var update = request(UpdatePreferencesHandler.ACTION);
    var question = conversationState.begin(session, update);
    conversationState.finish(session, question, update);
    var answer = request(UpdatePreferencesHandler.ACTION);
    answer.setRawText(text);
    var result = conversationState.begin(session, answer);
    conversationState.finish(session, result, answer);
    assertNull(result.getOutcome());
    assertNull(session.getPendingQuestionKind());
    assertFalse(session.isAwaitingPreferenceUpdate());
    assertEquals(preferences, session.getPreferences());
    assertEquals(3, session.getBookCount());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"no", "not now", "leave it"})
  void decliningAPendingSelectionCancelsIt(String text) {
    var session = new Session();
    session.setPendingQuestionKind(ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION);
    var reply = request(UnknownHandler.ACTION);
    reply.setRawText(text);
    var result = conversationState.begin(session, reply);
    conversationState.finish(session, result, reply);
    assertNull(result.getOutcome());
    assertNull(session.getPendingQuestionKind());
  }

  @Test
  void failedValidationLeavesTheCachedQueueIntact() {
    var action =
        com.gen3.recommenderagent.domain.conversation.ConversationAction.of("VALIDATION_PROBE");
    var handler = org.mockito.Mockito.mock(ConversationHandler.class);
    org.mockito.Mockito.when(handler.intents()).thenReturn(java.util.Set.of(action));
    org.mockito.Mockito.when(
            handler.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> call.getArgument(1));
    org.mockito.Mockito.when(handler.invalidatesBatch(org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    org.mockito.Mockito.doThrow(
            new com.gen3.recommenderagent.inputparser.InvalidRequestException("Invalid filters"))
        .when(handler)
        .validate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    var engine =
        com.gen3.recommenderagent.testsupport.TestHandlers.engine(java.util.List.of(handler));
    var session = new Session();
    var cached =
        new com.gen3.recommenderagent.domain.recommendation.Recommendation(
            "cached", 1, 1.0, "Cached book");
    session.getActiveSearch().setPendingRecommendations(java.util.List.of(cached));
    var request = new com.gen3.recommenderagent.application.conversation.model.SessionRequest();
    request.setIntent(action);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.process(session, request))
        .isInstanceOf(com.gen3.recommenderagent.inputparser.InvalidRequestException.class);
    org.assertj.core.api.Assertions.assertThat(
            session.getActiveSearch().getPendingRecommendations())
        .containsExactly(cached);
    org.mockito.Mockito.verify(handler, org.mockito.Mockito.never())
        .invalidatesBatch(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void preferenceSpecificDeclinesDoNotCancelOtherQuestions() {
    var session = new Session();
    session.setPendingQuestionKind(ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION);
    var reply = request(UnknownHandler.ACTION);
    reply.setRawText("keep my preferences");
    var result =
        new ClarificationResolver(com.gen3.recommenderagent.testsupport.TestHandlers.registry())
            .resolve(session, reply);
    assertTrue(result.getIntent() != CancelHandler.ACTION);
    assertEquals(
        ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION,
        session.getPendingQuestionKind());
  }

  private final ConversationEngine conversationState = ConversationTestSupport.engine();

  @ParameterizedTest
  @MethodSource("followUpIntents")
  void resolvesFollowUpsFromTheLatestRetainedSearch(ConversationAction intent) {
    Session session = new Session();
    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(searchRequest(RecommendationHandler.ACTION, "fantasy")));
    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(searchRequest(RecommendationHandler.ACTION, "history")));
    ConversationDraft request = request(intent);

    ConversationDraft effective = conversationState.resolve(session, request);

    assertEquals(List.of("history"), effective.getPositiveSemanticQuery().getTopics());
    assertNull(request.getPositiveSemanticQuery());
  }

  @Test
  void combinesSessionPreferencesWithExplicitRequestValues() {
    Session session = new Session();
    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(queryWithGenres("fantasy"));
    ExactCriteria defaults = new ExactCriteria();
    defaults.setLanguage("English");
    defaults.setPublicationYear("before 2000");
    preferences.setMustInclude(defaults);
    session.setPreferences(preferences);
    ConversationDraft request = request(RecommendationHandler.ACTION);
    request.setPositiveSemanticQuery(queryWithGenres("mystery"));
    ExactCriteria explicit = new ExactCriteria();
    explicit.setLanguage("French");
    explicit.setPublicationYear("after 2020");
    request.setMustInclude(explicit);

    ConversationDraft effective = conversationState.resolve(session, request);

    assertEquals(List.of("fantasy", "mystery"), effective.getPositiveSemanticQuery().getGenres());
    assertEquals("French", effective.getMustInclude().getLanguage());
    assertEquals("after 2020", effective.getMustInclude().getPublicationYear());
    assertEquals(List.of("mystery"), request.getPositiveSemanticQuery().getGenres());
  }

  @Test
  void turnsAValidPreferenceReplyIntoAPersonalisedRecommendationWithoutMutatingTheSession() {
    Session session = new Session();
    ConversationDraft pending = request(UnknownHandler.ACTION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    ConversationDraft reply = request(RefineHandler.ACTION);
    reply.setPositiveSemanticQuery(queryWithGenres("fantasy"));

    ConversationDraft effective = conversationState.resolve(session, reply);

    assertEquals(RecommendationHandler.ACTION, effective.getIntent());
    assertTrue(effective.isPersonalised());
    assertEquals(List.of("fantasy"), effective.getPositiveSemanticQuery().getGenres());
    assertNull(session.getPreferences());
    assertEquals(RefineHandler.ACTION, reply.getIntent());
  }

  @Test
  void leavesUnrelatedRequestsPendingAndResolvesBookCountWithoutMutatingTheSession() {
    Session session = new Session();
    session.setBookCount(3);
    ConversationDraft pending = request(UnknownHandler.ACTION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));

    ConversationDraft help = conversationState.resolve(session, request(HelpHandler.ACTION));
    ConversationDraft recommendation =
        conversationState.resolve(session, request(RecommendationHandler.ACTION));

    assertEquals(HelpHandler.ACTION, help.getIntent());
    assertTrue(session.isAwaitingPersonalisedPreferences());
    assertEquals(3, recommendation.getBookCount());
    assertEquals(3, session.getBookCount());
    assertFalse(
        com.gen3.recommenderagent.testsupport.TestHandlers.registry()
            .handler(UpdatePreferencesHandler.ACTION)
            .isSearchAction());
  }

  @ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
        "REPEAT_RECOMMENDATIONS",
        "CHANGE_COUNT",
        "UPDATE_PREFERENCES",
        "CLEAR_HISTORY",
        "HELP",
        "UNKNOWN"
      })
  void ignoresNewSemanticInformationOnControlIntents(ConversationAction intent) {
    var incoming = request(intent);
    incoming.setPositiveSemanticQuery(queryWithGenres("horror"));
    incoming.setNegativeSemanticQuery(queryWithGenres("romance"));
    incoming.setPersonalised(true);
    var effective = conversationState.resolve(new Session(), incoming);
    assertNull(effective.getPositiveSemanticQuery());
    assertNull(effective.getNegativeSemanticQuery());
    assertFalse(effective.isPersonalised());
  }

  @Test
  void helpWithCriteriaDoesNotReplaceTheRetainedSearch() {
    var session = new Session();
    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(searchRequest(RecommendationHandler.ACTION, "romance")));
    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(searchRequest(HelpHandler.ACTION, "horror")));
    var effective = conversationState.resolve(session, request(MoreResultsHandler.ACTION));
    assertEquals(List.of("romance"), effective.getPositiveSemanticQuery().getTopics());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"never mind", "cancel", "stop!"})
  void cancellationClearsThePreferenceQuestionWithoutSearchingOrClearingHistory(String text) {
    var session = new Session();
    var pending = request(RecommendationHandler.ACTION);
    pending.setPreferenceClarificationRequired(true);
    pending.setPersonalised(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    var preferences = new SessionPreferences();
    session.setPreferences(preferences);
    session.getActiveSearch().setShownBookIds(List.of("saved-book"));
    var reply = request(UnknownHandler.ACTION);
    reply.setRawText(text);
    var result = conversationState.begin(session, reply);
    assertEquals(CancelHandler.ACTION, result.getIntent());
    assertFalse(conversationState.needsRecommendations(result));
    conversationState.finish(session, result, reply);
    assertFalse(session.isAwaitingPersonalisedPreferences());
    assertNull(result.getOutcome());
    assertEquals(List.of("saved-book"), session.getActiveSearch().getShownBookIds());
    assertEquals(preferences, session.getPreferences());
    assertNull(conversationState.begin(session, request(RepeatHandler.ACTION)).getOutcome());
  }

  @Test
  void unknownReplyDoesNotBecomeAPersonalisedSearch() {
    var session = new Session();
    var pending = request(RecommendationHandler.ACTION);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    var reply = request(UnknownHandler.ACTION);
    reply.setRawText("huh?");
    assertEquals(UnknownHandler.ACTION, conversationState.begin(session, reply).getIntent());
    assertFalse(conversationState.needsRecommendations(conversationState.begin(session, reply)));
  }

  @ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {"CANCEL", "RESET_PREFERENCES"})
  void abandonedPreferenceSearchCannotBeUsedByMore(ConversationAction action) {
    var session = new Session();
    var pending = searchRequest(RecommendationHandler.ACTION, "horror");
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    var input = request(action);
    var result = conversationState.begin(session, input);
    conversationState.finish(session, result, input);
    var more = conversationState.begin(session, request(MoreResultsHandler.ACTION));
    assertTrue(more.isSearchClarificationRequired());
    assertFalse(conversationState.needsRecommendations(more));
  }

  @Test
  void abandoningPreferenceSearchRestoresCompletedSearchIncludingZeroMatches() {
    var session = new Session();
    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(searchRequest(RecommendationHandler.ACTION, "fantasy")));
    var pending = searchRequest(RecommendationHandler.ACTION, "horror");
    pending.setPreferenceClarificationRequired(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    session.clearPreferenceQuestions();
    var more = conversationState.begin(session, request(MoreResultsHandler.ACTION));
    assertFalse(more.isSearchClarificationRequired());
    assertEquals(List.of("fantasy"), more.getPositiveSemanticQuery().getTopics());
  }

  @Test
  void preferenceUpdateReplacesTheWaitingPersonalisedSearch() {
    var session = new Session();
    var pending = request(RecommendationHandler.ACTION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    session.setPendingQuestionKind(RecommendationHandler.PREFERENCE_CLARIFICATION);
    var update = request(UpdatePreferencesHandler.ACTION);
    var question = conversationState.begin(session, update);
    conversationState.finish(session, question, update);
    var answer = request(RecommendationHandler.ACTION);
    answer.setRawText("horror");
    answer.setPositiveSemanticQuery(queryWithGenres("horror"));
    var resumed = conversationState.begin(session, answer);
    assertEquals(UpdatePreferencesHandler.ACTION, resumed.getIntent());
    assertFalse(conversationState.needsRecommendations(resumed));
    conversationState.finish(session, resumed, answer);
    assertEquals(
        List.of("horror"), session.getPreferences().getPositiveSemanticQuery().getGenres());
    assertNull(session.getPendingQuestion());
  }

  private static Stream<Arguments> followUpIntents() {
    return Stream.of(Arguments.of(RefineHandler.ACTION), Arguments.of(MoreResultsHandler.ACTION));
  }

  private ConversationDraft request(ConversationAction intent) {
    ConversationDraft request = new ConversationDraft();
    request.setIntent(intent);
    return request;
  }

  private ConversationDraft searchRequest(ConversationAction intent, String topic) {
    ConversationDraft request = request(intent);
    request.setPositiveSemanticQuery(queryWithTopics(topic));
    return request;
  }

  private SemanticQuery queryWithTopics(String topic) {
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of(topic));
    return query;
  }

  private SemanticQuery queryWithGenres(String genre) {
    SemanticQuery query = new SemanticQuery();
    query.setGenres(List.of(genre));
    return query;
  }

  @Test
  void retainsOnlyTheLatestSearch() {
    Session session = new Session();
    ConversationDraft firstSearch = searchRequest(RecommendationHandler.ACTION, "fantasy");
    ConversationDraft latestSearch = searchRequest(RecommendationHandler.ACTION, "history");

    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(firstSearch));
    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(latestSearch));

    assertEquals(RecommendationHandler.ACTION, session.getActiveSearch().getSearch().getIntent());
    assertEquals(
        List.of("history"),
        session.getActiveSearch().getSearch().getPositiveSemanticQuery().getTopics());
  }

  @Test
  void doesNotDerivePendingQuestionsFromRetainedRequests() {
    Session session = new Session();
    ConversationDraft pending = new ConversationDraft();
    pending.setPreferenceClarificationRequired(true);

    com.gen3.recommenderagent.testsupport.TestHandlers.retainRequest(
        session, ConversationDraft.toTurn(pending));
    assertFalse(session.isAwaitingPersonalisedPreferences());
    session.clearHistory();
    assertFalse(session.isAwaitingPersonalisedPreferences());
  }
}
