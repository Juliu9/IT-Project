package com.gen3.recommenderagent.application.conversation.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
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

class ConversationDispatcherTest {
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
    var update = request(ConversationAction.UPDATE_PREFERENCES);
    var question = conversationState.begin(session, update);
    conversationState.finish(session, question, update);
    var answer = request(ConversationAction.UPDATE_PREFERENCES);
    answer.setRawText(text);
    var result = conversationState.begin(session, answer);
    conversationState.finish(session, result, answer);
    assertEquals(ConversationDecision.CANCELLED, result.getDecision());
    assertNull(session.getPendingDecision());
    assertFalse(session.isAwaitingPreferenceUpdate());
    assertEquals(preferences, session.getPreferences());
    assertEquals(3, session.getBookCount());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"no", "not now", "leave it"})
  void decliningAPendingSelectionCancelsIt(String text) {
    var session = new Session();
    session.setPendingDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    var reply = request(ConversationAction.UNKNOWN);
    reply.setRawText(text);
    var result = conversationState.begin(session, reply);
    conversationState.finish(session, result, reply);
    assertEquals(ConversationDecision.CANCELLED, result.getDecision());
    assertNull(session.getPendingDecision());
  }

  @Test
  void preferenceSpecificDeclinesDoNotCancelOtherQuestions() {
    var session = new Session();
    session.setPendingDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    var reply = request(ConversationAction.UNKNOWN);
    reply.setRawText("keep my preferences");
    var result = new ClarificationResolver().resolve(session, reply);
    assertTrue(result.getIntent() != ConversationAction.CANCEL);
    assertEquals(
        ConversationDecision.EXPLANATION_TARGET_CLARIFICATION, session.getPendingDecision());
  }

  private final ConversationDispatcher conversationState = ConversationTestSupport.dispatcher();

  @ParameterizedTest
  @MethodSource("followUpIntents")
  void resolvesFollowUpsFromTheLatestRetainedSearch(ConversationAction intent) {
    Session session = new Session();
    ConversationHistory.retainRequest(
        session,
        ConversationDraft.toTurn(searchRequest(ConversationAction.RECOMMENDATION, "fantasy")));
    ConversationHistory.retainRequest(
        session,
        ConversationDraft.toTurn(searchRequest(ConversationAction.RECOMMENDATION, "history")));
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
    ConversationDraft request = request(ConversationAction.RECOMMENDATION);
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
    ConversationDraft pending = request(ConversationAction.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    ConversationDraft reply = request(ConversationAction.REFINE);
    reply.setPositiveSemanticQuery(queryWithGenres("fantasy"));

    ConversationDraft effective = conversationState.resolve(session, reply);

    assertEquals(ConversationAction.RECOMMENDATION, effective.getIntent());
    assertTrue(effective.isPersonalised());
    assertEquals(List.of("fantasy"), effective.getPositiveSemanticQuery().getGenres());
    assertNull(session.getPreferences());
    assertEquals(ConversationAction.REFINE, reply.getIntent());
  }

  @Test
  void leavesUnrelatedRequestsPendingAndResolvesBookCountWithoutMutatingTheSession() {
    Session session = new Session();
    session.setBookCount(3);
    ConversationDraft pending = request(ConversationAction.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));

    ConversationDraft help = conversationState.resolve(session, request(ConversationAction.HELP));
    ConversationDraft recommendation =
        conversationState.resolve(session, request(ConversationAction.RECOMMENDATION));

    assertEquals(ConversationAction.HELP, help.getIntent());
    assertTrue(session.isAwaitingPersonalisedPreferences());
    assertEquals(3, recommendation.getBookCount());
    assertEquals(3, session.getBookCount());
    assertFalse(ConversationRules.isSearchContext(request(ConversationAction.UPDATE_PREFERENCES)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
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
    ConversationHistory.retainRequest(
        session,
        ConversationDraft.toTurn(searchRequest(ConversationAction.RECOMMENDATION, "romance")));
    ConversationHistory.retainRequest(
        session, ConversationDraft.toTurn(searchRequest(ConversationAction.HELP, "horror")));
    var effective = conversationState.resolve(session, request(ConversationAction.MORE_RESULTS));
    assertEquals(List.of("romance"), effective.getPositiveSemanticQuery().getTopics());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"never mind", "cancel", "stop!"})
  void cancellationClearsThePreferenceQuestionWithoutSearchingOrClearingHistory(String text) {
    var session = new Session();
    var pending = request(ConversationAction.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    pending.setPersonalised(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    var preferences = new SessionPreferences();
    session.setPreferences(preferences);
    session.getActiveSearch().setShownBookIds(List.of("saved-book"));
    var reply = request(ConversationAction.UNKNOWN);
    reply.setRawText(text);
    var result = conversationState.begin(session, reply);
    assertEquals(ConversationAction.CANCEL, result.getIntent());
    assertFalse(conversationState.needsRecommendations(result));
    conversationState.finish(session, result, reply);
    assertFalse(session.isAwaitingPersonalisedPreferences());
    assertEquals(ConversationDecision.CANCELLED, result.getDecision());
    assertEquals(List.of("saved-book"), session.getActiveSearch().getShownBookIds());
    assertEquals(preferences, session.getPreferences());
    assertEquals(
        ConversationDecision.CANCELLED,
        conversationState.begin(session, request(ConversationAction.REPEAT)).getDecision());
  }

  @Test
  void unknownReplyDoesNotBecomeAPersonalisedSearch() {
    var session = new Session();
    var pending = request(ConversationAction.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    var reply = request(ConversationAction.UNKNOWN);
    reply.setRawText("huh?");
    assertEquals(ConversationAction.UNKNOWN, conversationState.begin(session, reply).getIntent());
    assertFalse(conversationState.needsRecommendations(conversationState.begin(session, reply)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"CANCEL", "RESET_PREFERENCES"})
  void abandonedPreferenceSearchCannotBeUsedByMore(ConversationAction action) {
    var session = new Session();
    var pending = searchRequest(ConversationAction.RECOMMENDATION, "horror");
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    var input = request(action);
    var result = conversationState.begin(session, input);
    conversationState.finish(session, result, input);
    var more = conversationState.begin(session, request(ConversationAction.MORE_RESULTS));
    assertTrue(more.isSearchClarificationRequired());
    assertFalse(conversationState.needsRecommendations(more));
  }

  @Test
  void abandoningPreferenceSearchRestoresCompletedSearchIncludingZeroMatches() {
    var session = new Session();
    ConversationHistory.retainRequest(
        session,
        ConversationDraft.toTurn(searchRequest(ConversationAction.RECOMMENDATION, "fantasy")));
    var pending = searchRequest(ConversationAction.RECOMMENDATION, "horror");
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    session.clearPreferenceQuestions();
    var more = conversationState.begin(session, request(ConversationAction.MORE_RESULTS));
    assertFalse(more.isSearchClarificationRequired());
    assertEquals(List.of("fantasy"), more.getPositiveSemanticQuery().getTopics());
  }

  @Test
  void preferenceUpdateReplacesTheWaitingPersonalisedSearch() {
    var session = new Session();
    var pending = request(ConversationAction.RECOMMENDATION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationDraft.toTurn(pending));
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    var update = request(ConversationAction.UPDATE_PREFERENCES);
    var question = conversationState.begin(session, update);
    conversationState.finish(session, question, update);
    var answer = request(ConversationAction.RECOMMENDATION);
    answer.setRawText("horror");
    answer.setPositiveSemanticQuery(queryWithGenres("horror"));
    var resumed = conversationState.begin(session, answer);
    assertEquals(ConversationAction.UPDATE_PREFERENCES, resumed.getIntent());
    assertFalse(conversationState.needsRecommendations(resumed));
    conversationState.finish(session, resumed, answer);
    assertEquals(
        List.of("horror"), session.getPreferences().getPositiveSemanticQuery().getGenres());
    assertNull(session.getPendingQuestion());
  }

  private static Stream<Arguments> followUpIntents() {
    return Stream.of(
        Arguments.of(ConversationAction.REFINE), Arguments.of(ConversationAction.MORE_RESULTS));
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
    ConversationDraft firstSearch = searchRequest(ConversationAction.RECOMMENDATION, "fantasy");
    ConversationDraft latestSearch = searchRequest(ConversationAction.RECOMMENDATION, "history");

    ConversationHistory.retainRequest(session, ConversationDraft.toTurn(firstSearch));
    ConversationHistory.retainRequest(session, ConversationDraft.toTurn(latestSearch));

    assertEquals(
        ConversationAction.RECOMMENDATION, session.getActiveSearch().getSearch().getIntent());
    assertEquals(
        List.of("history"),
        session.getActiveSearch().getSearch().getPositiveSemanticQuery().getTopics());
  }

  @Test
  void doesNotDerivePendingQuestionsFromRetainedRequests() {
    Session session = new Session();
    ConversationDraft pending = new ConversationDraft();
    pending.setPreferenceClarificationRequired(true);

    ConversationHistory.retainRequest(session, ConversationDraft.toTurn(pending));
    assertFalse(session.isAwaitingPersonalisedPreferences());
    session.clearHistory();
    assertFalse(session.isAwaitingPersonalisedPreferences());
  }
}
