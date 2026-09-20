package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmbeddingIndexerAndRetrieverTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private SolrAudiobookRepository audiobookRepository;

    @Test
    void shouldEmbedAudiobookAndIndexAllOriginalFields() throws Exception {
        AudiobookEmbeddingIndexer indexer = new AudiobookEmbeddingIndexer(embeddingService, audiobookRepository,
                "audiobook_vector");
        SolrDocument audiobook = new SolrDocument();
        audiobook.setField("id", "book-1");
        audiobook.setField("title", "The Hidden Door");
        float[] embedding = new float[] { 0.1f, 0.2f };
        when(embeddingService.embedAudiobook(audiobook)).thenReturn(embedding);

        indexer.index(audiobook);

        ArgumentCaptor<SolrInputDocument> documentCaptor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(audiobookRepository)
                .indexWithEmbedding(documentCaptor.capture(), eq(embedding), eq("audiobook_vector"));
        SolrInputDocument indexedDocument = documentCaptor.getValue();
        assertEquals("book-1", indexedDocument.getFieldValue("id"));
        assertEquals("The Hidden Door", indexedDocument.getFieldValue("title"));
    }

    @Test
    void shouldRejectNullAudiobookBeforeCallingDependencies() {
        AudiobookEmbeddingIndexer indexer = new AudiobookEmbeddingIndexer(embeddingService, audiobookRepository,
                "audiobook_vector");

        assertThrows(IllegalArgumentException.class, () -> indexer.index(null));
    }

    @Test
    void shouldEmbedRequestAndReturnSemanticCandidates() throws Exception {
        CandidateRetriever retriever = new CandidateRetriever(audiobookRepository, embeddingService,
                "audiobook_vector");
        SessionRequest request = new SessionRequest();
        float[] embedding = new float[] { 0.4f, 0.5f };
        SolrDocument result = new SolrDocument();
        result.setField("id", "book-2");
        QueryResponse response = mock(QueryResponse.class);
        SolrDocumentList results = new SolrDocumentList();
        results.add(result);
        when(response.getResults()).thenReturn(results);
        when(embeddingService.embedRequest(request)).thenReturn(embedding);
        when(audiobookRepository.searchByVector(embedding, 10, "audiobook_vector"))
                .thenReturn(response);

        List<SolrDocument> candidates = retriever.getSemanticCandidates(request, 10);

        assertEquals(List.of(result), candidates);
        verify(audiobookRepository).searchByVector(embedding, 10, "audiobook_vector");
    }

    @Test
    void shouldReindexExistingAudiobooksInOneBatch() throws Exception {
        AudiobookEmbeddingIndexer indexer = new AudiobookEmbeddingIndexer(embeddingService, audiobookRepository,
                "audiobook_vector");
        SolrDocument first = audiobook("book-1", "First book");
        SolrDocument second = audiobook("book-2", "Second book");
        QueryResponse response = mock(QueryResponse.class);
        SolrDocumentList results = new SolrDocumentList();
        results.add(first);
        results.add(second);
        when(response.getResults()).thenReturn(results);
        when(audiobookRepository.findAudiobooks(0, 10)).thenReturn(response);
        when(embeddingService.embedAudiobook(first)).thenReturn(new float[] { 1.0f, 0.0f });
        when(embeddingService.embedAudiobook(second)).thenReturn(new float[] { 0.0f, 1.0f });
        when(audiobookRepository.vectorUpdate(any(), any(), eq("audiobook_vector")))
                .thenReturn(new SolrInputDocument());

        int indexed = indexer.reindexAll(10);

        assertEquals(2, indexed);
        verify(audiobookRepository).findAudiobooks(0, 10);
        verify(audiobookRepository).updateEmbeddings(any());
    }

    @Test
    void shouldRejectInvalidReindexBatchSize() {
        AudiobookEmbeddingIndexer indexer = new AudiobookEmbeddingIndexer(embeddingService, audiobookRepository,
                "audiobook_vector");

        assertThrows(IllegalArgumentException.class, () -> indexer.reindexAll(0));
    }

    private SolrDocument audiobook(String id, String title) {
        SolrDocument audiobook = new SolrDocument();
        audiobook.setField("id", id);
        audiobook.setField("title", title);
        return audiobook;
    }
}
