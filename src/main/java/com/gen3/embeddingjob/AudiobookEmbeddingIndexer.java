package com.gen3.embeddingjob;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.vector.VectorMath;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;

/** Converts existing catalogue records to normalized vectors for the opt-in indexing job. */
public class AudiobookEmbeddingIndexer {
  private final EmbeddingModel model;

  public AudiobookEmbeddingIndexer(EmbeddingModel model) {
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
