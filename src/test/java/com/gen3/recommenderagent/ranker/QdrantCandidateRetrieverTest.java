package com.gen3.recommenderagent.ranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookFilters;
import com.gen3.recommenderagent.ranker.retrieval.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.ranker.retrieval.IntentRetrievalPolicy;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that Qdrant retrieval returns the shared candidate type with its score. */
class QdrantCandidateRetrieverTest {

  /** Confirms a hybrid intent executes dense and sparse retrieval together. */
  @Test
  void routesNewRecommendationToHybridSearch() throws Exception {
    QdrantAudiobookRepository repository = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.NEW_RECOMMENDATION);
    request.setRawText("fantasy in a desert");
    Query query = new Query();
    query.setGenres(List.of("fantasy"));
    query.setTopics(List.of("desert"));
    request.setQuery(query);
    float[] vector = {0.6f, 0.8f};
    when(embeddingIndexer.embedRequest(request)).thenReturn(vector);

    new QdrantCandidateRetriever(
            repository,
            embeddingIndexer,
            new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()))
        .getCandidates("fallback", 5, request);

    verify(repository)
        .searchHybrid(vector, "desert fantasy", AudiobookFilters.empty(), 5);
  }

  /** Confirms action-only intents avoid unnecessary embedding and database calls. */
  @Test
  void skipsRetrievalForNoneMode() throws Exception {
    QdrantAudiobookRepository repository = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.HELP);

    List<AudiobookCandidate> result =
        new QdrantCandidateRetriever(
                repository,
                embeddingIndexer,
                new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()))
            .getCandidates("help", 5, request);

    assertThat(result).isEmpty();
    verify(embeddingIndexer, never()).embedRequest(request);
  }

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
    when(repository.searchSemantic(vector, AudiobookFilters.empty(), 5))
        .thenReturn(List.of(new AudiobookCandidate(book, 0.91)));

    List<AudiobookCandidate> candidates =
        new QdrantCandidateRetriever(
                repository,
                embeddingIndexer,
                new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()))
            .getCandidates("mystery", 5, request);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook().id()).isEqualTo("42");
    assertThat(candidates.getFirst().score()).isEqualTo(0.91);
    verify(repository).searchSemantic(vector, AudiobookFilters.empty(), 5);
  }
}
