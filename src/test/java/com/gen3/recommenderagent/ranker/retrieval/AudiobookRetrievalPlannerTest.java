package com.gen3.recommenderagent.ranker.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies conversion of a mixed user request into searchable text and exact filters. */
class AudiobookRetrievalPlannerTest {

  /** Keeps fantasy and desert as lexical terms while making narrator and duration exact filters. */
  @Test
  void plansMixedHybridRequest() {
    Query query = new Query();
    query.setGenres(List.of("fantasy"));
    query.setTopics(List.of("deserts"));
    query.setNarrators(List.of("Stephen Fry"));
    Constraints constraints = new Constraints();
    constraints.setLanguage("English");
    constraints.setDuration("under 10 hours");
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.NEW_RECOMMENDATION);
    request.setRawText("More fantasy books with deserts narrated by Stephen Fry");
    request.setQuery(query);
    request.setConstraints(constraints);

    AudiobookRetrievalPlan plan =
        new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()).plan("fallback", 50, request);

    assertThat(plan.mode()).isEqualTo(RetrievalMode.HYBRID);
    assertThat(plan.semanticText()).contains("fantasy", "deserts", "Stephen Fry");
    assertThat(plan.keywordText()).isEqualTo("deserts fantasy");
    assertThat(plan.filters().narrators()).containsExactly("Stephen Fry");
    assertThat(plan.filters().language()).isEqualTo("English");
    assertThat(plan.filters().maximumDurationMinutes()).isEqualTo(600);
  }
}
