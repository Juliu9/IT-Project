package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Recommendations;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
    Does machine learning on some candidates, and return a ranking of the top _ results
 */
@Service
public class RankingService {

    private static final int MAX_RESULTS = 5;

    /**
     * Baseline ranking used until the ML ranking model is introduced.
     *
     * Solr already returns documents in relevance order, so this implementation
     * preserves that order, removes duplicate IDs, and selects at most five books.
     * The method is deliberately isolated so an ML implementation can replace it
     * without changing RecommendationEngine.
     */
    public Recommendations rank(List<SolrDocument> candidates, int requestedLimit) {
        Recommendations result = new Recommendations();

        if (candidates == null || candidates.isEmpty()) {
            return result;
        }

        int limit = Math.min(
                Math.max(requestedLimit, 1),
                MAX_RESULTS
        );

        List<Recommendation> ranked = new ArrayList<>();
        List<String> shownBooks = new ArrayList<>();
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

            int rank = ranked.size() + 1;
            ranked.add(new Recommendation(bookId, rank, score));
            shownBooks.add(bookId);

            if (ranked.size() == limit) {
                break;
            }
        }

        result.setRecommendations(ranked);
        result.setShownBooks(shownBooks);
        return result;
    }
}
