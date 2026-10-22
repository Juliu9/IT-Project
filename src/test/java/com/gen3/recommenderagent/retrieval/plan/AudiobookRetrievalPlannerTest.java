package com.gen3.recommenderagent.retrieval.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies conversion of a mixed user request into searchable text and exact filters. */
class AudiobookRetrievalPlannerTest {

  /** Keeps fantasy and desert as lexical terms while making narrator and duration exact filters. */
  @Test
  void plansMixedHybridRequest() {
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("fantasy"));
    semanticQuery.setTopics(List.of("deserts"));
    MustInclude filter = new MustInclude();
    filter.setNarrators(List.of("Stephen Fry"));
    filter.setLanguage("English");
    filter.setDuration("under 10 hours");
    filter.setPublicationYear("after 2020");
    filter.setSource("NLS");
    MustNotInclude excluded = new MustNotInclude();
    excluded.setLanguage("French");
    excluded.setPublicationYear("before 2000");
    excluded.setSource("Legacy");
    SessionRequest request = new SessionRequest();
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

    SessionRequest semanticOnly = new SessionRequest();
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of("horror"));
    semanticOnly.setPositiveSemanticQuery(query);
    assertThat(planner.plan(10, semanticOnly, Set.of()).mode()).isEqualTo(RetrievalMode.SEMANTIC);

    SessionRequest filterOnly = new SessionRequest();
    MustInclude duration = new MustInclude();
    duration.setDuration("under 3 hours");
    filterOnly.setMustInclude(duration);
    assertThat(planner.plan(10, filterOnly, Set.of()).mode()).isEqualTo(RetrievalMode.FILTER_ONLY);

    SessionRequest empty = new SessionRequest();
    assertThat(planner.plan(10, empty, Set.of()).mode()).isEqualTo(RetrievalMode.NONE);

    SessionRequest negativeAuthorOnly = new SessionRequest();
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
      SessionRequest request = new SessionRequest();
      request.setPositiveSemanticQuery(query);
      assertThat(new AudiobookRetrievalPlanner().plan(50, request, Set.of()).mode())
          .isEqualTo(RetrievalMode.SEMANTIC);
    }
  }

  @Test
  void preservesYearRangesAndInclusiveComparisonsAndRejectsInvalidYears() {
    SessionRequest request = new SessionRequest();
    MustInclude filter = new MustInclude();
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
    for (String invalid :
        List.of("between 2010 and 2000", "recent", "999999999999999", "2000 or 2010")) {
      filter.setPublicationYear(invalid);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(50, request, Set.of()))
          .isInstanceOf(com.gen3.recommenderagent.inputparser.InvalidRequestException.class);
    }
  }

  @Test
  void treatsAnUnqualifiedPublicationYearAsAnExactYear() {
    SessionRequest request = new SessionRequest();
    MustInclude publicationYear = new MustInclude();
    publicationYear.setPublicationYear("2020");
    request.setMustInclude(publicationYear);

    AudiobookFilterConditions conditions =
        new AudiobookRetrievalPlanner().plan(10, request, Set.of()).filters().mustInclude();

    assertThat(conditions.minimumPublicationYear()).isEqualTo(2020);
    assertThat(conditions.maximumPublicationYear()).isEqualTo(2020);
  }
}
