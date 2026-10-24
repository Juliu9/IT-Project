package com.gen3.recommenderagent.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExactFilterCorrectionTest {
  private final AudiobookRetrievalPlanner planner = new AudiobookRetrievalPlanner();

  private SessionRequest request(String field, String required, String forbidden) {
    var request = new SessionRequest();
    request.setIntent(Intent.REFINE);
    var include = new MustInclude();
    var exclude = new MustNotInclude();
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
    var before = planner.plan(1, resolved, Set.of()).filters();
    planner.resolveFilterCorrections(resolved, request(field, include, exclude));
    assertThat(planner.plan(1, resolved, Set.of()).filters()).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"duration,under 5 hours,under 6 hours", "year,2024,between 2020 and 2025"})
  void newRangeRequirementOverridesAnInheritedExclusion(
      String field, String include, String exclude) {
    var resolved = request(field, include, exclude);
    planner.resolveFilterCorrections(resolved, request(field, include, null));
    assertThat(planner.plan(1, resolved, Set.of()).filters().mustNotInclude().hasConditions())
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({"duration,under 5 hours,under 6 hours", "year,2024,between 2020 and 2025"})
  void newRangeExclusionOverridesAnInheritedRequirement(
      String field, String include, String exclude) {
    var resolved = request(field, include, exclude);
    planner.resolveFilterCorrections(resolved, request(field, null, exclude));
    assertThat(planner.plan(1, resolved, Set.of()).filters().mustInclude().hasConditions())
        .isFalse();
  }
}
