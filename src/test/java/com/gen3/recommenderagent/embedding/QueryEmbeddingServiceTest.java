package com.gen3.recommenderagent.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.search.SemanticQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

class QueryEmbeddingServiceTest {

  @Test
  void embedsOneCompleteSemanticQuery() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    QueryEmbeddingService indexer = new QueryEmbeddingService(model);
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of("desert planets"));
    query.setGenres(List.of("science fiction"));
    query.setKeywords(List.of("survival"));
    String text = indexer.buildSemanticQueryText(query);
    EmbeddingResponse response = mock(EmbeddingResponse.class);
    when(response.getResults()).thenReturn(List.of(new Embedding(new float[] {0, 5}, 0)));
    when(model.embedForResponse(List.of(text))).thenReturn(response);

    assertArrayEquals(new float[] {0, 1}, indexer.embedSemanticQuery(query), 0.0001f);
    verify(model).embedForResponse(List.of(text));
  }

  @Test
  void embedsPositiveAndNegativeSemanticQueriesInOneBatch() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    QueryEmbeddingService indexer = new QueryEmbeddingService(model);
    SemanticQuery positive = new SemanticQuery();
    positive.setTopics(List.of("space"));
    SemanticQuery negative = new SemanticQuery();
    negative.setKeywords(List.of("gore"));
    List<String> texts =
        List.of(indexer.buildSemanticQueryText(positive), indexer.buildSemanticQueryText(negative));
    EmbeddingResponse response = mock(EmbeddingResponse.class);
    when(response.getResults())
        .thenReturn(
            List.of(new Embedding(new float[] {3, 4}, 0), new Embedding(new float[] {0, 2}, 1)));
    when(model.embedForResponse(texts)).thenReturn(response);

    List<float[]> vectors = indexer.embedSemanticQueries(List.of(positive, negative));

    assertArrayEquals(new float[] {0.6f, 0.8f}, vectors.get(0), 0.0001f);
    assertArrayEquals(new float[] {0, 1}, vectors.get(1), 0.0001f);
    verify(model).embedForResponse(texts);
  }

  @Test
  void skipsTheModelForAnEmptySemanticQuery() {
    EmbeddingModel model = mock(EmbeddingModel.class);

    assertThat(new QueryEmbeddingService(model).embedSemanticQuery(null)).isEmpty();
    verifyNoInteractions(model);
  }

  @Test
  void combinesPositiveAndNegativeDirectionsForRetrieval() {
    QueryEmbeddingService indexer = new QueryEmbeddingService(mock(EmbeddingModel.class));
    SemanticQueryVectors vectors =
        new SemanticQueryVectors(List.of(new float[] {1, 0}), List.of(new float[] {0, 1}));

    float inverseRootTwo = (float) (1 / Math.sqrt(2));
    assertArrayEquals(
        new float[] {inverseRootTwo, -inverseRootTwo},
        indexer.combineSemanticQueries(vectors),
        0.0001f);
  }
}
