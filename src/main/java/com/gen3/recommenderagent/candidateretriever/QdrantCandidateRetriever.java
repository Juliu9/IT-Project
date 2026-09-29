package com.gen3.recommenderagent.candidateretriever;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Retrieves database-independent recommendation candidates from Qdrant. */
@Service
@ConditionalOnProperty(
    name = "audiobook.candidate-retriever",
    havingValue = "qdrant",
    matchIfMissing = true)
public class QdrantCandidateRetriever implements CandidateRetriever {

  private final AudiobookCandidateSearch candidateSearch;
  private final CandidateRetrievalExecutor retrievalExecutor;

  /** Uses the Qdrant repository and the shared normalized request-embedding pipeline. */
  public QdrantCandidateRetriever(
      @Qualifier("qdrantAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      CandidateRetrievalExecutor retrievalExecutor) {
    this.candidateSearch = candidateSearch;
    this.retrievalExecutor = retrievalExecutor;
  }

  /** Embeds the combined raw and processed user request once, then searches Qdrant. */
  @Override
  public List<AudiobookCandidate> getCandidates(
      SessionRequest request, SemanticQueryVectors vectors, int limit) {
    try {
      return retrievalExecutor.execute(candidateSearch, request, vectors, limit);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to retrieve candidates from Qdrant", exception);
    }
  }
}
