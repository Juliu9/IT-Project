package com.gen3.recommenderagent.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DurationBoundsTest {
  @ParameterizedTest
  @CsvSource(
      value = {
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
    MustInclude include = new MustInclude();
    include.setDuration(text);
    request.setMustInclude(include);
    MustNotInclude exclude = new MustNotInclude();
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
    MustInclude include = new MustInclude();
    include.setDuration(text);
    request.setMustInclude(include);
    assertThatThrownBy(() -> new AudiobookRetrievalPlanner().plan(50, request, Set.of()))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("duration");
  }
}
