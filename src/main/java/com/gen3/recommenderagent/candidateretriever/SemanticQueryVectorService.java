package com.gen3.recommenderagent.candidateretriever;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/** Encodes the request's positive and negative semantic queries exactly once. */
@Service
public class SemanticQueryVectorService {

  private final EmbeddingIndexer embeddingIndexer;

  public SemanticQueryVectorService(EmbeddingIndexer embeddingIndexer) {
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Keeps query polarity separate so retrieval can add positive and subtract negative evidence. */
  public SemanticQueryVectors create(SessionRequest request) {
    if (request == null) {
      return SemanticQueryVectors.empty();
    }
    List<float[]> embedded =
        embeddingIndexer.embedSemanticQueries(
            Arrays.asList(request.getPositiveSemanticQuery(), request.getNegativeSemanticQuery()));
    if (embedded.size() != 2) {
      throw new IllegalStateException("Embedding indexer returned an unexpected query batch size");
    }
    return new SemanticQueryVectors(vectorOf(embedded.get(0)), vectorOf(embedded.get(1)));
  }

  private List<float[]> vectorOf(float[] vector) {
    return vector == null || vector.length == 0 ? List.of() : List.of(vector);
  }
}
