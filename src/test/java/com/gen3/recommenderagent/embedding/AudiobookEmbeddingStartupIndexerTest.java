package com.gen3.recommenderagent.embedding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCatalogueRepository;
import com.gen3.recommenderagent.storage.audiobook.AudiobookVectorIndexer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class AudiobookEmbeddingStartupIndexerTest {

    @Test
    void indexesEveryCataloguePageBeforeCompleting() throws Exception {
        AudiobookCatalogueRepository catalogue = mock(AudiobookCatalogueRepository.class);
        AudiobookVectorIndexer vectors = mock(AudiobookVectorIndexer.class);
        EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
        AudiobookRecord first = book("book-1");
        AudiobookRecord second = book("book-2");
        AudiobookRecord third = book("book-3");

        when(catalogue.findAllBooks(0, 2)).thenReturn(List.of(first, second));
        when(catalogue.findAllBooks(2, 2)).thenReturn(List.of(third));
        when(embeddingIndexer.ensureAudiobookEmbedding(any(AudiobookRecord.class)))
                .thenReturn(new float[] { 1.0f, 0.0f });

        AudiobookEmbeddingStartupIndexer indexer = new AudiobookEmbeddingStartupIndexer(catalogue, vectors, embeddingIndexer, 2,
                true);

        indexer.run(new DefaultApplicationArguments());

        verify(catalogue).findAllBooks(0, 2);
        verify(catalogue).findAllBooks(2, 2);
        verify(vectors, times(2)).indexEmbeddings(any());
    }

    @Test
    void canBeDisabledWithoutReadingCatalogue() throws Exception {
        AudiobookCatalogueRepository catalogue = mock(AudiobookCatalogueRepository.class);
        AudiobookVectorIndexer vectors = mock(AudiobookVectorIndexer.class);
        EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);

        AudiobookEmbeddingStartupIndexer indexer = new AudiobookEmbeddingStartupIndexer(catalogue, vectors, embeddingIndexer,
                100, false);

        indexer.run(new DefaultApplicationArguments());

        org.mockito.Mockito.verifyNoInteractions(catalogue, vectors, embeddingIndexer);
    }

    private AudiobookRecord book(String id) {
        return new AudiobookRecord(id, "source", "Title " + id, List.of("Author"), "Description");
    }
}
