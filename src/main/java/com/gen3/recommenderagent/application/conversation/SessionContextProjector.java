package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.SessionContext;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces the bounded parser context from the current user-visible session outcome. */
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
    List<String> displayed =
        session.getLastOutcome() == null
                || session.getLastOutcome().turn() == null
                || session.getLastOutcome().turn().getRecommendations() == null
            ? List.of()
            : session.getLastOutcome().turn().getRecommendations().stream()
                .limit(MAX_DISPLAYED_BOOKS)
                .map(Recommendation::getTitle)
                .filter(java.util.Objects::nonNull)
                .toList();
    return new SessionContext(pending, action, displayed);
  }
}
