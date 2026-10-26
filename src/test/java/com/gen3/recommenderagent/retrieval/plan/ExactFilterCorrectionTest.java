package com.gen3.recommenderagent.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExactFilterCorrectionTest {
  private final ExactFilterPolicy planner = new ExactFilterPolicy();

  private ConversationDraft request(String field, String required, String forbidden) {
    var request = new ConversationDraft();
    request.setIntent(Intent.REFINE);
    var include = new ExactCriteria();
    var exclude = new ExactCriteria();
    switch (field) {
      case "authors" -> {
        include.setAuthors(required == null ? null : List.of(required));
        exclude.setAuthors(forbidden == null ? null : List.of(forbidden));
      }
      case "narrators" -> {
        include.setNarrators(required == null ? null : List.of(required));
        exclude.setNarrators(forbidden == null ? null : List.of(forbidden));
      }
      case "language" -> {
        include.setLanguage(required);
        exclude.setLanguage(forbidden);
      }
      case "source" -> {
        include.setSource(required);
        exclude.setSource(forbidden);
      }
      case "duration" -> {
        include.setDuration(required);
        exclude.setDuration(forbidden);
      }
      case "year" -> {
        include.setPublicationYear(required);
        exclude.setPublicationYear(forbidden);
      }
      default -> throw new IllegalArgumentException(field);
    }
    request.setMustInclude(include);
    request.setMustNotInclude(exclude);
    return request;
  }

  @ParameterizedTest
  @CsvSource({
    "authors,A,A",
    "authors,Suzanne Collins,'Collins, Suzanne'",
    "narrators,'Fry, Stephen',Stephen Fry",
    "narrators,N,N",
    "language,English,english",
    "source,rnib,rnib",
    "duration,under 5 hours,under 6 hours",
    "duration,over 10 hours,at least 10 hours",
    "year,2024,between 2020 and 2025",
    "year,after 2020,after 2010"
  })
  void sameMessageConflictsRequireClarification(String field, String include, String exclude) {
    assertThatThrownBy(
            () ->
                planner.resolveFilterCorrections(
                    request(field, include, exclude), request(field, include, exclude)))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("Which requirement");
  }

  @ParameterizedTest
  @CsvSource({"duration,between 2 and 5 hours,under 3 hours", "year,between 2020 and 2025,2024"})
  void partialRangeExclusionsStillAllowMatches(String field, String include, String exclude) {
    var resolved = request(field, include, exclude);
    var before = planner.filters(resolved, Set.of());
    planner.resolveFilterCorrections(resolved, request(field, include, exclude));
    assertThat(planner.filters(resolved, Set.of())).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({
    "duration,under 5 hours,under 6 hours",
    "year,2024,between 2020 and 2025",
    "authors,Suzanne Collins,'Collins, Suzanne'",
    "narrators,'Fry, Stephen',Stephen Fry"
  })
  void newRangeRequirementOverridesAnInheritedExclusion(
      String field, String include, String exclude) {
    var resolved = request(field, include, exclude);
    planner.resolveFilterCorrections(resolved, request(field, include, null));
    assertThat(planner.filters(resolved, Set.of()).mustNotInclude().hasConditions()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "duration,under 5 hours,under 6 hours",
    "year,2024,between 2020 and 2025",
    "authors,Suzanne Collins,'Collins, Suzanne'",
    "narrators,'Fry, Stephen',Stephen Fry"
  })
  void newRangeExclusionOverridesAnInheritedRequirement(
      String field, String include, String exclude) {
    var resolved = request(field, include, exclude);
    planner.resolveFilterCorrections(resolved, request(field, null, exclude));
    assertThat(planner.filters(resolved, Set.of()).mustInclude().hasConditions()).isFalse();
  }
}
