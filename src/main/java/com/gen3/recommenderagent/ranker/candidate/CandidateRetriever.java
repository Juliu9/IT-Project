package com.gen3.recommenderagent.ranker.candidate;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.SemanticQueryVectors;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import java.util.List;

/*
   Gets candidates that can be used for machine learning.
*/
public interface CandidateRetriever {

  /** Retrieves candidates using the parsed request and its already-embedded preference signals. */
  List<AudiobookCandidate> getCandidates(
      SessionRequest request, SemanticQueryVectors vectors, int limit);
}
