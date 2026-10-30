package com.gen3.recommenderagent.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecommendationTest {

  @Test
  void readsRecommendationStoredInSessionJson() throws Exception {
    Recommendation recommendation =
        new ObjectMapper()
            .readValue(
                """
                {"id":"book-1","rank":1,"score":0.9,"title":"Book One","shortDescription":"A concise summary."}
                """,
                Recommendation.class);

    assertThat(recommendation.getId()).isEqualTo("book-1");
    assertThat(recommendation.getRank()).isEqualTo(1);
    assertThat(recommendation.getScore()).isEqualTo(0.9);
    assertThat(recommendation.getTitle()).isEqualTo("Book One");
    assertThat(recommendation.getShortDescription()).isEqualTo("A concise summary.");
  }
}
