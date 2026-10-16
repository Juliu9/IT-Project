package com.gen3.recommenderagent.domain.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.domain.Intent;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConversationStateTest {

  private final ConversationState conversationState = new ConversationState();

  @ParameterizedTest
  @MethodSource("followUpIntents")
  void resolvesFollowUpsFromTheLatestRetainedSearch(Intent intent) {
    Session session = new Session();
    session.addRequest(searchRequest(Intent.RECOMMENDATION, "fantasy"));
    session.addRequest(searchRequest(Intent.RECOMMENDATION, "history"));
    SessionRequest request = request(intent);

    SessionRequest effective = conversationState.resolve(session, request);

    assertEquals(List.of("history"), effective.getPositiveSemanticQuery().getTopics());
    assertNull(request.getPositiveSemanticQuery());
  }

  @Test
  void combinesSessionPreferencesWithExplicitRequestValues() {
    Session session = new Session();
    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(queryWithGenres("fantasy"));
    MustInclude defaults = new MustInclude();
    defaults.setLanguage("English");
    preferences.setMustInclude(defaults);
    session.setPreferences(preferences);
    SessionRequest request = request(Intent.RECOMMENDATION);
    request.setPositiveSemanticQuery(queryWithGenres("mystery"));
    MustInclude explicit = new MustInclude();
    explicit.setLanguage("French");
    request.setMustInclude(explicit);

    SessionRequest effective = conversationState.resolve(session, request);

    assertEquals(List.of("fantasy", "mystery"), effective.getPositiveSemanticQuery().getGenres());
    assertEquals("French", effective.getMustInclude().getLanguage());
    assertEquals(List.of("mystery"), request.getPositiveSemanticQuery().getGenres());
  }

  @Test
  void turnsAValidPreferenceReplyIntoAPersonalisedRecommendationWithoutMutatingTheSession() {
    Session session = new Session();
    SessionRequest pending = request(Intent.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.addRequest(pending);
    SessionRequest reply = request(Intent.UPDATE_PREFERENCES);
    reply.setPositiveSemanticQuery(queryWithGenres("fantasy"));

    SessionRequest effective = conversationState.resolve(session, reply);

    assertEquals(Intent.RECOMMENDATION, effective.getIntent());
    assertTrue(effective.isPersonalised());
    assertEquals(List.of("fantasy"), effective.getPositiveSemanticQuery().getGenres());
    assertNull(session.getPreferences());
    assertEquals(Intent.UPDATE_PREFERENCES, reply.getIntent());
  }

  @Test
  void leavesUnrelatedRequestsPendingAndResolvesBookCountWithoutMutatingTheSession() {
    Session session = new Session();
    session.setBookCount(3);
    SessionRequest pending = request(Intent.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.addRequest(pending);

    SessionRequest help = conversationState.resolve(session, request(Intent.HELP));
    SessionRequest recommendation =
        conversationState.resolve(session, request(Intent.RECOMMENDATION));

    assertEquals(Intent.HELP, help.getIntent());
    assertTrue(session.isAwaitingPersonalisedPreferences());
    assertEquals(3, recommendation.getBookCount());
    assertEquals(3, session.getBookCount());
    assertFalse(ConversationState.isSearchContext(request(Intent.UPDATE_PREFERENCES)));
  }

  private static Stream<Arguments> followUpIntents() {
    return Stream.of(Arguments.of(Intent.REFINE), Arguments.of(Intent.MORE_RESULTS));
  }

  private SessionRequest request(Intent intent) {
    SessionRequest request = new SessionRequest();
    request.setIntent(intent);
    return request;
  }

  private SessionRequest searchRequest(Intent intent, String topic) {
    SessionRequest request = request(intent);
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
}
