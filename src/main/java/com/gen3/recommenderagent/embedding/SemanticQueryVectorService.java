package com.gen3.recommenderagent.embedding;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/** Encodes the request's positive and negative semantic queries exactly once. */
@Service
public class SemanticQueryVectorService {

  private final QueryEmbeddingService embeddingIndexer;

  public SemanticQueryVectorService(QueryEmbeddingService embeddingIndexer) {
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Keeps query polarity separate so retrieval can add positive and subtract negative evidence. */
  public SemanticQueryVectors create(SessionRequest request) {
    if (request == null) {
      return SemanticQueryVectors.empty();
    }
    List<float[]> embedded =
        embeddingIndexer.embedSemanticQueries(
            Arrays.asList(request.getPositiveSemanticQuery(), request.getNegativeSemanticQuery()));
    if (embedded.size() != 2) {
      throw new IllegalStateException("Embedding indexer returned an unexpected query batch size");
    }
    return new SemanticQueryVectors(vectorOf(embedded.get(0)), vectorOf(embedded.get(1)));
  }

  private List<float[]> vectorOf(float[] vector) {
    return vector == null || vector.length == 0 ? List.of() : List.of(vector);
  }

  /** Encodes session preferences, including author-only and structured preferences. */
  public SemanticQueryVectors createPersonalised(SessionRequest request) {
    SessionRequest embeddingRequest = new SessionRequest();
    var include = request.getMustInclude();
    var exclude = request.getMustNotInclude();
    embeddingRequest.setPositiveSemanticQuery(
        withFilters(
            request.getPositiveSemanticQuery(),
            include == null ? null : include.getAuthors(),
            include == null ? null : include.getNarrators(),
            include == null ? null : include.getLanguage(),
            include == null ? null : include.getDuration(),
            include == null ? null : include.getSource()));
    embeddingRequest.setNegativeSemanticQuery(
        withFilters(
            request.getNegativeSemanticQuery(),
            exclude == null ? null : exclude.getAuthors(),
            exclude == null ? null : exclude.getNarrators(),
            exclude == null ? null : exclude.getLanguage(),
            exclude == null ? null : exclude.getDuration(),
            exclude == null ? null : exclude.getSource()));
    return create(embeddingRequest);
  }

  private SemanticQuery withFilters(
      SemanticQuery query,
      List<String> authors,
      List<String> narrators,
      String language,
      String duration,
      String source) {
    SemanticQuery combined = new SemanticQuery();
    if (query != null) {
      combined.setTopics(query.getTopics());
      combined.setGenres(query.getGenres());
      combined.setAuthors(query.getAuthors());
      combined.setNarrators(query.getNarrators());
    }
    List<String> keywords = new ArrayList<>();
    if (query != null && query.getKeywords() != null) {
      keywords.addAll(query.getKeywords());
    }
    addTerms(keywords, "Author", authors);
    addTerms(keywords, "Narrator", narrators);
    addTerm(keywords, "Language", language);
    addTerm(keywords, "Duration", duration);
    addTerm(keywords, "Source", source);
    combined.setKeywords(keywords);
    return combined;
  }

  private void addTerms(List<String> terms, String label, List<String> values) {
    if (values != null) {
      values.forEach(value -> addTerm(terms, label, value));
    }
  }

  private void addTerm(List<String> terms, String label, String value) {
    if (value != null && !value.isBlank()) {
      terms.add(label + ": " + value.trim());
    }
  }
}
