package com.gen3.recommenderagent.ranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that Qdrant retrieval returns the shared candidate type with its score. */
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
    when(repository.searchCandidates("mystery", 5, vector))
        .thenReturn(List.of(new AudiobookCandidate(book, 0.91)));

    List<AudiobookCandidate> candidates =
        new QdrantCandidateRetriever(repository, embeddingIndexer)
            .getCandidates("mystery", 5, request);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook().id()).isEqualTo("42");
    assertThat(candidates.getFirst().score()).isEqualTo(0.91);
    verify(repository).searchCandidates("mystery", 5, vector);
  }
}
