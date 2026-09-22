package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.PreferenceRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.RelevanceRankingStrategy;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.Test;

class RankingServiceTest {

  private final RankingService rankingService =
      new RankingService(
          new RelevanceRankingStrategy(),
          new PreferenceRankingStrategy(),
          new HybridRankingStrategy());

  @Test
  void shouldPreserveSolrOrderRemoveDuplicatesAndLimitResults() {
    SolrDocument first = document("book-1", 3.5);
    SolrDocument duplicate = document("book-1", 3.0);
    SolrDocument missingId = new SolrDocument();
    SolrDocument second = document("book-2", null);
    SolrDocument third = document("book-3", 1.5);

    List<Recommendation> result =
        rankingService.rank(List.of(first, duplicate, missingId, second, third), 2);

    assertEquals(2, result.size());
    assertEquals("book-1", result.get(0).getBookId());
    assertEquals(1, result.get(0).getRank());
    assertEquals(3.5, result.get(0).getScore());
    assertEquals("book-2", result.get(1).getBookId());
    assertEquals(2, result.get(1).getRank());
    assertNull(result.get(1).getScore());
  }

  private SolrDocument document(String id, Double score) {
    SolrDocument document = new SolrDocument();
    document.setField("id", id);
    if (score != null) {
      document.setField("score", score);
    }
    return document;
  }
}
