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
    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(pending));
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
    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(pending));

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
    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(pending));
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
    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(pending));
    var reply = request(Intent.UNKNOWN);
    reply.setRawText("huh?");
    assertEquals(Intent.UNKNOWN, conversationState.begin(session, reply).getIntent());
    assertFalse(conversationState.needsRecommendations(conversationState.begin(session, reply)));
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
  void derivesPendingPersonalisedPreferencesFromRetainedRequests() {
    Session session = new Session();
    ConversationDraft pending = new ConversationDraft();
    pending.setPreferenceClarificationRequired(true);

    ConversationHistory.retainRequest(session, ConversationTurn.fromDraft(pending));
    assertTrue(session.isAwaitingPersonalisedPreferences());

    session.clearHistory();
    assertFalse(session.isAwaitingPersonalisedPreferences());
  }
}
