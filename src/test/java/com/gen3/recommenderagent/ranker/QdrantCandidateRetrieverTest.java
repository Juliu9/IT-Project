package com.gen3.recommenderagent.ranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.ranker.candidate.QdrantCandidateRetriever;
import com.gen3.recommenderagent.ranker.candidate.retrieval.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.ranker.candidate.retrieval.IntentRetrievalPolicy;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
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
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("fantasy"));
    semanticQuery.setTopics(List.of("desert"));
    request.setQuery(semanticQuery);
    float[] vector = {0.6f, 0.8f};
    PreferenceSignals signals = PreferenceSignals.empty();
    when(embeddingIndexer.embedRequest(request, signals)).thenReturn(vector);

    new QdrantCandidateRetriever(
            repository,
            embeddingIndexer,
            new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()))
        .getCandidates(request, signals, 5);

    verify(repository).searchHybrid(vector, "desert fantasy", AudiobookFilters.empty(), 5);
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
            .getCandidates(request, PreferenceSignals.empty(), 5);

    assertThat(result).isEmpty();
    verify(embeddingIndexer, never()).embedRequest(request, PreferenceSignals.empty());
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
    PreferenceSignals signals = PreferenceSignals.empty();
    when(embeddingIndexer.embedRequest(request, signals)).thenReturn(vector);
    when(repository.searchSemantic(vector, AudiobookFilters.empty(), 5))
        .thenReturn(List.of(new AudiobookCandidate(book, 0.91)));

    List<AudiobookCandidate> candidates =
        new QdrantCandidateRetriever(
                repository,
                embeddingIndexer,
                new AudiobookRetrievalPlanner(new IntentRetrievalPolicy()))
            .getCandidates(request, signals, 5);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook().id()).isEqualTo("42");
    assertThat(candidates.getFirst().score()).isEqualTo(0.91);
    verify(repository).searchSemantic(vector, AudiobookFilters.empty(), 5);
  }
}
