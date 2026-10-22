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
    defaults.setPublicationYear("before 2000");
    preferences.setMustInclude(defaults);
    session.setPreferences(preferences);
    SessionRequest request = request(Intent.RECOMMENDATION);
    request.setPositiveSemanticQuery(queryWithGenres("mystery"));
    MustInclude explicit = new MustInclude();
    explicit.setLanguage("French");
    explicit.setPublicationYear("after 2020");
    request.setMustInclude(explicit);

    SessionRequest effective = conversationState.resolve(session, request);

    assertEquals(List.of("fantasy", "mystery"), effective.getPositiveSemanticQuery().getGenres());
    assertEquals("French", effective.getMustInclude().getLanguage());
    assertEquals("after 2020", effective.getMustInclude().getPublicationYear());
    assertEquals(List.of("mystery"), request.getPositiveSemanticQuery().getGenres());
  }

  @Test
  void turnsAValidPreferenceReplyIntoAPersonalisedRecommendationWithoutMutatingTheSession() {
    Session session = new Session();
    SessionRequest pending = request(Intent.UNKNOWN);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.addRequest(pending);
    SessionRequest reply = request(Intent.REFINE);
    reply.setPositiveSemanticQuery(queryWithGenres("fantasy"));

    SessionRequest effective = conversationState.resolve(session, reply);

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

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {
        "MORE_RESULTS",
        "REPEAT",
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
    session.addRequest(searchRequest(Intent.RECOMMENDATION, "romance"));
    session.addRequest(searchRequest(Intent.HELP, "horror"));
    var effective = conversationState.resolve(session, request(Intent.MORE_RESULTS));
    assertEquals(List.of("romance"), effective.getPositiveSemanticQuery().getTopics());
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
