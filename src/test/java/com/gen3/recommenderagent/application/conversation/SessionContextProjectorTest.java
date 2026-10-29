package com.gen3.recommenderagent.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.SessionContext;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SessionContextProjectorTest {
  private final SessionContextProjector projector = new SessionContextProjector();

  @Test
  void absentPageDoesNotFallBackToTheLastExplanation() {
    assertThat(projector.project(null)).isEqualTo(SessionContext.EMPTY);
    assertThat(projector.project(new Session())).isEqualTo(SessionContext.EMPTY);
    var session = new Session();
    var explanation = new ConversationTurn();
    explanation.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
    explanation.setRecommendations(List.of(new Recommendation("old", 2, 1.0, "Old book")));
    session.setLastOutcome(new LastOutcome(explanation, ConversationDecision.EXPLANATION));
    assertThat(projector.project(session).displayedBooks()).isEmpty();
    assertThat(projector.project(session).lastAction()).isEqualTo("EXPLAIN_RECOMMENDATION");
  }

  @Test
  void titlesStayBoundedAndKeepRanksWhenATitleIsMissing() {
    var session = new Session();
    var page = new ConversationTurn();
    page.setRecommendations(
        IntStream.rangeClosed(6, 12)
            .mapToObj(
                rank ->
                    new Recommendation(
                        "book-" + rank, rank, 1.0, rank == 7 ? null : "Book " + rank))
            .toList());
    session.getActiveSearch().setSearch(page);
    session.setPendingDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    var context = projector.project(session);
    assertThat(context.pendingQuestion()).isEqualTo("EXPLANATION_TARGET_CLARIFICATION");
    assertThat(context.displayedBooks())
        .extracting(SessionContext.DisplayedBook::rank)
        .containsExactly(6, 8, 9, 10, 11);
    assertThat(context.displayedBooks())
        .extracting(SessionContext.DisplayedBook::title)
        .containsExactly("Book 6", "Book 8", "Book 9", "Book 10", "Book 11");
  }
}
