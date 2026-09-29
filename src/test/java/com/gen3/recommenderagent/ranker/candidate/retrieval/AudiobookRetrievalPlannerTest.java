package com.gen3.recommenderagent.ranker.candidate.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Filter;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies conversion of a mixed user request into searchable text and exact filters. */
class AudiobookRetrievalPlannerTest {

  /** Keeps fantasy and desert as lexical terms while making narrator and duration exact filters. */
  @Test
  void plansMixedHybridRequest() {
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("fantasy"));
    semanticQuery.setTopics(List.of("deserts"));
    semanticQuery.setNarrators(List.of("Stephen Fry"));
    Filter filter = new Filter();
    filter.setLanguage("English");
    filter.setDuration("under 10 hours");
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.NEW_RECOMMENDATION);
    request.setRawText("More fantasy books with deserts narrated by Stephen Fry");
    request.setQuery(semanticQuery);
    request.setFilter(filter);

    AudiobookRetrievalPlan plan =
        new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()).plan(50, request);

    assertThat(plan.mode()).isEqualTo(RetrievalMode.HYBRID);
    assertThat(plan.keywordText()).isEqualTo("deserts fantasy");
    assertThat(plan.filters().narrators()).containsExactly("Stephen Fry");
    assertThat(plan.filters().language()).isEqualTo("English");
    assertThat(plan.filters().maximumDurationMinutes()).isEqualTo(600);
  }
}
