package com.gen3.catalogue.qdrant;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.range;

import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.model.PersonNameNormalizer;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.Points.FieldType;
import java.util.Map;

/** Qdrant-only field names, index types, vector schema, and exact-filter translation. */
final class QdrantAudiobookSchema {
  private QdrantAudiobookSchema() {}

  static final String ID = "id";
  static final String SOURCE = "source";
  static final String TITLE = "title";
  static final String DESCRIPTION = "description";
  static final String SHORT_DESCRIPTION = "short_description";
  static final String AUTHORS = "authors";
  static final String NARRATORS = "narrators";
  static final String LANGUAGE = "language";
  static final String DURATION_MINUTES = "duration_minutes";
  static final String PUBDATE = "pubdate";

  static final Map<String, FieldType> PAYLOAD_INDEXES =
      Map.of(
          AUTHORS,
          FieldType.FieldTypeKeyword,
          NARRATORS,
          FieldType.FieldTypeKeyword,
          ID,
          FieldType.FieldTypeKeyword,
          LANGUAGE,
          FieldType.FieldTypeKeyword,
          SOURCE,
          FieldType.FieldTypeKeyword,
          DURATION_MINUTES,
          FieldType.FieldTypeInteger,
          PUBDATE,
          FieldType.FieldTypeDatetime,
          SHORT_DESCRIPTION,
          FieldType.FieldTypeKeyword);

  static Collections.CreateCollection collection(String collection, int dimension) {
    Collections.VectorParams denseParams =
        Collections.VectorParams.newBuilder()
            .setSize(dimension)
            .setDistance(Collections.Distance.Dot)
            .build();
    Collections.VectorsConfig vectors =
        Collections.VectorsConfig.newBuilder()
            .setParamsMap(
                Collections.VectorParamsMap.newBuilder()
                    .putMap(QdrantPointMapper.DENSE_VECTOR, denseParams))
            .build();
    Collections.SparseVectorParams sparseParams =
        Collections.SparseVectorParams.newBuilder().setModifier(Collections.Modifier.Idf).build();
    Collections.SparseVectorConfig sparseVectors =
        Collections.SparseVectorConfig.newBuilder()
            .putMap(QdrantPointMapper.KEYWORD_VECTOR, sparseParams)
            .build();
    return Collections.CreateCollection.newBuilder()
        .setCollectionName(collection)
        .setVectorsConfig(vectors)
        .setSparseVectorsConfig(sparseVectors)
        .build();
  }

  /** Converts structured application filters into Qdrant payload conditions. */
  static Filter filter(AudiobookFilters filters) {
    if (filters == null) {
      return null;
    }
    Filter.Builder builder = Filter.newBuilder();
    addConditions(builder, filters.mustInclude(), false);
    addConditions(builder, filters.mustNotInclude(), true);
    if (!filters.excludedBookIds().isEmpty()) {
      builder.addMustNot(matchKeywords(ID, filters.excludedBookIds()));
    }
    return builder.getMustCount() == 0 && builder.getMustNotCount() == 0 ? null : builder.build();
  }

  private static void addConditions(
      Filter.Builder builder, AudiobookFilterConditions conditions, boolean excluded) {
    if (conditions == null || !conditions.hasConditions()) {
      return;
    }
    var authors = PersonNameNormalizer.variants(conditions.authors());
    if (!authors.isEmpty()) {
      addCondition(builder, matchKeywords(AUTHORS, authors), excluded);
    }
    var narrators = PersonNameNormalizer.variants(conditions.narrators());
    if (!narrators.isEmpty()) {
      addCondition(builder, matchKeywords(NARRATORS, narrators), excluded);
    }
    if (conditions.language() != null && !conditions.language().isBlank()) {
      addCondition(builder, matchKeyword(LANGUAGE, conditions.language()), excluded);
    }
    if (conditions.source() != null && !conditions.source().isBlank()) {
      addCondition(builder, matchKeyword(SOURCE, conditions.source()), excluded);
    }
    addRangeCondition(
        builder,
        DURATION_MINUTES,
        conditions.minimumDurationMinutes(),
        conditions.maximumDurationMinutes(),
        excluded);
    if (conditions.minimumPublicationYear() != null
        || conditions.maximumPublicationYear() != null) {
      var dates = io.qdrant.client.grpc.Common.DatetimeRange.newBuilder();
      if (conditions.minimumPublicationYear() != null) {
        dates.setGte(
            timestamp(
                com.gen3.catalogue.model.PublicationDate.startOfYear(
                    conditions.minimumPublicationYear())));
      }
      if (conditions.maximumPublicationYear() != null) {
        // An exclusive upper bound includes every instant in the requested final year.
        if (conditions.maximumPublicationYear() == 9999) {
          dates.setLte(timestamp(java.time.Instant.parse("9999-12-31T23:59:59.999999Z")));
        } else {
          dates.setLt(
              timestamp(
                  com.gen3.catalogue.model.PublicationDate.startOfYear(
                      conditions.maximumPublicationYear() + 1)));
        }
      }
      addCondition(
          builder,
          io.qdrant.client.ConditionFactory.datetimeRange(PUBDATE, dates.build()),
          excluded);
    }
  }

  private static com.google.protobuf.Timestamp timestamp(java.time.Instant instant) {
    return com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static void addRangeCondition(
      Filter.Builder builder, String field, Integer minimum, Integer maximum, boolean excluded) {
    if (minimum == null && maximum == null) {
      return;
    }
    Range.Builder range = Range.newBuilder();
    if (minimum != null) {
      range.setGte(minimum);
    }
    if (maximum != null) {
      range.setLte(maximum);
    }
    addCondition(builder, range(field, range.build()), excluded);
  }

  private static void addCondition(Filter.Builder builder, Condition condition, boolean excluded) {
    if (excluded) {
      builder.addMustNot(condition);
    } else {
      builder.addMust(condition);
    }
  }
}
