package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import java.io.IOException;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Retrieves recommendation candidates from Qdrant while preserving the ranker's input type. */
@Service
@ConditionalOnProperty(
    name = "audiobook.candidate-retriever",
    havingValue = "qdrant",
    matchIfMissing = true)
public class QdrantCandidateRetriever implements CandidateRetriever {

  private final QdrantAudiobookRepository repository;
  private final EmbeddingIndexer embeddingIndexer;

  /** Uses the Qdrant repository and the shared normalized request-embedding pipeline. */
  public QdrantCandidateRetriever(
      QdrantAudiobookRepository repository, EmbeddingIndexer embeddingIndexer) {
    this.repository = repository;
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Embeds the supplied text and retrieves the nearest audiobook candidates. */
  @Override
  public List<SolrDocument> getCandidates(String query, int limit) {
    try {
      return repository.searchBooks(query, limit).records().stream()
          .map(this::toRankerDocument)
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to retrieve candidates from Qdrant", exception);
    }
  }

  /** Embeds the combined raw and processed user request once, then searches Qdrant. */
  @Override
  public List<SolrDocument> getCandidates(String query, int limit, SessionRequest request) {
    try {
      float[] queryVector = embeddingIndexer.embedRequest(request);
      if (queryVector.length == 0) {
        return getCandidates(query, limit);
      }
      return repository.searchBooks(query, limit, queryVector).records().stream()
          .map(this::toRankerDocument)
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to retrieve candidates from Qdrant", exception);
    }
  }

  /** Adapts database-independent records to the existing ranking pipeline. */
  private SolrDocument toRankerDocument(AudiobookRecord record) {
    SolrDocument document = new SolrDocument();
    document.setField("id", record.id());
    document.setField("source", record.source());
    document.setField("title", record.title());
    document.setField("authors", record.authors());
    document.setField("description", record.description());
    return document;
  }
}
