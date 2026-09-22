package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import java.io.IOException;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Service;

/*
   Gets candidates that can be used for machine learning.
*/
@Service
public class BaseSolrCandidateRetriever implements CandidateRetriever {

  private final AudiobookRepository audiobookRepository;
  private final EmbeddingIndexer embeddingIndexer;

  public BaseSolrCandidateRetriever(
      AudiobookRepository audiobookRepository, EmbeddingIndexer embeddingIndexer) {
    this.audiobookRepository = audiobookRepository;
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Retrieves audiobook candidates from Solr using the given query. */
  @Override
  public List<SolrDocument> getCandidates(String query, int limit) {

    try {
      List<AudiobookRecord> records = audiobookRepository.searchBooks(query, limit).records();
      records.forEach(embeddingIndexer::indexAudiobook);
      return records.stream().map(this::toSolrDocument).toList();

    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve candidates from Solr", e);
    }
  }

  /** Retrieves lexical and semantic candidates using the same embedding model as indexing. */
  @Override
  public List<SolrDocument> getCandidates(String query, int limit, SessionRequest request) {
    try {
      float[] queryVector = embeddingIndexer.embedRequest(request);
      if (queryVector == null || queryVector.length == 0) {
        return getCandidates(query, limit);
      }

      var lexicalRecords = audiobookRepository.searchBooks(query, limit).records();
      lexicalRecords.forEach(
          record -> {
            if (!embeddingIndexer.hasAudiobookEmbedding(record)) {
              embeddingIndexer.indexAudiobook(record);
              float[] bookVector = embeddingIndexer.ensureAudiobookEmbedding(record);
              if (bookVector != null && bookVector.length > 0) {
                try {
                  audiobookRepository.indexEmbedding(record, bookVector);
                } catch (IOException exception) {
                  throw new RuntimeException(
                      "Failed to index audiobook embedding in Solr", exception);
                }
              }
            }
          });

      return audiobookRepository.searchBooks(query, limit, queryVector).records().stream()
          .map(this::toSolrDocument)
          .toList();
    } catch (IOException exception) {
      throw new RuntimeException("Failed to retrieve semantic candidates from Solr", exception);
    }
  }

  /** Keeps the current ranker input format isolated from the repository boundary. */
  private SolrDocument toSolrDocument(AudiobookRecord record) {
    SolrDocument document = new SolrDocument();
    document.setField("id", record.id());
    document.setField("source", record.source());
    document.setField("title", record.title());
    document.setField("authors", record.authors());
    document.setField("description", record.description());
    document.setField("score", record.score());
    return document;
  }
}
