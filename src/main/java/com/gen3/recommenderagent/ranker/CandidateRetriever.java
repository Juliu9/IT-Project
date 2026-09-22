package com.gen3.recommenderagent.ranker;

import java.util.List;
import org.apache.solr.common.SolrDocument;

/*
   Gets candidates that can be used for machine learning.
*/
public interface CandidateRetriever {

  /** Retrieves audiobook candidates from Solr using the given query. */
  List<SolrDocument> getCandidates(String query, int limit);
}
