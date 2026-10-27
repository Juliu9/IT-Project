package com.gen3.recommenderagent.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.conversation.ClarificationResolver;
import com.gen3.recommenderagent.domain.conversation.SearchContextResolver;
import com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DurationBoundsTest {
  @ParameterizedTest
  @CsvSource({"4,false", "5,true"})
  void shorterPreservesTheMinimumOrConfirmsExtendingTheRange(int minimumHours, boolean confirm) {
    var previous = new ConversationDraft();
    previous.setIntent(Intent.RECOMMENDATION);
    var criteria = new ExactCriteria();
    criteria.setDuration("between " + minimumHours + " and 6 hours");
    previous.setMustInclude(criteria);
    var book = new Recommendation("id", 1, 0.9, "Title");
    book.setDurationMinutes(300);
    previous.setRecommendations(List.of(book));
    var session = new Session();
    session.getRequests().add(ConversationTurn.fromDraft(previous));
    var incoming = new ConversationDraft();
    incoming.setIntent(Intent.REFINE);
    incoming.setShorter(true);
    incoming.setRawText("make them shorter");
    var result =
        new SearchContextResolver().resolve(session, SearchCriteriaMerger.copyOf(incoming));
    var policy = new ExactFilterPolicy();
    policy.validateTransition(result, incoming, session);
    assertThat(result.isSearchClarificationRequired()).isEqualTo(confirm);
    if (confirm) {
      assertThat(result.getDecision()).isEqualTo(ConversationDecision.DURATION_RANGE_CONFIRMATION);
      var clarification = new ClarificationResolver();
      clarification.complete(session, result);
      var answer = new ConversationDraft();
      answer.setIntent(Intent.UNKNOWN);
      answer.setRawText("yes");
      result = clarification.resolve(session, answer);
      policy.validateTransition(result, answer, session);
      assertThat(result.isSearchClarificationRequired()).isFalse();
      assertThat(policy.filters(result, Set.of()).mustInclude().minimumDurationMinutes()).isNull();
    } else {
      assertThat(policy.filters(result, Set.of()).mustInclude().minimumDurationMinutes())
          .isEqualTo(240);
    }
    assertThat(policy.filters(result, Set.of()).mustInclude().maximumDurationMinutes())
        .isEqualTo(299);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "under 10 hours|null|599",
        "under 1.5 hours|null|89",
        "at most 1.5 hours|null|90",
        "over 90 minutes|91|null",
        "at least 90 minutes|90|null",
        "between 2 and 3 hours|120|180",
        "2-3 hours|120|180",
        "between 1h 30m and 2h|90|120",
        "1h 30m|90|90",
        "1 hour and 30 minutes|90|90",
        "exactly 90 minutes|90|90",
        "under 1.51 hours|null|90",
        "over 1.51 hours|91|null",
        "at least 1.51 hours|91|null",
        "no more than 2 hrs|null|120",
        "no less than 2 hrs|120|null"
      },
      delimiter = '|',
      nullValues = "null")
  void preservesDurationMeaningForRequiredAndExcludedRanges(
      String text, Integer minimum, Integer maximum) {
    ConversationDraft request = new ConversationDraft();
    ExactCriteria include = new ExactCriteria();
    include.setDuration(text);
    request.setMustInclude(include);
    ExactCriteria exclude = new ExactCriteria();
    exclude.setDuration(text);
    request.setMustNotInclude(exclude);
    var filters = new AudiobookRetrievalPlanner().plan(50, request, Set.of()).filters();
    assertThat(filters.mustInclude().minimumDurationMinutes()).isEqualTo(minimum);
    assertThat(filters.mustInclude().maximumDurationMinutes()).isEqualTo(maximum);
    assertThat(filters.mustNotInclude().minimumDurationMinutes()).isEqualTo(minimum);
    assertThat(filters.mustNotInclude().maximumDurationMinutes()).isEqualTo(maximum);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a short book",
        "90",
        "about 2 hours",
        "between 3 and 2 hours",
        "-2 hours",
        "0 hours",
        "999999999999999 hours",
        "1e9 hours",
        "2 hours trailing text",
        "under 0.5 minutes",
        "2 hours or 3 hours",
        "between 2 hours and 3"
      })
  void rejectsInvalidOrAmbiguousDurationWithoutDroppingTheFilter(String text) {
    ConversationDraft request = new ConversationDraft();
    ExactCriteria include = new ExactCriteria();
    include.setDuration(text);
    request.setMustInclude(include);
    assertThatThrownBy(() -> new AudiobookRetrievalPlanner().plan(50, request, Set.of()))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("duration");
  }
}
