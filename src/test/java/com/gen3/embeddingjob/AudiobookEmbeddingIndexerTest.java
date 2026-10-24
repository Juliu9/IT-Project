package com.gen3.embeddingjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class AudiobookEmbeddingIndexerTest {
  @Test
  void embedsAndNormalizesAudiobookText() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    AudiobookEmbeddingIndexer indexer = new AudiobookEmbeddingIndexer(model);
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
    when(model.embed(List.of(text))).thenReturn(List.of(new float[] {3, 4}));

    assertArrayEquals(
        new float[] {0.6f, 0.8f},
        indexer.embedAudiobooks(List.of(book)).getFirst().vector(),
        0.0001f);
    assertThat(text).contains("title: Dune", "authors: Frank Herbert", "narrators: Simon Vance");
  }

  @Test
  void embedsAValidBatchAndPreservesRecordOrder() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    AudiobookEmbeddingIndexer indexer = new AudiobookEmbeddingIndexer(model);
    AudiobookRecord first = book("1", "First");
    AudiobookRecord second = book("2", "Second");
    List<String> texts =
        List.of(indexer.buildAudiobookText(first), indexer.buildAudiobookText(second));
    when(model.embed(texts)).thenReturn(List.of(new float[] {1, 0}, new float[] {0, 2}));

    var embeddings = indexer.embedAudiobooks(List.of(first, second));

    assertThat(embeddings).extracting(value -> value.record().id()).containsExactly("1", "2");
    assertArrayEquals(new float[] {0, 1}, embeddings.get(1).vector(), 0.0001f);
  }

  private AudiobookRecord book(String id, String title) {
    return new AudiobookRecord(id, "catalogue", title, List.of("Author"), "Description");
  }
}
