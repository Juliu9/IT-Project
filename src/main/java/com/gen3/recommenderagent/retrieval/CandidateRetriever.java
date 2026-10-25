package com.gen3.recommenderagent.retrieval;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import java.util.List;
import java.util.Set;

/*
   Gets candidates that can be used for machine learning.
*/
public interface CandidateRetriever {

  /** Retrieves candidates using the parsed request and its already-embedded preference signals. */
  List<AudiobookCandidate> getCandidates(
      SessionRequest request, SemanticQueryVectors vectors, int limit, Set<String> excludedBookIds);
}
