package com.gen3.recommenderagent.embedding;

import com.gen3.catalogue.vector.VectorMath;
import com.gen3.recommenderagent.ai.AiProviderCalls;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Builds and embeds semantic search queries; catalogue indexing belongs to embeddingjob. */
@Service
public class QueryEmbeddingService {

  private final EmbeddingModel model;
  private final AiOperationObserver aiOperationObserver;

  /** Uses the configured Spring AI embedding model. */
  @Autowired
  public QueryEmbeddingService(EmbeddingModel model, AiOperationObserver aiOperationObserver) {
    this.model = model;
    this.aiOperationObserver = aiOperationObserver;
  }

  public QueryEmbeddingService(EmbeddingModel model) {
    this(model, AiOperationObserver.noop());
  }

  /** Encodes one complete semantic query as a normalized vector. */
  public float[] embedSemanticQuery(SemanticQuery query) {
    return query == null ? new float[0] : embedSemanticQueries(List.of(query)).getFirst();
  }

  /** Encodes all nonempty semantic queries in one embedding-model request. */
  public List<float[]> embedSemanticQueries(List<SemanticQuery> queries) {
    if (queries == null || queries.isEmpty()) {
      return List.of();
    }
    List<float[]> results = new ArrayList<>(queries.size());
    List<Integer> resultIndexes = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (int index = 0; index < queries.size(); index++) {
      results.add(new float[0]);
      String text = buildSemanticQueryText(queries.get(index));
      if (!text.isBlank()) {
        resultIndexes.add(index);
        texts.add(text);
      }
    }
    if (texts.isEmpty()) {
      return List.copyOf(results);
    }
    var response =
        AiProviderCalls.call(
            () ->
                aiOperationObserver.observe(
                    "embed_query",
                    () -> model.embedForResponse(texts),
                    AiOperationObserver.AiUsage::from));
    List<float[]> generated =
        response.getResults().stream().map(embedding -> embedding.getOutput()).toList();
    if (generated.size() != texts.size()) {
      throw new IllegalStateException("Embedding model returned an unexpected batch size");
    }
    for (int index = 0; index < generated.size(); index++) {
      results.set(resultIndexes.get(index), VectorMath.normalize(generated.get(index)));
    }
    return List.copyOf(results);
  }

  /** Adds the positive vector and the inverse negative vector for candidate retrieval. */
  public float[] combineSemanticQueries(SemanticQueryVectors vectors) {
    if (vectors == null || vectors.isEmpty()) {
      return new float[0];
    }
    return VectorMath.directionalAverage(vectors.positive(), vectors.negative());
  }

  /** Builds the stable structured representation sent to the embedding model. */
  public String buildSemanticQueryText(SemanticQuery query) {
    if (query == null) {
      return "";
    }
    return List.of(
            part("Topics", query.getTopics()),
            part("Genres", query.getGenres()),
            part("Authors", query.getAuthors()),
            part("Narrators", query.getNarrators()),
            part("Keywords", query.getKeywords()))
        .stream()
        .filter(value -> !value.isBlank())
        .collect(Collectors.joining("\n"));
  }

  /** Adds a label only when a scalar value contains text. */
  private String part(String label, String value) {
    return value == null || value.isBlank() ? "" : label + ": " + value.trim();
  }

  /** Adds a label only when a parsed list contains text. */
  private String part(String label, List<String> values) {
    if (values == null) {
      return "";
    }

    String joined =
        values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(", "));

    return part(label, joined);
  }
}
