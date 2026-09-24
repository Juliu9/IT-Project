package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository.AudiobookEmbedding;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Converts database-independent audiobook records to and from Qdrant points. */
@Component
public class QdrantPointMapper {

  public static final String DENSE_VECTOR = "dense";
  public static final String KEYWORD_VECTOR = "keywords";

  private final SparseTextEncoder sparseEncoder;

  /** Uses the same sparse encoder for catalogue indexing and request retrieval. */
  public QdrantPointMapper(SparseTextEncoder sparseEncoder) {
    this.sparseEncoder = sparseEncoder;
  }

  /** Creates an idempotent Qdrant point containing the normalized vector and catalogue payload. */
  public PointStruct toPoint(AudiobookEmbedding embedding) {
    AudiobookRecord record = embedding.record();
    SparseVectorData sparse = sparseEncoder.encode(searchableText(record));
    return PointStruct.newBuilder()
        .setId(pointId(record.id()))
        .setVectors(
            namedVectors(
                Map.of(
                    DENSE_VECTOR,
                    vector(embedding.vector()),
                    KEYWORD_VECTOR,
                    vector(sparse.values(), sparse.indices()))))
        .putAllPayload(payload(record))
        .build();
  }

  /** Restores an audiobook record from a Qdrant similarity-search result. */
  public AudiobookRecord toRecord(ScoredPoint point) {
    Map<String, Value> payload = point.getPayloadMap();
    return new AudiobookRecord(
        stringValue(payload, "bookId"),
        stringValue(payload, "source"),
        stringValue(payload, "title"),
        listValue(payload.get("authors")),
        stringValue(payload, "description"),
        listValue(payload.get("narrators")),
        stringValue(payload, "language"),
        integerValue(payload.get("durationMinutes")));
  }

  /** Generates the same legal Qdrant UUID every time a catalogue book ID is supplied. */
  public io.qdrant.client.grpc.Common.PointId pointId(String bookId) {
    if (bookId == null || bookId.isBlank()) {
      throw new IllegalArgumentException("Audiobook ID must not be blank");
    }
    UUID uuid =
        UUID.nameUUIDFromBytes(("audiobook:" + bookId).getBytes(StandardCharsets.UTF_8));
    return id(uuid);
  }

  /** Converts catalogue fields into the metadata returned with Qdrant search results. */
  private Map<String, Value> payload(AudiobookRecord record) {
    Map<String, Value> payload = new LinkedHashMap<>();
    put(payload, "bookId", record.id());
    put(payload, "source", record.source());
    put(payload, "title", record.title());
    put(payload, "description", record.description());
    put(payload, "language", record.language());
    if (record.durationMinutes() != null) {
      payload.put("durationMinutes", value(record.durationMinutes().longValue()));
    }
    List<Value> authors =
        record.authors() == null
            ? List.of()
            : record.authors().stream()
                .filter(author -> author != null)
                .map(QdrantPointMapper::textValue)
                .toList();
    payload.put("authors", list(authors));
    List<Value> narrators =
        record.narrators() == null
            ? List.of()
            : record.narrators().stream()
                .filter(narrator -> narrator != null)
                .map(QdrantPointMapper::textValue)
                .toList();
    payload.put("narrators", list(narrators));
    return payload;
  }

  /** Builds the lexical document used by the sparse keyword vector. */
  private String searchableText(AudiobookRecord record) {
    return String.join(
        " ",
        text(record.title()),
        text(record.authors()),
        text(record.narrators()),
        text(record.description()));
  }

  /** Converts an optional scalar into searchable text. */
  private String text(String value) {
    return value == null ? "" : value;
  }

  /** Converts an optional list into searchable text. */
  private String text(List<String> values) {
    return values == null ? "" : String.join(" ", values);
  }

  /** Adds a payload string only when a value is available. */
  private void put(Map<String, Value> payload, String name, String fieldValue) {
    if (fieldValue != null) {
      payload.put(name, value(fieldValue));
    }
  }

  /** Converts one author to a Qdrant JSON value for use in a payload list. */
  private static Value textValue(String text) {
    return value(text);
  }

  /** Reads a string payload field while treating a missing field as null. */
  private String stringValue(Map<String, Value> payload, String name) {
    Value field = payload.get(name);
    return field == null ? null : field.getStringValue();
  }

  /** Reads the multivalued authors payload. */
  private List<String> listValue(Value field) {
    if (field == null || !field.hasListValue()) {
      return List.of();
    }
    return field.getListValue().getValuesList().stream().map(Value::getStringValue).toList();
  }

  /** Reads an optional integer payload field. */
  private Integer integerValue(Value field) {
    return field == null ? null : Math.toIntExact(field.getIntegerValue());
  }
}
