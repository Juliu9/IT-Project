package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import java.util.List;

/*
   Gets candidates that can be used for machine learning.
*/
public interface CandidateRetriever {

  /** Retrieves audiobook candidates from the configured search database using the given query. */
  List<AudiobookCandidate> getCandidates(String query, int limit);

  /** Retrieves candidates using request-aware semantic retrieval when supported. */
  default List<AudiobookCandidate> getCandidates(
      String query, int limit, SessionRequest request) {
    return getCandidates(query, limit);
  }
}
