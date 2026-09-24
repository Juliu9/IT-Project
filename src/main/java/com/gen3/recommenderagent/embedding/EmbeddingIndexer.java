package com.gen3.recommenderagent.embedding;

import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds comparable audiobook and request text, embeds it, and persists unit vectors. */
@Service
public class EmbeddingIndexer {

  private final EmbeddingModel model;
  private final EmbeddingStore store;
  private final ObjectMapper mapper;

  /** Uses the configured Spring AI embedding model for both kinds of record. */
  public EmbeddingIndexer(EmbeddingModel model, EmbeddingStore store, ObjectMapper mapper) {
    this.model = model;
    this.store = store;
    this.mapper = mapper;
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

  /** Returns the normalized vector used for Solr indexing and query retrieval. */
  public float[] embedAudiobook(AudiobookRecord book) {
    String text = buildAudiobookText(book);
    return text.isBlank() ? new float[0] : VectorMath.normalize(model.embed(text));
  }

  /** Reuses the persisted audiobook vector and only calls the model for a new catalogue record. */
  public float[] ensureAudiobookEmbedding(AudiobookRecord book) {
    if (book.id() == null || book.id().isBlank()) {
      return new float[0];
    }

    var stored = findAudiobookEmbedding(book.id());
    if (stored.isPresent()) {
      return stored.get();
    }

    String id = "audiobook:" + book.id();
    float[] vector = embedAudiobook(book);
    if (vector.length > 0) {
      save(id, buildAudiobookText(book), vector);
    }
    return vector;
  }

  /**
   * Resolves one migration page with a single cache lookup and one model request for all misses.
   * Existing vectors retain their input order and newly generated vectors are persisted in bulk.
   */
  public List<AudiobookEmbedding> ensureAudiobookEmbeddings(List<AudiobookRecord> books) {
    if (books == null || books.isEmpty()) {
      return List.of();
    }

    List<AudiobookRecord> validBooks =
        books.stream().filter(book -> book != null && hasText(book.id())).toList();
    List<String> storageIds =
        validBooks.stream().map(book -> "audiobook:" + book.id().trim()).toList();
    Map<String, float[]> vectorsById = new HashMap<>();
    store.findAllById(storageIds)
        .forEach(stored -> vectorsById.put(stored.getId(), readVector(stored)));

    List<AudiobookRecord> missingBooks = new ArrayList<>();
    List<String> missingTexts = new ArrayList<>();
    for (AudiobookRecord book : validBooks) {
      String storageId = "audiobook:" + book.id().trim();
      String text = buildAudiobookText(book);
      if (!vectorsById.containsKey(storageId) && !text.isBlank()) {
        missingBooks.add(book);
        missingTexts.add(text);
      }
    }

    if (!missingTexts.isEmpty()) {
      List<float[]> generated = model.embed(missingTexts);
      if (generated.size() != missingBooks.size()) {
        throw new IllegalStateException("Embedding model returned an unexpected batch size");
      }
      List<StoredEmbedding> toSave = new ArrayList<>(generated.size());
      for (int index = 0; index < generated.size(); index++) {
        AudiobookRecord book = missingBooks.get(index);
        String storageId = "audiobook:" + book.id().trim();
        float[] normalized = VectorMath.normalize(generated.get(index));
        vectorsById.put(storageId, normalized);
        toSave.add(new StoredEmbedding(storageId, missingTexts.get(index), serialize(normalized)));
      }
      store.saveAll(toSave);
    }

    return validBooks.stream()
        .map(
            book ->
                new AudiobookEmbedding(
                    book, vectorsById.getOrDefault("audiobook:" + book.id().trim(), new float[0])))
        .filter(embedding -> embedding.vector().length > 0)
        .toList();
  }

  /**
   * Finds an existing normalized audiobook vector by its catalogue book ID.
   *
   * <p>The database key includes the {@code audiobook:} prefix so audiobook and request IDs cannot
   * collide. An empty result means startup indexing has not stored a vector for that book.
   */
  public Optional<float[]> findAudiobookEmbedding(String bookId) {
    if (bookId == null || bookId.isBlank()) {
      return Optional.empty();
    }

    return store.findById("audiobook:" + bookId.trim()).map(this::readVector);
  }

  /** Returns a normalized vector for the raw request and its parsed search constraints. */
  public float[] embedRequest(SessionRequest request) {
    String text = buildRequestText(request);
    return text.isBlank() ? new float[0] : VectorMath.normalize(model.embed(text));
  }

  /** Indexes the original request together with its parsed search terms under its request ID. */
  public void indexRequest(SessionRequest request) {
    if (request.getRequestId() == null || request.getRequestId().isBlank()) {
      throw new IllegalArgumentException("Request ID is required for embedding storage");
    }
    String text = buildRequestText(request);
    if (!text.isBlank()) {
      save("request:" + request.getRequestId(), text);
    }
  }

  /** Builds canonical request text without serializing the Java object or response text. */
  public String buildRequestText(SessionRequest request) {
    Query query = request.getQuery();
    String text =
        List.of(
                part("Request", request.getRawText()),
                part("Intent", request.getIntent() == null ? null : request.getIntent().name()),
                part("Topics", query == null ? null : query.getTopics()),
                part("Genres", query == null ? null : query.getGenres()),
                part("Authors", query == null ? null : query.getAuthors()),
                part("Narrators", query == null ? null : query.getNarrators()),
                part("Keywords", query == null ? null : query.getKeywords()),
                part(
                    "Included preferences",
                    request.getPreferences() == null
                        ? null
                        : request.getPreferences().getInclude()),
                part(
                    "Excluded preferences",
                    request.getPreferences() == null
                        ? null
                        : request.getPreferences().getExclude()),
                part(
                    "Duration",
                    request.getConstraints() == null
                        ? null
                        : request.getConstraints().getDuration()),
                part(
                    "Language",
                    request.getConstraints() == null
                        ? null
                        : request.getConstraints().getLanguage()))
            .stream()
            .filter(value -> !value.isBlank())
            .collect(Collectors.joining("\n"));
    return text;
  }

  /** Embeds and normalizes before the vector is serialized and saved. */
  private void save(String id, String text) {
    save(id, text, VectorMath.normalize(model.embed(text)));
  }

  private void save(String id, String text, float[] vector) {
    store.save(new StoredEmbedding(id, text, serialize(vector)));
  }

  /** Serializes a normalized vector for the relational embedding cache. */
  private String serialize(float[] vector) {
    try {
      return mapper.writeValueAsString(vector);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize embedding", exception);
    }
  }

  /** Deserializes the normalized vector stored as JSON in PostgreSQL. */
  private float[] readVector(StoredEmbedding stored) {
    try {
      return mapper.readValue(stored.getVectorJson(), float[].class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not read stored audiobook embedding", exception);
    }
  }

  /** Adds a label only when a scalar value contains text. */
  private String part(String label, String value) {
    return value == null || value.isBlank() ? "" : label + ": " + value.trim();
  }

  /** Reports whether a catalogue identifier can safely be used as a cache key. */
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
