package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionTest {

  @Test
  void defaultsAndCapsBookCountAtTheApplicationMaximum() {
    Session session = new Session();

    assertEquals(MAX_BOOK_COUNT, session.getBookCount());

    session.setBookCount(MAX_BOOK_COUNT + 3);
    assertEquals(MAX_BOOK_COUNT, session.getBookCount());

    session.setBookCount(3);
    assertEquals(3, session.getBookCount());

    session.setBookCount(null);
    assertEquals(MAX_BOOK_COUNT, session.getBookCount());

    session.setBookCount(0);
    assertEquals(1, session.getBookCount());
  }

  @Test
  void storesOnlyUniqueShownBookIds() {
    Session session = new Session();

    session.addRecommendationIds(List.of("book-1", "book-2"));
    session.addRecommendationIds(List.of("book-2", "book-3"));

    assertEquals(List.of("book-1", "book-2", "book-3"), session.getShownBooks());
  }

  @Test
  void keepsOnlyTheLatestSearchAndLatestNonSearchRequest() {
    Session session = new Session();
    SessionRequest firstSearch = searchRequest("fantasy");
    SessionRequest help = new SessionRequest();
    SessionRequest latestSearch = searchRequest("history");

    session.addRequest(firstSearch);
    session.addRequest(help);
    session.addRequest(latestSearch);

    assertEquals(List.of(help, latestSearch), session.getRequests());
  }

  @Test
  void derivesPendingPersonalisedPreferencesFromRetainedRequests() {
    Session session = new Session();
    SessionRequest pending = new SessionRequest();
    pending.setPreferenceClarificationRequired(true);

    session.addRequest(pending);
    assertTrue(session.isAwaitingPersonalisedPreferences());

    session.clearHistory();
    assertFalse(session.isAwaitingPersonalisedPreferences());
  }

  private SessionRequest searchRequest(String topic) {
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of(topic));
    SessionRequest request = new SessionRequest();
    request.setPositiveSemanticQuery(query);
    return request;
  }
}
