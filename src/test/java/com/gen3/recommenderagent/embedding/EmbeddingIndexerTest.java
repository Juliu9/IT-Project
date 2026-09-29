package com.gen3.recommenderagent.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingIndexerTest {

  @Test
  void embedsAndNormalizesAudiobookText() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    EmbeddingIndexer indexer = new EmbeddingIndexer(model);
    AudiobookRecord book =
        new AudiobookRecord(
            "book-1",
            "catalogue",
            "Dune",
            List.of("Frank Herbert"),
            "Desert science fiction",
            List.of("Simon Vance"),
            "English",
            1200);
    String text = indexer.buildAudiobookText(book);
    when(model.embed(text)).thenReturn(new float[] {3, 4});

    assertArrayEquals(new float[] {0.6f, 0.8f}, indexer.embedAudiobook(book), 0.0001f);
    assertThat(text).contains("title: Dune", "authors: Frank Herbert", "narrators: Simon Vance");
  }

  @Test
  void embedsAValidBatchAndPreservesRecordOrder() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    EmbeddingIndexer indexer = new EmbeddingIndexer(model);
    AudiobookRecord first = book("1", "First");
    AudiobookRecord second = book("2", "Second");
    List<String> texts =
        List.of(indexer.buildAudiobookText(first), indexer.buildAudiobookText(second));
    when(model.embed(texts)).thenReturn(List.of(new float[] {1, 0}, new float[] {0, 2}));

    var embeddings = indexer.embedAudiobooks(List.of(first, second));

    assertThat(embeddings).extracting(value -> value.record().id()).containsExactly("1", "2");
    assertArrayEquals(new float[] {0, 1}, embeddings.get(1).vector(), 0.0001f);
  }

  @Test
  void embedsOneCompleteSemanticQuery() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    EmbeddingIndexer indexer = new EmbeddingIndexer(model);
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of("desert planets"));
    query.setGenres(List.of("science fiction"));
    query.setKeywords(List.of("survival"));
    String text = indexer.buildSemanticQueryText(query);
    when(model.embed(text)).thenReturn(new float[] {0, 5});

    assertArrayEquals(new float[] {0, 1}, indexer.embedSemanticQuery(query), 0.0001f);
    verify(model).embed(text);
  }

  @Test
  void skipsTheModelForAnEmptySemanticQuery() {
    EmbeddingModel model = mock(EmbeddingModel.class);

    assertThat(new EmbeddingIndexer(model).embedSemanticQuery(null)).isEmpty();
    verifyNoInteractions(model);
  }

  @Test
  void combinesPositiveAndNegativeDirectionsForRetrieval() {
    EmbeddingIndexer indexer = new EmbeddingIndexer(mock(EmbeddingModel.class));
    SemanticQueryVectors vectors =
        new SemanticQueryVectors(List.of(new float[] {1, 0}), List.of(new float[] {0, 1}));

    float inverseRootTwo = (float) (1 / Math.sqrt(2));
    assertArrayEquals(
        new float[] {inverseRootTwo, -inverseRootTwo},
        indexer.combineSemanticQueries(vectors),
        0.0001f);
  }

  private AudiobookRecord book(String id, String title) {
    return new AudiobookRecord(id, "catalogue", title, List.of("Author"), "Description");
  }
}
