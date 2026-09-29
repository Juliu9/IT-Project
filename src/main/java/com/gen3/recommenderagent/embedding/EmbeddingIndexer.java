package com.gen3.recommenderagent.embedding;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Builds comparable audiobook and request text and generates normalized embeddings.
 *
 * <p>EmbeddingIndexer is deliberately persistence-agnostic. Vector storage and retrieval are
 * handled by the Qdrant storage layer.
 */
@Service
public class EmbeddingIndexer {

  private final EmbeddingModel model;

  /** Uses the configured Spring AI embedding model. */
  public EmbeddingIndexer(EmbeddingModel model) {
    this.model = model;
  }

  /** Builds the stable, human-readable catalogue representation sent to the model. */
  public String buildAudiobookText(AudiobookRecord book) {
    return List.of(
            part("title", book.title()),
            part("authors", book.authors()),
            part("narrators", book.narrators()),
            part("language", book.language()),
            part("description", book.description()))
        .stream()
        .filter(value -> !value.isBlank())
        .collect(Collectors.joining("\n"));
  }

  /** Returns the normalized vector used for indexing and semantic retrieval. */
  public float[] embedAudiobook(AudiobookRecord book) {
    String text = buildAudiobookText(book);

    return text.isBlank() ? new float[0] : VectorMath.normalize(model.embed(text));
  }

  /**
   * Embeds a batch of audiobook records.
   *
   * <p>The returned embeddings retain the same order as the supplied valid books.
   */
  public List<AudiobookEmbedding> embedAudiobooks(List<AudiobookRecord> books) {
    if (books == null || books.isEmpty()) {
      return List.of();
    }

    List<AudiobookRecord> validBooks =
        books.stream().filter(book -> book != null && hasText(book.id())).toList();

    if (validBooks.isEmpty()) {
      return List.of();
    }

    List<AudiobookRecord> embeddableBooks =
        validBooks.stream().filter(book -> !buildAudiobookText(book).isBlank()).toList();

    if (embeddableBooks.isEmpty()) {
      return List.of();
    }

    List<String> texts = embeddableBooks.stream().map(this::buildAudiobookText).toList();

    List<float[]> generated = model.embed(texts);

    if (generated.size() != embeddableBooks.size()) {
      throw new IllegalStateException("Embedding model returned an unexpected batch size");
    }

    List<AudiobookEmbedding> embeddings = new ArrayList<>(generated.size());

    for (int index = 0; index < generated.size(); index++) {
      AudiobookRecord book = embeddableBooks.get(index);
      float[] normalized = VectorMath.normalize(generated.get(index));

      embeddings.add(new AudiobookEmbedding(book, normalized));
    }

    return embeddings;
  }

  /** Encodes one complete semantic query as a normalized vector. */
  public float[] embedSemanticQuery(SemanticQuery query) {
    String text = buildSemanticQueryText(query);
    return text.isBlank() ? new float[0] : VectorMath.normalize(model.embed(text));
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

  /** Reports whether a value contains usable text. */
  private boolean hasText(String value) {
    return value != null && !value.isBlank();
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
