package com.gen3.recommenderagent.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.application.conversation.intent.HelpHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConversationStructureTest {
  @Test
  void rejectsDuplicateRegistrationsAndAllowsIndependentHandlerSets() {
    var all = replacementHandler();
    assertThatThrownBy(
            () ->
                com.gen3.recommenderagent.testsupport.TestHandlers.engine(
                    List.of(all, new HelpHandler())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate conversation handler");
    assertThat(new HandlerRegistry(List.of(new HelpHandler())).actions())
        .containsExactly(HelpHandler.ACTION);
  }

  @Test
  void dispatchesAnIndependentHandlerWithoutChangingTheCoordinator() {
    var state =
        com.gen3.recommenderagent.testsupport.TestHandlers.engine(List.of(replacementHandler()));
    var session = new Session();
    var input = new SessionRequest();
    input.setIntent(HelpHandler.ACTION);
    input.setRawText("help");
    var result = state.begin(session, input);
    assertThat(state.needsRecommendations(result)).isFalse();
    state.finish(session, result, input);
    assertThat(result.getOutcome()).isNull();
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(session.getLastOutcome().outcome()).isNull();
    assertThat(input.getBookCount()).isNull();
    assertThat(result.getRawText()).isEqualTo("help");
    assertThat(session.getLastOutcome().turn().getRawText()).isNull();
    assertThat(session.getLastOutcome().turn().getOutcome()).isNull();
  }

  @Test
  void completedTurnsOwnTheirSearchSnapshotAndInputHasNoOutputProperties() {
    var empty = ConversationDraft.toTurn(new ConversationDraft());
    assertThat(empty.getMustInclude()).isNull();
    assertThat(empty.getMustNotInclude()).isNull();
    var draft = new ConversationDraft();
    draft.setIntent(RecommendationHandler.ACTION);
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

  private ConversationHandler replacementHandler() {
    return new ConversationHandler() {
      public Set<ConversationAction> intents() {
        return new java.util.LinkedHashSet<>(
            List.of(
                com.gen3.recommenderagent.testsupport.TestHandlers.registry()
                    .actions()
                    .toArray(ConversationAction[]::new)));
      }

      public ConversationDraft resolve(Session session, ConversationDraft input) {
        return input;
      }

      public void finish(Session session, ConversationDraft result, SessionRequest original) {
        session.setBookCount(3);
      }
    };
  }
}
