package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.domain.session.Recommendation;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RelevanceRankingStrategy implements RankingStrategy {

    private static final int MAX_RESULTS = 5;

    /**
     * Baseline ranking used until the ML ranking model is introduced.
     *
     * Solr already returns documents in relevance order, so this implementation
     * preserves that order, removes duplicate IDs, and selects at most five books.
     * The method is deliberately isolated so an ML implementation can replace it
     * without changing RecommendationEngine.
     */
    @Override
    public List<Recommendation> rank(List<SolrDocument> candidates, int requestedLimit) {

        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        int limit = Math.min(
                Math.max(requestedLimit, 1),
                MAX_RESULTS
        );

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
            Double score = scoreValue instanceof Number number
                    ? number.doubleValue()
                    : null;

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
