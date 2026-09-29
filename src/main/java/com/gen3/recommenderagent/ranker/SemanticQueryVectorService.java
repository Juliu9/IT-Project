package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
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
    return new SemanticQueryVectors(
        vectorOf(request.getPositiveSemanticQuery()), vectorOf(request.getNegativeSemanticQuery()));
  }

  private List<float[]> vectorOf(SemanticQuery query) {
    float[] vector = embeddingIndexer.embedSemanticQuery(query);
    return vector == null || vector.length == 0 ? List.of() : List.of(vector);
  }
}
