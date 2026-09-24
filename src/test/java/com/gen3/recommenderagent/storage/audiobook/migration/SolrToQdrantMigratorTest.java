package com.gen3.recommenderagent.storage.audiobook.migration;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that migration pages through Solr and writes normalized vectors in batches. */
class SolrToQdrantMigratorTest {

  /** Confirms a full page is followed by a final partial page before migration finishes. */
  @Test
  void migratesEverySolrPage() throws Exception {
    SolrAudiobookRepository solr = mock(SolrAudiobookRepository.class);
    QdrantAudiobookRepository qdrant = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddings = mock(EmbeddingIndexer.class);
    AudiobookRecord first = new AudiobookRecord("1", "src", "One", List.of(), "First");
    AudiobookRecord second = new AudiobookRecord("2", "src", "Two", List.of(), "Second");
    AudiobookRecord third = new AudiobookRecord("3", "src", "Three", List.of(), "Third");
    when(solr.findAllBooks(0, 2)).thenReturn(List.of(first, second));
    when(solr.findAllBooks(2, 2)).thenReturn(List.of(third));
    when(embeddings.ensureAudiobookEmbeddings(List.of(first, second)))
        .thenReturn(
            List.of(
                new AudiobookEmbedding(first, new float[] {1.0f, 0.0f}),
                new AudiobookEmbedding(second, new float[] {0.0f, 1.0f})));
    when(embeddings.ensureAudiobookEmbeddings(List.of(third)))
        .thenReturn(List.of(new AudiobookEmbedding(third, new float[] {0.7f, 0.7f})));

    new SolrToQdrantMigrator(solr, qdrant, embeddings, 2, 0).run(null);

    verify(qdrant).initialize();
    verify(qdrant, times(2)).indexEmbeddings(anyList());
    verify(solr).findAllBooks(0, 2);
    verify(solr).findAllBooks(2, 2);
  }
}
