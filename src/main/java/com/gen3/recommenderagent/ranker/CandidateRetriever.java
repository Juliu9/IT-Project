package com.gen3.recommenderagent.ranker;

import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Service;

import java.util.List;

/*
    Gets candidates that can be used for machine learning.
 */
@Service
public class CandidateRetriever {

    private final SolrAudiobookRepository audiobookRepository;

    public CandidateRetriever(SolrAudiobookRepository audiobookRepository) {
        this.audiobookRepository = audiobookRepository;
    }

    /**
     * Retrieves audiobook candidates from Solr using the given query.
     */
    public List<SolrDocument> getCandidates(String query, int limit) {

        try {
            return audiobookRepository
                    .search(query, limit)
                    .getResults();

        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve candidates from Solr", e);
        }
    }
}