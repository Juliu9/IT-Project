package com.gen3.recommenderagent.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
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
    input.setIntent(Intent.HELP);
    input.setRawText("help");
    var result = state.begin(session, input);
    assertThat(state.needsRecommendations(result)).isFalse();
    state.finish(session, result, input);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.HELP);
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(session.getLastDecision()).isEqualTo(ConversationDecision.HELP);
    assertThat(input.getBookCount()).isNull();
    assertThat(result.getRawText()).isEqualTo("help");
    assertThat(session.getLastRequest().getRawText()).isNull();
    assertThat(session.getLastRequest().getDecision()).isNull();
  }

  @Test
  void readsLegacyFlatSessionSnapshotsAndKeepsTheirSearchAndReplayContext() {
    var mapper = new ObjectMapper();
    var session =
        mapper.readValue(
            """
        {"requests":[{"intent":"RECOMMENDATION","bookCount":2,
          "positiveSemanticQuery":{"genres":["fantasy"]},
          "searchCriteria":{"positiveSemanticQuery":{"genres":["fantasy"]}},
          "userPreferencesLoaded":true,
          "preferenceUpdates":{"mustInclude":{"language":"English"}},
          "explicitSearch":true,
          "recommendations":[{"bookId":"42","rank":1,"title":"A book"}]}],
         "lastRequest":{"intent":"HELP","recommendations":[]},"lastDecision":"HELP"}
        """,
            Session.class);
    var search = ConversationHistory.latestSearch(session);
    assertThat(search.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(search.getSearchCriteria().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    assertThat(search.isUserPreferencesLoaded()).isTrue();
    assertThat(search.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("42");
    var repeated = ConversationTestSupport.dispatcher().resolve(session, request(Intent.REPEAT));
    assertThat(repeated.getDecision()).isEqualTo(ConversationDecision.HELP);
    var json = mapper.readTree(mapper.writeValueAsString(session));
    var saved = json.get("requests").get(0);
    assertThat(saved.has("positiveSemanticQuery")).isTrue();
    assertThat(saved.has("searchContext")).isFalse();
    assertThat(saved.has("preferenceUpdates")).isFalse();
    assertThat(saved.has("explicitSearch")).isFalse();
  }

  @Test
  void legacyPendingChoicesAreReadButOnlyStoredInTheRequestSnapshot() {
    var mapper = new ObjectMapper();
    var session =
        mapper.readValue(
            """
        {"pendingQuestion":"SIMILAR_BOOK_SELECTION","pendingSimilarBook":{
          "request":{"intent":"RECOMMENDATION","similarBookChoices":[{"bookId":"hunger","title":"The Hunger Games","authors":["Suzanne Collins"]}]},
          "decision":"SIMILAR_BOOK_SELECTION",
          "choices":[{"bookId":"hunger","title":"The Hunger Games","authors":["Suzanne Collins"]}]}}
        """,
            Session.class);
    assertThat(session.getPendingSimilarBook().request().getSimilarBookChoices()).hasSize(1);
    var pending = mapper.readTree(mapper.writeValueAsString(session)).get("pendingQuestion");
    assertThat(pending.has("choices")).isFalse();
    assertThat(pending.get("request").get("similarBookChoices").size()).isEqualTo(1);
  }

  @Test
  void completedTurnsOwnTheirSearchSnapshotAndInputHasNoOutputProperties() {
    var empty = ConversationTurn.fromDraft(new ConversationDraft());
    assertThat(empty.getMustInclude()).isNull();
    assertThat(empty.getMustNotInclude()).isNull();
    var draft = new ConversationDraft();
    draft.setIntent(Intent.RECOMMENDATION);
    var include = new ExactCriteria();
    include.setLanguage("English");
    draft.setMustInclude(include);
    var turn = ConversationTurn.fromDraft(draft);
    include.setLanguage("French");
    assertThat(turn.getMustInclude().getLanguage()).isEqualTo("English");
    turn.toDraft().getMustInclude().setLanguage("Spanish");
    assertThat(turn.getMustInclude().getLanguage()).isEqualTo("English");
    var input =
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new SessionRequest()));
    assertThat(input.has("recommendations")).isFalse();
    assertThat(input.has("decision")).isFalse();
    assertThat(input.has("searchCriteria")).isFalse();
    assertThat(input.has("userPreferenceSnapshot")).isFalse();
  }

  private SessionRequest request(Intent intent) {
    var request = new SessionRequest();
    request.setIntent(intent);
    return request;
  }

  private ConversationHandler replacementHandler() {
    return new ConversationHandler() {
      public Set<Intent> intents() {
        return EnumSet.allOf(Intent.class);
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
