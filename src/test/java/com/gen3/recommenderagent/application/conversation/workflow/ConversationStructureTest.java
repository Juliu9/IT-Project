package com.gen3.recommenderagent.application.conversation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConversationStructureTest {
  @Test
  void rejectsDuplicateAndMissingHandlerRegistrations() {
    var all = replacementHandler();
    assertThatThrownBy(() -> new ConversationDispatcher(List.of(all, new SessionActionHandler())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate conversation handler");
    assertThatThrownBy(() -> new ConversationDispatcher(List.of(new SessionActionHandler())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing conversation handlers")
        .hasMessageContaining("RECOMMENDATION");
  }

  @Test
  void dispatchesAnIndependentHandlerWithoutChangingTheCoordinator() {
    var state = new ConversationDispatcher(List.of(replacementHandler()));
    var session = new Session();
    var input = new SessionRequest();
    input.setIntent(ConversationAction.HELP);
    input.setRawText("help");
    var result = state.begin(session, input);
    assertThat(state.needsRecommendations(result)).isFalse();
    state.finish(session, result, input);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.HELP);
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(session.getLastOutcome().decision()).isEqualTo(ConversationDecision.HELP);
    assertThat(input.getBookCount()).isNull();
    assertThat(result.getRawText()).isEqualTo("help");
    assertThat(session.getLastOutcome().turn().getRawText()).isNull();
    assertThat(session.getLastOutcome().turn().getDecision()).isNull();
  }

  @Test
  void completedTurnsOwnTheirSearchSnapshotAndInputHasNoOutputProperties() {
    var empty = ConversationDraft.toTurn(new ConversationDraft());
    assertThat(empty.getMustInclude()).isNull();
    assertThat(empty.getMustNotInclude()).isNull();
    var draft = new ConversationDraft();
    draft.setIntent(ConversationAction.RECOMMENDATION);
    var include = new ExactCriteria();
    include.setLanguage("English");
    draft.setMustInclude(include);
    var turn = ConversationDraft.toTurn(draft);
    include.setLanguage("French");
    assertThat(turn.getMustInclude().getLanguage()).isEqualTo("English");
    ConversationDraft.fromTurn(turn).getMustInclude().setLanguage("Spanish");
    assertThat(turn.getMustInclude().getLanguage()).isEqualTo("English");
    var input =
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new SessionRequest()));
    assertThat(input.has("recommendations")).isFalse();
    assertThat(input.has("decision")).isFalse();
    assertThat(input.has("searchCriteria")).isFalse();
    assertThat(input.has("userPreferenceSnapshot")).isFalse();
  }

  private SessionRequest request(ConversationAction intent) {
    var request = new SessionRequest();
    request.setIntent(intent);
    return request;
  }

  private ConversationHandler replacementHandler() {
    return new ConversationHandler() {
      public Set<ConversationAction> intents() {
        return EnumSet.allOf(ConversationAction.class);
      }

      public ConversationDraft resolve(Session session, ConversationDraft input) {
        input.setDecision(ConversationDecision.HELP);
        return input;
      }

      public void finish(Session session, ConversationDraft result, SessionRequest original) {
        session.setBookCount(3);
      }
    };
  }
}
