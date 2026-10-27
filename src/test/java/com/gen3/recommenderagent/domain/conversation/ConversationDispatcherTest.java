package com.gen3.recommenderagent.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
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
    var update = request(Intent.UPDATE_PREFERENCES);
    var question = conversationState.begin(session, update);
    conversationState.finish(session, question, update);
    var answer = request(Intent.UPDATE_PREFERENCES);
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
    var reply = request(Intent.UNKNOWN);
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
    var reply = request(Intent.UNKNOWN);
    reply.setRawText("keep my preferences");
    var result = new ClarificationResolver().resolve(session, reply);
    assertTrue(result.getIntent() != Intent.CANCEL);
    assertEquals(
        ConversationDecision.EXPLANATION_TARGET_CLARIFICATION, session.getPendingDecision());
  }

  private final ConversationDispatcher conversationState = ConversationTestSupport.dispatcher();

  @ParameterizedTest
  @MethodSource("followUpIntents")
  void resolvesFollowUpsFromTheLatestRetainedSearch(Intent intent) {
    Session session = new Session();
    ConversationHistory.retainRequest(
        session, ConversationTurn.fromDraft(searchRequest(Intent.RECOMMENDATION, "fantasy")));
    ConversationHistory.retainRequest(
        session, ConversationTurn.fromDraft(searchRequest(Intent.RECOMMENDATION, "history")));
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
    ConversationDraft request = request(Intent.RECOMMENDATION);
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
    ConversationDraft pending = request(Intent.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
    ConversationDraft reply = request(Intent.REFINE);
    reply.setPositiveSemanticQuery(queryWithGenres("fantasy"));

    ConversationDraft effective = conversationState.resolve(session, reply);

    assertEquals(Intent.RECOMMENDATION, effective.getIntent());
    assertTrue(effective.isPersonalised());
    assertEquals(List.of("fantasy"), effective.getPositiveSemanticQuery().getGenres());
    assertNull(session.getPreferences());
    assertEquals(Intent.REFINE, reply.getIntent());
  }

  @Test
  void leavesUnrelatedRequestsPendingAndResolvesBookCountWithoutMutatingTheSession() {
    Session session = new Session();
    session.setBookCount(3);
    ConversationDraft pending = request(Intent.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));

    ConversationDraft help = conversationState.resolve(session, request(Intent.HELP));
    ConversationDraft recommendation =
        conversationState.resolve(session, request(Intent.RECOMMENDATION));

    assertEquals(Intent.HELP, help.getIntent());
    assertTrue(session.isAwaitingPersonalisedPreferences());
    assertEquals(3, recommendation.getBookCount());
    assertEquals(3, session.getBookCount());
    assertFalse(ConversationRules.isSearchContext(request(Intent.UPDATE_PREFERENCES)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
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
  void ignoresNewSemanticInformationOnControlIntents(Intent intent) {
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
        session, ConversationTurn.fromDraft(searchRequest(Intent.RECOMMENDATION, "romance")));
    ConversationHistory.retainRequest(
        session, ConversationTurn.fromDraft(searchRequest(Intent.HELP, "horror")));
    var effective = conversationState.resolve(session, request(Intent.MORE_RESULTS));
    assertEquals(List.of("romance"), effective.getPositiveSemanticQuery().getTopics());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"never mind", "cancel", "stop!"})
  void cancellationClearsThePreferenceQuestionWithoutSearchingOrClearingHistory(String text) {
    var session = new Session();
    var pending = request(Intent.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    pending.setPersonalised(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
    var preferences = new SessionPreferences();
    session.setPreferences(preferences);
    session.setShownBooks(List.of("saved-book"));
    var reply = request(Intent.UNKNOWN);
    reply.setRawText(text);
    var result = conversationState.begin(session, reply);
    assertEquals(Intent.CANCEL, result.getIntent());
    assertFalse(conversationState.needsRecommendations(result));
    conversationState.finish(session, result, reply);
    assertFalse(session.isAwaitingPersonalisedPreferences());
    assertEquals(ConversationDecision.CANCELLED, result.getDecision());
    assertEquals(List.of("saved-book"), session.getShownBooks());
    assertEquals(preferences, session.getPreferences());
    assertEquals(
        ConversationDecision.CANCELLED,
        conversationState.begin(session, request(Intent.REPEAT)).getDecision());
  }

  @Test
  void unknownReplyDoesNotBecomeAPersonalisedSearch() {
    var session = new Session();
    var pending = request(Intent.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
    var reply = request(Intent.UNKNOWN);
    reply.setRawText("huh?");
    assertEquals(Intent.UNKNOWN, conversationState.begin(session, reply).getIntent());
    assertFalse(conversationState.needsRecommendations(conversationState.begin(session, reply)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {"CANCEL", "RESET_PREFERENCES"})
  void abandonedPreferenceSearchCannotBeUsedByMore(Intent action) {
    var session = new Session();
    var pending = searchRequest(Intent.RECOMMENDATION, "horror");
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
    var input = request(action);
    var result = conversationState.begin(session, input);
    conversationState.finish(session, result, input);
    var more = conversationState.begin(session, request(Intent.MORE_RESULTS));
    assertTrue(more.isSearchClarificationRequired());
    assertFalse(conversationState.needsRecommendations(more));
  }

  @Test
  void abandoningPreferenceSearchRestoresCompletedSearchIncludingZeroMatches() {
    var session = new Session();
    ConversationHistory.retainRequest(
        session, ConversationTurn.fromDraft(searchRequest(Intent.RECOMMENDATION, "fantasy")));
    var pending = searchRequest(Intent.RECOMMENDATION, "horror");
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
    session.clearPreferenceQuestions();
    var more = conversationState.begin(session, request(Intent.MORE_RESULTS));
    assertFalse(more.isSearchClarificationRequired());
    assertEquals(List.of("fantasy"), more.getPositiveSemanticQuery().getTopics());
  }

  @Test
  void countClarificationDoesNotRestoreTheAbandonedBookSelection() {
    var session = new Session();
    session.setPendingDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    var input = request(Intent.CHANGE_COUNT);
    var count = conversationState.begin(session, input);
    conversationState.finish(session, count, input);
    assertEquals(ConversationDecision.COUNT_CLARIFICATION, session.getPendingDecision());
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    session = mapper.readValue(mapper.writeValueAsString(session), Session.class);
    var answer = request(Intent.UNKNOWN);
    answer.setRawText("2");
    var changed = conversationState.begin(session, answer);
    conversationState.finish(session, changed, answer);
    assertEquals(2, session.getBookCount());
    assertNull(session.getPendingQuestion());
    var selected = new ClarificationResolver().resolve(session, answer);
    assertEquals(Intent.UNKNOWN, selected.getIntent());
  }

  @Test
  void preferenceUpdateReplacesTheWaitingPersonalisedSearch() {
    var session = new Session();
    var pending = request(Intent.RECOMMENDATION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    ((com.gen3.recommenderagent.domain.session.PendingQuestion.Preference)
            session.getPendingQuestion())
        .setRequest(ConversationTurn.fromDraft(pending));
    session.setPendingDecision(ConversationDecision.PREFERENCE_CLARIFICATION);
    var update = request(Intent.UPDATE_PREFERENCES);
    var question = conversationState.begin(session, update);
    conversationState.finish(session, question, update);
    var answer = request(Intent.RECOMMENDATION);
    answer.setRawText("horror");
    answer.setPositiveSemanticQuery(queryWithGenres("horror"));
    var resumed = conversationState.begin(session, answer);
    assertEquals(Intent.UPDATE_PREFERENCES, resumed.getIntent());
    assertFalse(conversationState.needsRecommendations(resumed));
    conversationState.finish(session, resumed, answer);
    assertEquals(
        List.of("horror"), session.getPreferences().getPositiveSemanticQuery().getGenres());
    assertNull(session.getPendingQuestion());
  }

  private static Stream<Arguments> followUpIntents() {
    return Stream.of(Arguments.of(Intent.REFINE), Arguments.of(Intent.MORE_RESULTS));
  }

  private ConversationDraft request(Intent intent) {
    ConversationDraft request = new ConversationDraft();
    request.setIntent(intent);
    return request;
  }

  private ConversationDraft searchRequest(Intent intent, String topic) {
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
  void keepsOnlyTheLatestSearchAndLatestNonSearchRequest() {
    Session session = new Session();
    ConversationDraft firstSearch = searchRequest(Intent.RECOMMENDATION, "fantasy");
    ConversationDraft help = new ConversationDraft();
    ConversationDraft latestSearch = searchRequest(Intent.RECOMMENDATION, "history");

    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(firstSearch));
    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(help));
    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(latestSearch));

    assertEquals(2, session.getRequests().size());
    assertEquals(help.getIntent(), session.getRequests().get(0).getIntent());
    assertEquals(
        List.of("history"), session.getRequests().get(1).getPositiveSemanticQuery().getTopics());
  }

  @Test
  void doesNotDerivePendingQuestionsFromRetainedRequests() {
    Session session = new Session();
    ConversationDraft pending = new ConversationDraft();
    pending.setPreferenceClarificationRequired(true);

    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(pending));
    assertFalse(session.isAwaitingPersonalisedPreferences());
    session.clearHistory();
    assertFalse(session.isAwaitingPersonalisedPreferences());
  }
}
