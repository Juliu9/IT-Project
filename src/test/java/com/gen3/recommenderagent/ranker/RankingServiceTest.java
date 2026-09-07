package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Recommendations;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RankingServiceTest {

    private final RankingService rankingService = new RankingService();

    @Test
    void shouldPreserveSolrOrderRemoveDuplicatesAndLimitResults() {
        SolrDocument first = document("book-1", 3.5);
        SolrDocument duplicate = document("book-1", 3.0);
        SolrDocument missingId = new SolrDocument();
        SolrDocument second = document("book-2", null);
        SolrDocument third = document("book-3", 1.5);

        Recommendations result = rankingService.rank(
                List.of(first, duplicate, missingId, second, third),
                2
        );

        assertEquals(2, result.getRecommendations().size());
        assertEquals("book-1", result.getRecommendations().get(0).getBookId());
        assertEquals(1, result.getRecommendations().get(0).getRank());
        assertEquals(3.5, result.getRecommendations().get(0).getScore());
        assertEquals("book-2", result.getRecommendations().get(1).getBookId());
        assertEquals(2, result.getRecommendations().get(1).getRank());
        assertNull(result.getRecommendations().get(1).getScore());
        assertEquals(List.of("book-1", "book-2"), result.getShownBooks());
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
