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
  @CsvSource(
      delimiter = '|',
      value = {
        "under 5 hours|false|240|299", "under 3 hours|true|null|179",
        "between 2 and 5 hours|true|120|300", "at least 5 hours|false|300|360",
        "at least 7 hours|true|420|null"
      },
      nullValues = "null")
  void durationAnswersPreserveUnchangedBoundsAndConfirmExtensions(
      String duration, boolean confirmation, Integer minimum, Integer maximum) {
    var session = new Session();
    session.setPendingDecision(ConversationDecision.SHORTER_DURATION_CLARIFICATION);
    var pending = new ConversationDraft();
    pending.setIntent(Intent.REFINE);
    var old = new ExactCriteria();
    old.setDuration("between 4 and 6 hours");
    pending.setMustInclude(old);
    session.setPendingRefinementRequest(ConversationTurn.fromDraft(pending));
    var answer = new ConversationDraft();
    answer.setIntent(Intent.REFINE);
    var updated = new ExactCriteria();
    updated.setDuration(duration);
    answer.setMustInclude(updated);
    var policy = new ExactFilterPolicy();
    policy.validateTransition(answer, answer, session);
    assertThat(answer.isSearchClarificationRequired()).isEqualTo(confirmation);
    if (confirmation) {
      assertThat(answer.getDecision()).isEqualTo(ConversationDecision.DURATION_RANGE_CONFIRMATION);
      var resolver = new ClarificationResolver();
      resolver.complete(session, answer);
      var yes = new ConversationDraft();
      yes.setIntent(Intent.UNKNOWN);
      yes.setRawText("yes");
      answer = resolver.resolve(session, yes);
      policy.validateTransition(answer, yes, session);
      assertThat(answer.isSearchClarificationRequired()).isFalse();
    }
    var bounds = policy.filters(answer, Set.of()).mustInclude();
    assertThat(bounds.minimumDurationMinutes()).isEqualTo(minimum);
    assertThat(bounds.maximumDurationMinutes()).isEqualTo(maximum);
  }

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
        ".5 hours|30|30",
        "two and a half hours|150|150",
        "an hour and a half|90|90",
        "two hours and a half|150|150",
        "three quarters of an hour|45|45",
        "one quarter of an hour|15|15",
        "one hundred minutes|100|100",
        "one hundred and twenty five minutes|125|125",
        "one hour and thirty minutes|90|90",
        "between twenty five and thirty hours|1500|1800",
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
