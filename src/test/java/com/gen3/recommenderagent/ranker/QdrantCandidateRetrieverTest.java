package com.gen3.recommenderagent.ranker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Filter;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.candidateretriever.QdrantCandidateRetriever;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class QdrantCandidateRetrieverTest {

  @Test
  void routesSemanticAndFilterContentToHybridSearch() throws Exception {
    QdrantAudiobookRepository repository = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = requestWithTopic("desert");
    Filter filter = new Filter();
    filter.setLanguage("English");
    request.setFilter(filter);
    float[] vector = {0.6f, 0.8f};
    SemanticQueryVectors vectors = new SemanticQueryVectors(List.of(vector), List.of());
    when(embeddingIndexer.combineSemanticQueries(vectors)).thenReturn(vector);

    new QdrantCandidateRetriever(repository, embeddingIndexer, new AudiobookRetrievalPlanner())
        .getCandidates(request, vectors, 5);

    verify(repository)
        .searchHybrid(
            vector, "desert", new AudiobookFilters(List.of(), List.of(), "English", null), 5);
  }

  @Test
  void skipsRetrievalWhenRequestHasNoSearchContent() throws Exception {
    QdrantAudiobookRepository repository = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SemanticQueryVectors vectors = SemanticQueryVectors.empty();

    List<AudiobookCandidate> result =
        new QdrantCandidateRetriever(repository, embeddingIndexer, new AudiobookRetrievalPlanner())
            .getCandidates(new SessionRequest(), vectors, 5);

    assertThat(result).isEmpty();
    verify(embeddingIndexer, never()).combineSemanticQueries(vectors);
  }

  @Test
  void combinesSemanticVectorsOnceAndSuppliesResultToQdrant() throws Exception {
    QdrantAudiobookRepository repository = mock(QdrantAudiobookRepository.class);
    EmbeddingIndexer embeddingIndexer = mock(EmbeddingIndexer.class);
    SessionRequest request = requestWithTopic("space");
    float[] vector = {0.6f, 0.8f};
    SemanticQueryVectors vectors = new SemanticQueryVectors(List.of(vector), List.of());
    AudiobookRecord book =
        new AudiobookRecord("42", "catalogue", "Book", List.of("Writer"), "Description");
    when(embeddingIndexer.combineSemanticQueries(vectors)).thenReturn(vector);
    when(repository.searchSemantic(vector, AudiobookFilters.empty(), 5))
        .thenReturn(List.of(new AudiobookCandidate(book, 0.91)));

    List<AudiobookCandidate> candidates =
        new QdrantCandidateRetriever(repository, embeddingIndexer, new AudiobookRetrievalPlanner())
            .getCandidates(request, vectors, 5);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().audiobook().id()).isEqualTo("42");
    assertThat(candidates.getFirst().score()).isEqualTo(0.91);
    verify(embeddingIndexer).combineSemanticQueries(vectors);
    verify(repository).searchSemantic(vector, AudiobookFilters.empty(), 5);
  }

  private SessionRequest requestWithTopic(String topic) {
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of(topic));
    SessionRequest request = new SessionRequest();
    request.setPositiveSemanticQuery(query);
    return request;
  }
}
