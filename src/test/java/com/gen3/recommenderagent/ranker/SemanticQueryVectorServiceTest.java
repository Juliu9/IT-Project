package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import java.util.List;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectorService;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import org.junit.jupiter.api.Test;

class SemanticQueryVectorServiceTest {

  @Test
  void embedsPositiveAndNegativeQueriesExactlyOnce() {
    EmbeddingIndexer indexer = mock(EmbeddingIndexer.class);
    SemanticQuery positive = query("space opera");
    SemanticQuery negative = query("gore");
    SessionRequest request = new SessionRequest();
    request.setPositiveSemanticQuery(positive);
    request.setNegativeSemanticQuery(negative);
    when(indexer.embedSemanticQuery(positive)).thenReturn(new float[] {1, 0});
    when(indexer.embedSemanticQuery(negative)).thenReturn(new float[] {0, 1});

    SemanticQueryVectors vectors = new SemanticQueryVectorService(indexer).create(request);

    assertArrayEquals(new float[] {1, 0}, vectors.positive().getFirst());
    assertArrayEquals(new float[] {0, 1}, vectors.negative().getFirst());
    verify(indexer).embedSemanticQuery(positive);
    verify(indexer).embedSemanticQuery(negative);
  }

  @Test
  void returnsEmptyVectorsForANullRequest() {
    SemanticQueryVectors vectors =
        new SemanticQueryVectorService(mock(EmbeddingIndexer.class)).create(null);

    assertTrue(vectors.isEmpty());
  }

  private SemanticQuery query(String keyword) {
    SemanticQuery query = new SemanticQuery();
    query.setKeywords(List.of(keyword));
    return query;
  }
}
