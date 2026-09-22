package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.domain.session.Recommendation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Component;

/**
 * Stub for hybrid (relevance + preference blend) ranking (MAPA-49 scaffolding).
 *
 * <p>Falls back to the relevance baseline until preference weighting exists to blend against.
 */
@Component
public class HybridRankingStrategy implements RankingStrategy {

  private static final int MAX_RESULTS = 5;

  @Override
  public List<Recommendation> rank(List<SolrDocument> candidates, int requestedLimit) {

    if (candidates == null || candidates.isEmpty()) {
      return new ArrayList<>();
    }

    // TODO: blend relevance score with preference weighting once
    // PreferenceRankingStrategy has real logic to blend against.
    // Falling back to relevance-order baseline until then.

    int limit = Math.min(Math.max(requestedLimit, 1), MAX_RESULTS);

    List<Recommendation> ranked = new ArrayList<>();
    Set<String> seenBookIds = new HashSet<>();

    for (SolrDocument candidate : candidates) {
      Object idValue = candidate.getFieldValue("id");

      if (idValue == null) {
        continue;
      }

      String bookId = idValue.toString();
      if (bookId.isBlank() || !seenBookIds.add(bookId)) {
        continue;
      }

      Object scoreValue = candidate.getFieldValue("score");
      Double score = scoreValue instanceof Number number ? number.doubleValue() : null;

      Object titleValue = candidate.getFieldValue("title");
      String title = titleValue != null ? titleValue.toString() : null;

      int rank = ranked.size() + 1;
      ranked.add(new Recommendation(bookId, rank, score, title));
      if (ranked.size() == limit) {
        break;
      }
    }

    return ranked;
  }
}
