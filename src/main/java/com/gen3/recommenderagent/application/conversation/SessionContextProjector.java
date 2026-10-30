package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.SessionContext;
import java.util.List;
import org.springframework.stereotype.Component;

/** Projects the retained page independently of the latest action and pending question. */
@Component
public class SessionContextProjector {
  private static final int MAX_DISPLAYED_BOOKS = 5;

  public SessionContext project(Session session) {
    if (session == null) {
      return SessionContext.EMPTY;
    }
    String pending =
        session.getPendingQuestion() == null
            ? null
            : session.getPendingQuestion().getDecision().name();
    String action =
        session.getLastOutcome() == null
                || session.getLastOutcome().turn() == null
                || session.getLastOutcome().turn().getIntent() == null
            ? null
            : session.getLastOutcome().turn().getIntent().name();
    List<SessionContext.DisplayedBook> displayed =
        session.getActiveSearch() == null
                || session.getActiveSearch().getSearch() == null
                || session.getActiveSearch().getSearch().getRecommendations() == null
            ? List.of()
            : session.getActiveSearch().getSearch().getRecommendations().stream()
                .filter(book -> book.getTitle() != null)
                .limit(MAX_DISPLAYED_BOOKS)
                .map(book -> new SessionContext.DisplayedBook(book.getRank(), book.getTitle()))
                .toList();
    return new SessionContext(pending, action, displayed);
  }
}
