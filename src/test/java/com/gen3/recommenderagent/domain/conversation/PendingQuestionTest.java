package com.gen3.recommenderagent.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.PendingSimilarBook;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
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
    var update = input(Intent.UPDATE_PREFERENCES, "update my preferences");
    var question = dispatcher.begin(session, update);
    dispatcher.finish(session, question, update);
    assertThat(session.getPendingQuestion()).isInstanceOf(PendingQuestion.Preference.class);
    assertThat(session.getPendingSimilarBook()).isNull();
    var taste = input(Intent.RECOMMENDATION, "horror");
    var semantic = new SemanticQuery();
    semantic.setGenres(List.of("horror"));
    taste.setPositiveSemanticQuery(semantic);
    var answer = dispatcher.begin(session, taste);
    dispatcher.finish(session, answer, taste);
    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("horror");
    session = reload(session);
    var second = resolver.resolve(session, input(Intent.UNKNOWN, "the second one"));
    assertThat(second.getIntent()).isEqualTo(Intent.UNKNOWN);
    assertThat(session.getPendingQuestion()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = Intent.class,
      names = {"HELP", "REPEAT", "UNKNOWN"})
  void supportingActionsPreserveSelectionsAcrossReload(Intent intent) {
    var session = selection();
    var action = input(intent, intent == Intent.UNKNOWN ? "gobbledygook" : intent.name());
    var result = resolver.resolve(session, action);
    resolver.complete(session, result);
    assertThat(session.getPendingQuestion()).isInstanceOf(PendingQuestion.SimilarBook.class);
    session = reload(session);
    var selected = resolver.resolve(session, input(Intent.UNKNOWN, "the second one"));
    assertThat(selected.getSearchContext().getSimilarBook().bookId()).isEqualTo("2");
    if (intent == Intent.UNKNOWN) {
      result.setDecision(ConversationDecision.UNKNOWN);
      var response =
          new ConversationResponseGenerator(null)
              .generate(List.of(), ConversationTurn.fromDraft(result));
      assertThat(response)
          .startsWith("I didn't quite catch that. Just to clarify,")
          .contains("Harry Potter", "Which book did you mean?");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Intent.class,
      names = {
        "CHANGE_COUNT",
        "RESET_PREFERENCES",
        "MORE_RESULTS",
        "REPEAT_RECOMMENDATIONS",
        "EXPLAIN_RECOMMENDATION",
        "CLEAR_HISTORY",
        "CANCEL"
      })
  void explicitActionsDiscardSelections(Intent intent) {
    var session = selection();
    var action = input(intent, intent.name());
    if (intent == Intent.CHANGE_COUNT) {
      action.setBookCount(3);
    }
    var result = resolver.resolve(session, action);
    if (intent == Intent.CANCEL) {
      result.setDecision(ConversationDecision.CANCELLED);
      resolver.complete(session, result);
    }
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(resolver.resolve(reload(session), input(Intent.UNKNOWN, "2")).getIntent())
        .isEqualTo(Intent.UNKNOWN);
  }

  @Test
  void legacyCacheKeepsOnlyTheActiveQuestionAndNeverRestoresSuspendedState() {
    var session =
        json.readValue(
            """
        {"pendingQuestion":"COUNT_CLARIFICATION",
         "suspendedQuestion":"SIMILAR_BOOK_SELECTION",
         "pendingCountRequest":{"intent":"CHANGE_COUNT"},
         "pendingSimilarBook":{"decision":"SIMILAR_BOOK_SELECTION","request":{"intent":"RECOMMENDATION"}},
         "awaitingPreferenceUpdate":true}
        """,
            Session.class);
    assertThat(session.getPendingQuestion()).isInstanceOf(PendingQuestion.Count.class);
    assertThat(session.getPendingSimilarBook()).isNull();
    var corrected = resolver.resolve(session, input(Intent.UNKNOWN, "3"));
    corrected.setDecision(ConversationDecision.COUNT_CHANGED);
    resolver.complete(session, corrected);
    assertThat(reload(session).getPendingQuestion()).isNull();
    var saved = json.readTree(json.writeValueAsString(session));
    assertThat(saved.has("suspendedQuestion")).isFalse();
    assertThat(saved.has("pendingCountRequest")).isFalse();
    assertThat(saved.has("awaitingPreferenceUpdate")).isFalse();
  }

  @Test
  void oldHistoryCannotResurrectAnAbandonedQuestion() {
    var session = new Session();
    session.setLastDecision(ConversationDecision.SHORTER_DURATION_CLARIFICATION);
    var old = new ConversationDraft();
    old.setPreferenceClarificationRequired(true);
    session.setRequests(List.of(ConversationTurn.fromDraft(old)));
    resolver.resolve(session, input(Intent.UNKNOWN, "huh"));
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(session.isAwaitingPersonalisedPreferences()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationDecision.class,
      names = {
        "COUNT_CLARIFICATION",
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
  void unknownReasksEveryQuestionTypeAcrossReload(ConversationDecision decision) {
    var session = new Session();
    session.setPendingDecision(decision);
    if (session.getPendingQuestion() instanceof PendingQuestion.SimilarBook book) {
      book.setRequest(ConversationTurn.fromDraft(new ConversationDraft()));
    }
    if (session.getPendingQuestion() instanceof PendingQuestion.Search search) {
      search.setRequest(ConversationTurn.fromDraft(new ConversationDraft()));
    }
    if (session.getPendingQuestion() instanceof PendingQuestion.Preference preference) {
      preference.setRequest(ConversationTurn.fromDraft(new ConversationDraft()));
    }
    session = reload(session);
    var unknown = resolver.resolve(session, input(Intent.UNKNOWN, "gobbledygook"));
    assertThat(unknown.getIntent()).isEqualTo(Intent.UNKNOWN);
    unknown.setDecision(ConversationDecision.UNKNOWN);
    resolver.complete(session, unknown);
    assertThat(session.getPendingDecision()).isEqualTo(decision);
    var response =
        new ConversationResponseGenerator(null)
            .generate(List.of(), ConversationTurn.fromDraft(unknown));
    assertThat(response).startsWith("I didn't quite catch that. Just to clarify,");
  }

  @Test
  void legacyPersonalisedQuestionMigratesItsOwnRequestAndDiscardsHistoricalFlags() {
    var session =
        json.readValue(
            """
        {"pendingQuestion":"PREFERENCE_CLARIFICATION",
         "requests":[{"intent":"RECOMMENDATION","personalised":true,
           "preferenceClarificationRequired":true,"mustInclude":{"authors":["J. K. Rowling"]}}]}
        """,
            Session.class);
    assertThat(session.getPendingPreferenceRequest().getMustInclude().getAuthors())
        .containsExactly("J. K. Rowling");
    assertThat(session.getRequests()).isEmpty();
    session.setPendingQuestion(null);
    assertThat(reload(session).isAwaitingPersonalisedPreferences()).isFalse();
  }

  private Session selection() {
    var session = new Session();
    var search = new ConversationDraft();
    search.setIntent(Intent.RECOMMENDATION);
    search.setSimilarBookChoices(
        List.of(
            new AudiobookTitle("1", "Harry Potter one", List.of("J. K. Rowling")),
            new AudiobookTitle("2", "Harry Potter two", List.of("J. K. Rowling"))));
    session.setPendingSimilarBook(
        new PendingSimilarBook(
            ConversationTurn.fromDraft(search), ConversationDecision.SIMILAR_BOOK_SELECTION));
    return session;
  }

  private SessionRequest input(Intent intent, String text) {
    var input = new SessionRequest();
    input.setIntent(intent);
    input.setRawText(text);
    return input;
  }

  private Session reload(Session session) {
    return json.readValue(json.writeValueAsString(session), Session.class);
  }
}
