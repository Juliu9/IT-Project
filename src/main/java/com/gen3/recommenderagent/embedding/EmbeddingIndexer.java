package com.gen3.recommenderagent.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.AudiobookRecord;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

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

  /** Indexes a returned audiobook once; records without an ID or useful text are skipped. */
  public void indexAudiobook(AudiobookRecord book) {
    String id = book.id();
    if (id == null || id.isBlank() || store.existsById("audiobook:" + id)) {
      return;
    }
    String text = buildAudiobookText(book);
    if (!text.isBlank()) {
      save("audiobook:" + id, text);
    }
  }

  /** Builds the stable, human-readable catalogue representation sent to the model. */
  public String buildAudiobookText(AudiobookRecord book) {
    return List.of(
            part("title", book.title()),
            part("authors", book.authors()),
            part("description", book.description()),
            part("genres", book.genres()),
            part("narrator", book.narrator()))
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

    String id = "audiobook:" + book.id();
    var stored = store.findById(id);
    if (stored.isPresent()) {
      try {
        return mapper.readValue(stored.get().getVectorJson(), float[].class);
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Could not read stored audiobook embedding", exception);
      }
    }

    float[] vector = embedAudiobook(book);
    if (vector.length > 0) {
      save(id, buildAudiobookText(book), vector);
    }
    return vector;
  }

  public boolean hasAudiobookEmbedding(AudiobookRecord book) {
    return book.id() != null && !book.id().isBlank() && store.existsById("audiobook:" + book.id());
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
    try {
      store.save(new StoredEmbedding(id, text, mapper.writeValueAsString(vector)));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize embedding", exception);
    }
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
