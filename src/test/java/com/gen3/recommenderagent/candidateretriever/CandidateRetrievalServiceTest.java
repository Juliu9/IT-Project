package com.gen3.recommenderagent.candidateretriever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CandidateRetrievalServiceTest {

  @Test
  void routesSemanticAndFilterContentToTheSelectedAdapter() throws Exception {
    AudiobookCandidateSearch candidateSearch = mock(AudiobookCandidateSearch.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = requestWithTopic("desert");
    MustInclude filter = new MustInclude();
    filter.setLanguage("English");
    request.setMustInclude(filter);
    float[] vector = {0.6f, 0.8f};
    SemanticQueryVectors vectors = new SemanticQueryVectors(List.of(vector), List.of());
    when(embeddingIndexer.combineSemanticQueries(vectors)).thenReturn(vector);

    service("qdrant", candidateSearch, embeddingIndexer)
        .getCandidates(request, vectors, 5, Set.of());

    verify(candidateSearch)
        .searchHybrid(
            vector,
            "desert",
            new AudiobookFilters(
                new AudiobookFilterConditions(List.of(), List.of(), "English", null, null, null),
                AudiobookFilterConditions.empty(),
                List.of()),
            5);
  }

  @Test
  void skipsRetrievalWhenRequestHasNoSearchContent() {
    AudiobookCandidateSearch candidateSearch = mock(AudiobookCandidateSearch.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);

    List<AudiobookCandidate> result =
        service("qdrant", candidateSearch, embeddingIndexer)
            .getCandidates(new SessionRequest(), SemanticQueryVectors.empty(), 5, Set.of());

    assertThat(result).isEmpty();
    verify(embeddingIndexer, never()).combineSemanticQueries(SemanticQueryVectors.empty());
  }

  @Test
  void fallsBackToKeywordSearchWhenNoUsableEmbeddingIsAvailable() throws Exception {
    AudiobookCandidateSearch candidateSearch = mock(AudiobookCandidateSearch.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = requestWithTopic("space");
    SemanticQueryVectors vectors = new SemanticQueryVectors(List.of(new float[] {1}), List.of());
    when(embeddingIndexer.combineSemanticQueries(vectors)).thenReturn(new float[0]);

    service("solr", candidateSearch, embeddingIndexer).getCandidates(request, vectors, 5, Set.of());

    verify(candidateSearch).searchKeyword("space", AudiobookFilters.empty(), 5);
  }

  @Test
  void returnsCandidatesFromTheSelectedAdapter() throws Exception {
    AudiobookCandidateSearch candidateSearch = mock(AudiobookCandidateSearch.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = requestWithTopic("space");
    float[] vector = {0.6f, 0.8f};
    SemanticQueryVectors vectors = new SemanticQueryVectors(List.of(vector), List.of());
    AudiobookRecord book =
        new AudiobookRecord("42", "catalogue", "Book", List.of("Writer"), "Description");
    when(embeddingIndexer.combineSemanticQueries(vectors)).thenReturn(vector);
    when(candidateSearch.searchSemantic(vector, AudiobookFilters.empty(), 5))
        .thenReturn(List.of(new AudiobookCandidate(book, 0.91)));

    List<AudiobookCandidate> candidates =
        service("qdrant", candidateSearch, embeddingIndexer)
            .getCandidates(request, vectors, 5, Set.of());

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook().id()).isEqualTo("42");
    assertThat(candidates.getFirst().score()).isEqualTo(0.91);
  }

  @ParameterizedTest
  @MethodSource("backends")
  void translatesAdapterFailuresUsingTheConfiguredBackend(String backend) throws Exception {
    AudiobookCandidateSearch candidateSearch = mock(AudiobookCandidateSearch.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = requestWithTopic("space");
    float[] vector = {0.6f, 0.8f};
    SemanticQueryVectors vectors = new SemanticQueryVectors(List.of(vector), List.of());
    when(embeddingIndexer.combineSemanticQueries(vectors)).thenReturn(vector);
    when(candidateSearch.searchSemantic(vector, AudiobookFilters.empty(), 5))
        .thenThrow(new IOException("timed out"));

    assertThatThrownBy(
            () ->
                service(backend, candidateSearch, embeddingIndexer)
                    .getCandidates(request, vectors, 5, Set.of()))
        .isInstanceOf(CandidateRetrievalException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining(backend);
  }

  private static Stream<Arguments> backends() {
    return Stream.of(Arguments.of("qdrant"), Arguments.of("solr"));
  }

  private CandidateRetrievalService service(
      String backend, AudiobookCandidateSearch candidateSearch, EmbeddingIndexer embeddingIndexer) {
    return new CandidateRetrievalService(
        backend, candidateSearch, embeddingIndexer, new AudiobookRetrievalPlanner());
  }

  private SessionRequest requestWithTopic(String topic) {
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of(topic));
    SessionRequest request = new SessionRequest();
    request.setPositiveSemanticQuery(query);
    return request;
  }
}
