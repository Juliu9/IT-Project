package com.gen3.recommenderagent.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies conversion of a mixed user request into searchable text and exact filters. */
class AudiobookRetrievalPlannerTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "around 2000,1995,2005", "the period 2000,1995,2005", "period 2000,1995,2005",
    "from the period 2000,1995,2005", "around 0000,0,5", "around 9999,9994,9999",
    "2000,2000,2000", "from 2000,2000,"
  })
  void approximateYearsUseInclusiveFiveYearBoundsWithoutChangingExactYears(
      String text, int minimum, Integer maximum) {
    var request = new ConversationDraft();
    var exact = new ExactCriteria();
    exact.setPublicationYear(text);
    request.setMustInclude(exact);
    request.setMustNotInclude(exact);
    var filters = new AudiobookRetrievalPlanner().plan(50, request, Set.of()).filters();
    for (var conditions : List.of(filters.mustInclude(), filters.mustNotInclude())) {
      assertThat(conditions.minimumPublicationYear()).isEqualTo(minimum);
      assertThat(conditions.maximumPublicationYear()).isEqualTo(maximum);
    }
  }

  /** Keeps fantasy and desert as lexical terms while making narrator and duration exact filters. */
  @Test
  void plansMixedHybridRequest() {
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("fantasy"));
    semanticQuery.setTopics(List.of("deserts"));
    ExactCriteria filter = new ExactCriteria();
    filter.setNarrators(List.of("Stephen Fry"));
    filter.setLanguage("English");
    filter.setDuration("under 10 hours");
    filter.setPublicationYear("after 2020");
    filter.setSource("NLS");
    ExactCriteria excluded = new ExactCriteria();
    excluded.setLanguage("French");
    excluded.setPublicationYear("before 2000");
    excluded.setSource("Legacy");
    ConversationDraft request = new ConversationDraft();
    request.setRawText("More fantasy books with deserts narrated by Stephen Fry");
    request.setPositiveSemanticQuery(semanticQuery);
    request.setMustInclude(filter);
    request.setMustNotInclude(excluded);

    AudiobookRetrievalPlan plan =
        new AudiobookRetrievalPlanner().plan(50, request, Set.of("already-shown"));

    assertThat(plan.mode()).isEqualTo(RetrievalMode.HYBRID);
    assertThat(plan.keywordText()).isEqualTo("deserts fantasy");
    assertThat(plan.filters().mustInclude().narrators()).containsExactly("Stephen Fry");
    assertThat(plan.filters().mustInclude().language()).isEqualTo("English");
    assertThat(plan.filters().mustInclude().source()).isEqualTo("NLS");
    assertThat(plan.filters().mustInclude().maximumDurationMinutes()).isEqualTo(599);
    assertThat(plan.filters().mustInclude().minimumPublicationYear()).isEqualTo(2021);
    assertThat(plan.filters().mustNotInclude().language()).isEqualTo("French");
    assertThat(plan.filters().mustNotInclude().maximumPublicationYear()).isEqualTo(1999);
    assertThat(plan.filters().mustNotInclude().source()).isEqualTo("Legacy");
    assertThat(plan.filters().excludedBookIds()).containsExactly("already-shown");
  }

  @Test
  void choosesModeFromRequestContentRatherThanIntent() {
    AudiobookRetrievalPlanner planner = new AudiobookRetrievalPlanner();

    ConversationDraft semanticOnly = new ConversationDraft();
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of("horror"));
    semanticOnly.setPositiveSemanticQuery(query);
    assertThat(planner.plan(10, semanticOnly, Set.of()).mode()).isEqualTo(RetrievalMode.SEMANTIC);

    ConversationDraft filterOnly = new ConversationDraft();
    ExactCriteria duration = new ExactCriteria();
    duration.setDuration("under 3 hours");
    filterOnly.setMustInclude(duration);
    assertThat(planner.plan(10, filterOnly, Set.of()).mode()).isEqualTo(RetrievalMode.FILTER_ONLY);

    ConversationDraft empty = new ConversationDraft();
    assertThat(planner.plan(10, empty, Set.of()).mode()).isEqualTo(RetrievalMode.NONE);

    ConversationDraft negativeAuthorOnly = new ConversationDraft();
    SemanticQuery negativeAuthor = new SemanticQuery();
    negativeAuthor.setAuthors(List.of("Unwanted Author"));
    negativeAuthorOnly.setNegativeSemanticQuery(negativeAuthor);
    assertThat(planner.plan(10, negativeAuthorOnly, Set.of()).mode())
        .isEqualTo(RetrievalMode.SEMANTIC);
  }

  @Test
  void authorAndNarratorPreferencesSelectSemanticRetrieval() {
    for (boolean author : List.of(true, false)) {
      SemanticQuery query = new SemanticQuery();
      if (author) {
        query.setAuthors(List.of("Agatha Christie"));
      } else {
        query.setNarrators(List.of("Stephen Fry"));
      }
      ConversationDraft request = new ConversationDraft();
      request.setPositiveSemanticQuery(query);
      assertThat(new AudiobookRetrievalPlanner().plan(50, request, Set.of()).mode())
          .isEqualTo(RetrievalMode.SEMANTIC);
    }
  }

  @Test
  void preservesYearRangesAndInclusiveComparisonsAndRejectsInvalidYears() {
    ConversationDraft request = new ConversationDraft();
    ExactCriteria filter = new ExactCriteria();
    request.setMustInclude(filter);
    var planner = new AudiobookRetrievalPlanner();
    filter.setPublicationYear("between 2000 and 2010");
    var range = planner.plan(50, request, Set.of()).filters().mustInclude();
    assertThat(range.minimumPublicationYear()).isEqualTo(2000);
    assertThat(range.maximumPublicationYear()).isEqualTo(2010);
    filter.setPublicationYear("at least 2020");
    assertThat(planner.plan(50, request, Set.of()).filters().mustInclude().minimumPublicationYear())
        .isEqualTo(2020);
    filter.setPublicationYear("at most 2020");
    assertThat(planner.plan(50, request, Set.of()).filters().mustInclude().maximumPublicationYear())
        .isEqualTo(2020);
    for (String invalid : List.of("between 2010 and 2000", "999999999999999", "2000 or 2010")) {
      filter.setPublicationYear(invalid);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(50, request, Set.of()))
          .isInstanceOf(com.gen3.recommenderagent.inputparser.InvalidRequestException.class);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "recent,2021",
    "recently,2021",
    "in the past year,2025",
    "past year,2025",
    "in the last 4 years,2022",
    "within the past four years,2022",
    "last five years,2021",
    "the past one year,2025",
    "last 10 years,2016"
  })
  void resolvesRelativeYearsFromTheClockForBothFilterGroups(String text, int startYear) {
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-10-24T00:00:00Z"), java.time.ZoneOffset.UTC);
    var planner = new AudiobookRetrievalPlanner(clock);
    var request = new ConversationDraft();
    var include = new ExactCriteria();
    include.setPublicationYear(text);
    request.setMustInclude(include);
    var exclude = new ExactCriteria();
    exclude.setPublicationYear(text);
    request.setMustNotInclude(exclude);
    var filters = planner.plan(50, request, Set.of()).filters();
    for (var conditions : List.of(filters.mustInclude(), filters.mustNotInclude())) {
      assertThat(conditions.minimumPublicationYear()).isEqualTo(startYear);
      assertThat(conditions.maximumPublicationYear()).isEqualTo(2026);
    }
  }

  @Test
  void relativeYearsAdvanceWithTheClockIncludingAtNewYear() {
    var request = new ConversationDraft();
    var include = new ExactCriteria();
    include.setPublicationYear("recent");
    request.setMustInclude(include);
    for (int year : List.of(2026, 2027, 2031)) {
      var clock =
          java.time.Clock.fixed(
              java.time.Instant.parse(year + "-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
      var bounds =
          new AudiobookRetrievalPlanner(clock).plan(50, request, Set.of()).filters().mustInclude();
      assertThat(bounds.minimumPublicationYear()).isEqualTo(year - 5);
      assertThat(bounds.maximumPublicationYear()).isEqualTo(year);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "last 0 years",
        "last -4 years",
        "last 2.5 years",
        "last 9999999999999999999999 years",
        "last 3000 years"
      })
  void rejectsInvalidRelativeYearWindowsWithoutOverflow(String text) {
    var request = new ConversationDraft();
    var include = new ExactCriteria();
    include.setPublicationYear(text);
    request.setMustInclude(include);
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-10-24T00:00:00Z"), java.time.ZoneOffset.UTC);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new AudiobookRetrievalPlanner(clock).plan(50, request, Set.of()))
        .isInstanceOf(com.gen3.recommenderagent.inputparser.InvalidRequestException.class);
  }

  @Test
  void treatsAnUnqualifiedPublicationYearAsAnExactYear() {
    ConversationDraft request = new ConversationDraft();
    ExactCriteria publicationYear = new ExactCriteria();
    publicationYear.setPublicationYear("2020");
    request.setMustInclude(publicationYear);

    AudiobookFilterConditions conditions =
        new AudiobookRetrievalPlanner().plan(10, request, Set.of()).filters().mustInclude();

    assertThat(conditions.minimumPublicationYear()).isEqualTo(2020);
    assertThat(conditions.maximumPublicationYear()).isEqualTo(2020);
  }
}
