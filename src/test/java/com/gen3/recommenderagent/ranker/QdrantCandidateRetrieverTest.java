package com.gen3.recommenderagent.ranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookSearchPage;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.Test;

/** Verifies that Qdrant retrieval uses the combined request embedding and adapts its result. */
class QdrantCandidateRetrieverTest {

  /** Confirms the request vector is generated once and supplied to Qdrant. */
  @Test
  void retrievesCandidatesWithProcessedRequestVector() throws Exception {
    QdrantAudiobookRepository repository = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = new SessionRequest();
    float[] vector = {0.6f, 0.8f};
    AudiobookRecord book =
        new AudiobookRecord("42", "catalogue", "Book", List.of("Writer"), "Description");
    when(embeddingIndexer.embedRequest(request)).thenReturn(vector);
    when(repository.searchBooks("mystery", 5, vector))
        .thenReturn(new AudiobookSearchPage(1, List.of(book)));

    List<SolrDocument> candidates =
        new QdrantCandidateRetriever(repository, embeddingIndexer)
            .getCandidates("mystery", 5, request);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().getFieldValue("id")).isEqualTo("42");
    verify(repository).searchBooks("mystery", 5, vector);
  }
}
