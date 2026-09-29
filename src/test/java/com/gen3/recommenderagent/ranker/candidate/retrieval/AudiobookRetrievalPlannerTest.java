package com.gen3.recommenderagent.ranker.candidate.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlan;
import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.candidateretriever.retrievalplan.RetrievalMode;
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
    request.setRawText("More fantasy books with deserts narrated by Stephen Fry");
    request.setPositiveSemanticQuery(semanticQuery);
    request.setFilter(filter);

    AudiobookRetrievalPlan plan = new AudiobookRetrievalPlanner().plan(50, request);

    assertThat(plan.mode()).isEqualTo(RetrievalMode.HYBRID);
    assertThat(plan.keywordText()).isEqualTo("deserts fantasy");
    assertThat(plan.filters().narrators()).containsExactly("Stephen Fry");
    assertThat(plan.filters().language()).isEqualTo("English");
    assertThat(plan.filters().maximumDurationMinutes()).isEqualTo(600);
  }

  @Test
  void choosesModeFromRequestContentRatherThanIntent() {
    AudiobookRetrievalPlanner planner = new AudiobookRetrievalPlanner();

    SessionRequest semanticOnly = new SessionRequest();
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of("horror"));
    semanticOnly.setPositiveSemanticQuery(query);
    assertThat(planner.plan(10, semanticOnly).mode()).isEqualTo(RetrievalMode.SEMANTIC);

    SessionRequest filterOnly = new SessionRequest();
    Filter duration = new Filter();
    duration.setDuration("under 3 hours");
    filterOnly.setFilter(duration);
    assertThat(planner.plan(10, filterOnly).mode()).isEqualTo(RetrievalMode.FILTER_ONLY);

    SessionRequest empty = new SessionRequest();
    assertThat(planner.plan(10, empty).mode()).isEqualTo(RetrievalMode.NONE);

    SessionRequest negativeAuthorOnly = new SessionRequest();
    SemanticQuery negativeAuthor = new SemanticQuery();
    negativeAuthor.setAuthors(List.of("Unwanted Author"));
    negativeAuthorOnly.setNegativeSemanticQuery(negativeAuthor);
    assertThat(planner.plan(10, negativeAuthorOnly).mode()).isEqualTo(RetrievalMode.SEMANTIC);
  }
}
