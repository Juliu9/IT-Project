package com.gen3.recommenderagent.storage.audiobook;

import java.io.IOException;
import java.util.List;

/** Search port for the retrieval modes understood by candidate retrievers. */
public interface AudiobookCandidateSearch {

  /** Finds candidates by dense-vector meaning while applying structured filters. */
  List<AudiobookCandidate> searchSemantic(
      float[] queryVector, AudiobookFilters filters, int limit) throws IOException;

  /** Finds candidates by sparse lexical relevance while applying structured filters. */
  List<AudiobookCandidate> searchKeyword(
      String text, AudiobookFilters filters, int limit) throws IOException;

  /** Fuses dense and sparse rankings while applying structured filters. */
  List<AudiobookCandidate> searchHybrid(
      float[] queryVector, String text, AudiobookFilters filters, int limit) throws IOException;

  /** Finds candidates using only exact structured filters. */
  List<AudiobookCandidate> searchByFilters(AudiobookFilters filters, int limit)
      throws IOException;
}
