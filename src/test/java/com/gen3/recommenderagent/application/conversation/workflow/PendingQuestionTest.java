package com.gen3.recommenderagent.application.conversation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class PendingQuestionTest {
  private final ClarificationResolver resolver = new ClarificationResolver();
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void preferenceUpdateAbandonsSimilaritySelectionPermanently() {
    var session = selection();
    var dispatcher = ConversationTestSupport.dispatcher();
    var update = input(ConversationAction.UPDATE_PREFERENCES, "update my preferences");
    var question = dispatcher.begin(session, update);
    dispatcher.finish(session, question, update);
    assertThat(session.getPendingQuestion()).isInstanceOf(PendingQuestion.Preference.class);
    assertThat(session.getPendingQuestion()).isInstanceOf(PendingQuestion.Preference.class);
    var taste = input(ConversationAction.RECOMMENDATION, "horror");
    var semantic = new SemanticQuery();
    semantic.setGenres(List.of("horror"));
    taste.setPositiveSemanticQuery(semantic);
    var answer = dispatcher.begin(session, taste);
    dispatcher.finish(session, answer, taste);
    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("horror");
    session = reload(session);
    var second = resolver.resolve(session, input(ConversationAction.UNKNOWN, "the second one"));
    assertThat(second.getIntent()).isEqualTo(ConversationAction.UNKNOWN);
    assertThat(session.getPendingQuestion()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationAction.class,
      names = {"HELP", "EXPLAIN_PREFERENCES", "REPEAT", "UNKNOWN"})
  void supportingActionsPreserveSelectionsAcrossReload(ConversationAction intent) {
    var session = selection();
    var action =
        input(intent, intent == ConversationAction.UNKNOWN ? "gobbledygook" : intent.name());
    var result = resolver.resolve(session, action);
    resolver.complete(session, result);
    assertThat(session.getPendingQuestion()).isInstanceOf(PendingQuestion.SimilarBook.class);
    session = reload(session);
    var selected = resolver.resolve(session, input(ConversationAction.UNKNOWN, "the second one"));
    assertThat(selected.getSearchContext().getSimilarBook().bookId()).isEqualTo("2");
    if (intent == ConversationAction.UNKNOWN) {
      var response =
          new ConversationResponseGenerator(null)
              .generate(List.of(), ConversationDraft.toTurn(result));
      assertThat(response)
          .startsWith("I didn't quite catch that. Just to clarify,")
          .contains("Harry Potter", "Which book did you mean?");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationAction.class,
      names = {
        "CHANGE_COUNT",
        "RESET_PREFERENCES",
        "MORE_RESULTS",
        "REPEAT_RECOMMENDATIONS",
        "EXPLAIN_RECOMMENDATION",
        "CLEAR_HISTORY",
        "CANCEL"
      })
  void explicitActionsDiscardSelections(ConversationAction intent) {
    var session = selection();
    var action = input(intent, intent.name());
    if (intent == ConversationAction.CHANGE_COUNT) {
      action.setBookCount(3);
    }
    var result = resolver.resolve(session, action);
    if (intent == ConversationAction.CANCEL) {
      resolver.complete(session, result);
    }
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(
            resolver.resolve(reload(session), input(ConversationAction.UNKNOWN, "2")).getIntent())
        .isEqualTo(ConversationAction.UNKNOWN);
  }

  @Test
  void oldHistoryCannotResurrectAnAbandonedQuestion() {
    var session = new Session();
    var old = new ConversationDraft();
    old.setPreferenceClarificationRequired(true);
    session.getActiveSearch().setSearch(ConversationDraft.toTurn(old));
    resolver.resolve(session, input(ConversationAction.UNKNOWN, "huh"));
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(session.isAwaitingPersonalisedPreferences()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationQuestion.class,
      names = {
        "PREFERENCE_CLARIFICATION",
        "PREFERENCE_UPDATE_CLARIFICATION",
        "EXPLANATION_TARGET_CLARIFICATION",
        "SEARCH_CLARIFICATION",
        "SEARCH_CHANGE_CLARIFICATION",
        "SEARCH_CHANGE_OFFER",
        "SHORTER_DURATION_CLARIFICATION",
        "LONGER_DURATION_CLARIFICATION",
        "DURATION_RANGE_CONFIRMATION",
        "RECENCY_YEAR_CLARIFICATION",
        "RECENCY_RANGE_CONFIRMATION",
        "SIMILAR_BOOK_SELECTION",
        "SIMILAR_BOOK_REQUIRED",
        "SIMILAR_BOOK_NOT_FOUND",
        "SIMILAR_BOOK_UNAVAILABLE"
      })
  void unknownReasksEveryQuestionTypeAcrossReload(ConversationQuestion decision) {
    var session = new Session();
    session.setPendingQuestionKind(decision);
    if (session.getPendingQuestion() instanceof PendingQuestion.SimilarBook book) {
      book.setRequest(ConversationDraft.toTurn(new ConversationDraft()));
    }
    if (session.getPendingQuestion() instanceof PendingQuestion.Search search) {
      search.setRequest(ConversationDraft.toTurn(new ConversationDraft()));
    }
    if (session.getPendingQuestion() instanceof PendingQuestion.Preference preference) {
      preference.setRequest(ConversationDraft.toTurn(new ConversationDraft()));
    }
    session = reload(session);
    var unknown = resolver.resolve(session, input(ConversationAction.UNKNOWN, "gobbledygook"));
    assertThat(unknown.getIntent()).isEqualTo(ConversationAction.UNKNOWN);
    resolver.complete(session, unknown);
    assertThat(session.getPendingQuestionKind()).isEqualTo(decision);
    var response =
        new ConversationResponseGenerator(null)
            .generate(List.of(), ConversationDraft.toTurn(unknown));
    assertThat(response).startsWith("I didn't quite catch that. Just to clarify,");
  }

  private Session selection() {
    var session = new Session();
    var search = new ConversationDraft();
    search.setIntent(ConversationAction.RECOMMENDATION);
    search.setSimilarBookChoices(
        List.of(
            new AudiobookTitle("1", "Harry Potter one", List.of("J. K. Rowling")),
            new AudiobookTitle("2", "Harry Potter two", List.of("J. K. Rowling"))));
    var pending = new PendingQuestion.SimilarBook();
    pending.setKind(ConversationQuestion.SIMILAR_BOOK_SELECTION);
    pending.setRequest(ConversationDraft.toTurn(search));
    session.setPendingQuestion(pending);
    return session;
  }

  private SessionRequest input(ConversationAction intent, String text) {
    var input = new SessionRequest();
    input.setIntent(intent);
    input.setRawText(text);
    return input;
  }

  private Session reload(Session session) {
    return json.readValue(json.writeValueAsString(session), Session.class);
  }
}
