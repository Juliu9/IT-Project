package com.gen3.recommenderagent.ranker;

import java.io.IOException;
import java.util.List;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Service;

/*
   Gets candidates that can be used for machine learning.
*/
@Service
public class CandidateRetriever {

  private final SolrAudiobookRepository audiobookRepository;

  public CandidateRetriever(SolrAudiobookRepository audiobookRepository) {
    this.audiobookRepository = audiobookRepository;
  }

  /** Retrieves audiobook candidates from Solr using the given query. */
  public List<SolrDocument> getCandidates(String query, int limit) {

    try {
      return audiobookRepository.search(query, limit).getResults();

    } catch (SolrServerException | IOException e) {
      throw new RuntimeException("Failed to retrieve candidates from Solr", e);
    }
  }
}
