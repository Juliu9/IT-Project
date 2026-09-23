package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.apache.solr.common.SolrDocument;

/*
   Gets candidates that can be used for machine learning.
*/
public interface CandidateRetriever {

  /** Retrieves audiobook candidates from the configured search database using the given query. */
  List<SolrDocument> getCandidates(String query, int limit);

  /** Retrieves candidates using request-aware semantic retrieval when supported. */
  default List<SolrDocument> getCandidates(String query, int limit, SessionRequest request) {
    return getCandidates(query, limit);
  }
}
