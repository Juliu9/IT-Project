package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

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

  /** Creates an idempotent Qdrant point containing the normalized vector and catalogue payload. */
  public PointStruct toPoint(AudiobookEmbedding embedding) {
    AudiobookRecord record = embedding.record();
    return PointStruct.newBuilder()
        .setId(pointId(record.id()))
        .setVectors(vectors(embedding.vector()))
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
        stringValue(payload, "description"));
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
    List<Value> authors =
        record.authors() == null
            ? List.of()
            : record.authors().stream()
                .filter(author -> author != null)
                .map(QdrantPointMapper::textValue)
                .toList();
    payload.put("authors", list(authors));
    return payload;
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
}
