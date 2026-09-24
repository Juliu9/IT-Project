package com.gen3.recommenderagent.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import tools.jackson.databind.ObjectMapper;

/** Verifies source composition and normalization at the persistence boundary. */
class EmbeddingIndexerTest {

  private final EmbeddingModel model = mock(EmbeddingModel.class);
  private final EmbeddingStore store = mock(EmbeddingStore.class);
  private final EmbeddingIndexer indexer = new EmbeddingIndexer(model, store, new ObjectMapper());

  /** Audiobook fields are combined and the saved vector has unit length. */
  @Test
  void indexesAudiobook() throws Exception {
    AudiobookRecord book =
        new AudiobookRecord(
            "book-1", "source", "A Space Journey", List.of("A. Writer"), "A trip through space");
    when(model.embed(any(String.class))).thenReturn(new float[] {3, 4});

    float[] indexed = indexer.ensureAudiobookEmbedding(book);

    ArgumentCaptor<StoredEmbedding> saved = ArgumentCaptor.forClass(StoredEmbedding.class);
    verify(store).save(saved.capture());
    assertEquals("audiobook:book-1", saved.getValue().getId());
    assertEquals(
        "title: A Space Journey\nauthors: A. Writer\ndescription: A trip through space",
        saved.getValue().getSourceText());
    float[] vector = new ObjectMapper().readValue(saved.getValue().getVectorJson(), float[].class);
    assertEquals(0.6, vector[0], 0.000001);
    assertEquals(0.8, vector[1], 0.000001);
    assertEquals(0.6, indexed[0], 0.000001);
    assertEquals(0.8, indexed[1], 0.000001);
  }

  /** Raw and parsed request text share one embedding and the final request ID. */
  @Test
  void indexesCombinedRequest() {
    SessionRequest request = new SessionRequest();
    request.setRequestId("request-1");
    request.setRawText("I want a space mystery");
    Query query = new Query();
    query.setGenres(List.of("science fiction", "mystery"));
    request.setQuery(query);
    when(model.embed(any(String.class))).thenReturn(new float[] {0, 2});

    indexer.indexRequest(request);

    ArgumentCaptor<StoredEmbedding> saved = ArgumentCaptor.forClass(StoredEmbedding.class);
    verify(store).save(saved.capture());
    assertEquals("request:request-1", saved.getValue().getId());
    assertEquals(
        "Request: I want a space mystery\nGenres: science fiction, mystery",
        saved.getValue().getSourceText());
    assertEquals("[0.0,1.0]", saved.getValue().getVectorJson());
  }

  /** Existing audiobook vectors are reused to avoid repeated embedding calls. */
  @Test
  void reusesExistingAudiobook() {
    AudiobookRecord book = new AudiobookRecord("book-1", "source", null, List.of(), null);
    when(store.findById("audiobook:book-1"))
        .thenReturn(
            java.util.Optional.of(
                new StoredEmbedding("audiobook:book-1", "stored text", "[0.0,1.0]")));

    float[] vector = indexer.ensureAudiobookEmbedding(book);

    verify(model, never()).embed(any(String.class));
    verify(store, never()).save(any(StoredEmbedding.class));
    assertEquals(0.0, vector[0], 0.000001);
    assertEquals(1.0, vector[1], 0.000001);
  }

  /** Migration pages generate all cache misses with one embedding-model batch call. */
  @Test
  void embedsMigrationPageInOneBatch() {
    AudiobookRecord first =
        new AudiobookRecord("book-1", "source", "First", List.of("Author"), "Description");
    AudiobookRecord second =
        new AudiobookRecord("book-2", "source", "Second", List.of("Author"), "Description");
    when(store.findAllById(List.of("audiobook:book-1", "audiobook:book-2")))
        .thenReturn(List.of());
    when(model.embed(anyList()))
        .thenReturn(List.of(new float[] {3, 4}, new float[] {0, 2}));

    var embeddings = indexer.ensureAudiobookEmbeddings(List.of(first, second));

    assertEquals(2, embeddings.size());
    assertEquals(0.6, embeddings.getFirst().vector()[0], 0.000001);
    assertEquals(1.0, embeddings.getLast().vector()[1], 0.000001);
    verify(model).embed(anyList());
    verify(store).saveAll(anyList());
  }

  /** A favourite book ID retrieves its stored normalized vector without calling the model. */
  @Test
  void findsAudiobookEmbeddingByBookId() {
    when(store.findById("audiobook:book-1"))
        .thenReturn(
            java.util.Optional.of(
                new StoredEmbedding("audiobook:book-1", "stored text", "[0.6,0.8]")));

    var result = indexer.findAudiobookEmbedding("book-1");

    assertTrue(result.isPresent());
    assertEquals(0.6, result.get()[0], 0.000001);
    assertEquals(0.8, result.get()[1], 0.000001);
    verify(model, never()).embed(any(String.class));
  }

  /** Zero vectors cannot be stored because their dot product has no cosine meaning. */
  @Test
  void rejectsZeroVector() {
    assertThrows(IllegalArgumentException.class, () -> VectorMath.normalize(new float[] {0, 0}));
    assertEquals(
        1.0, VectorMath.dotProduct(new float[] {0.6f, 0.8f}, new float[] {0.6f, 0.8f}), 0.000001);
  }
}
