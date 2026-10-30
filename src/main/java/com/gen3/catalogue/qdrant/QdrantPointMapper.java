package com.gen3.catalogue.qdrant;

import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.AUTHORS;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.DESCRIPTION;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.DURATION_MINUTES;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.ID;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.LANGUAGE;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.NARRATORS;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.PUBDATE;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.SHORT_DESCRIPTION;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.SOURCE;
import static com.gen3.catalogue.qdrant.QdrantAudiobookSchema.TITLE;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookRecord;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Converts database-independent audiobook records to and from Qdrant points. */
@ConditionalOnProperty(name = "audiobook.database", havingValue = "qdrant", matchIfMissing = true)
@Component
public class QdrantPointMapper {

  public static final String DENSE_VECTOR = "dense";
  public static final String KEYWORD_VECTOR = "keywords";

  private final SparseTextEncoder sparseEncoder;

  /** Maps stored Qdrant data without inventing metadata at request time. */
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

  /** Maps only data already stored in the source; absent vectors remain absent. */
  public PointStruct toStoredPoint(AudiobookEmbedding stored) {
    PointStruct.Builder point =
        PointStruct.newBuilder()
            .setId(pointId(stored.record().id()))
            .putAllPayload(payload(stored.record()));
    Map<String, io.qdrant.client.grpc.Points.Vector> vectors = new java.util.HashMap<>();
    if (stored.vector() != null && stored.vector().length > 0) {
      vectors.put(DENSE_VECTOR, vector(stored.vector()));
    }
    return point.setVectors(namedVectors(vectors)).build();
  }

  /** Restores an audiobook record from a Qdrant similarity-search result. */
  public AudiobookRecord toRecord(ScoredPoint point) {
    return toRecord(point.getPayloadMap());
  }

  /** Restores an audiobook record from a Qdrant scroll result. */
  public AudiobookRecord toRecord(RetrievedPoint point) {
    return toRecord(point.getPayloadMap());
  }

  private AudiobookRecord toRecord(Map<String, Value> payload) {
    return new AudiobookRecord(
        stringValue(payload, ID),
        stringValue(payload, SOURCE),
        stringValue(payload, TITLE),
        listValue(payload.get(AUTHORS)),
        stringValue(payload, DESCRIPTION),
        listValue(payload.get(NARRATORS)),
        stringValue(payload, LANGUAGE),
        integerValue(payload.get(DURATION_MINUTES)),
        stringValue(payload, PUBDATE),
        stringValue(payload, SHORT_DESCRIPTION));
  }

  /** Extracts the normalized dense vector included in a similarity-search result. */
  public float[] toDenseVector(ScoredPoint point) {
    if (point == null || !point.hasVectors()) {
      return null;
    }
    var namedVectors = point.getVectors().getVectors().getVectorsMap();
    if (!namedVectors.containsKey(DENSE_VECTOR) || !namedVectors.get(DENSE_VECTOR).hasDense()) {
      return null;
    }
    List<Float> values = namedVectors.get(DENSE_VECTOR).getDense().getDataList();
    if (values.isEmpty()) {
      return null;
    }
    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
      vector[index] = values.get(index);
    }
    return vector;
  }

  /** Generates the same legal Qdrant UUID every time a catalogue book ID is supplied. */
  public io.qdrant.client.grpc.Common.PointId pointId(String bookId) {
    if (bookId == null || bookId.isBlank()) {
      throw new IllegalArgumentException("Audiobook ID must not be blank");
    }
    UUID uuid = UUID.nameUUIDFromBytes(("audiobook:" + bookId).getBytes(StandardCharsets.UTF_8));
    return id(uuid);
  }

  /** Converts catalogue fields into the metadata returned with Qdrant search results. */
  private Map<String, Value> payload(AudiobookRecord record) {
    Map<String, Value> payload = new LinkedHashMap<>();
    put(payload, ID, record.id());
    put(payload, SOURCE, record.source());
    put(payload, TITLE, record.title());
    put(payload, DESCRIPTION, record.description());
    put(payload, SHORT_DESCRIPTION, record.shortDescription());
    put(payload, LANGUAGE, record.language());
    if (record.durationMinutes() != null) {
      payload.put(DURATION_MINUTES, value(record.durationMinutes().longValue()));
    }
    put(payload, PUBDATE, record.pubdate());
    List<Value> authors =
        record.authors() == null
            ? List.of()
            : record.authors().stream()
                .filter(author -> author != null)
                .map(QdrantPointMapper::textValue)
                .toList();
    payload.put(AUTHORS, list(authors));
    List<Value> narrators =
        record.narrators() == null
            ? List.of()
            : record.narrators().stream()
                .filter(narrator -> narrator != null)
                .map(QdrantPointMapper::textValue)
                .toList();
    payload.put(NARRATORS, list(narrators));
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
