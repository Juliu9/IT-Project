package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.range;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.Points.FieldType;
import java.util.Map;

/** Qdrant-only field names, index types, vector schema, and exact-filter translation. */
final class QdrantAudiobookSchema {
  private QdrantAudiobookSchema() {}

  static final String BOOK_ID = "bookId";
  static final String SOURCE = "source";
  static final String TITLE = "title";
  static final String DESCRIPTION = "description";
  static final String SHORT_DESCRIPTION = "short_description";
  static final String AUTHORS = "authors";
  static final String NARRATORS = "narrators";
  static final String LANGUAGE = "language";
  static final String DURATION_MINUTES = "durationMinutes";
  static final String PUBLICATION_YEAR = "publicationYear";

  static final Map<String, FieldType> PAYLOAD_INDEXES =
      Map.of(
          AUTHORS,
          FieldType.FieldTypeKeyword,
          NARRATORS,
          FieldType.FieldTypeKeyword,
          BOOK_ID,
          FieldType.FieldTypeKeyword,
          LANGUAGE,
          FieldType.FieldTypeKeyword,
          SOURCE,
          FieldType.FieldTypeKeyword,
          DURATION_MINUTES,
          FieldType.FieldTypeInteger,
          PUBLICATION_YEAR,
          FieldType.FieldTypeInteger,
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
      builder.addMustNot(matchKeywords(BOOK_ID, filters.excludedBookIds()));
    }
    return builder.getMustCount() == 0 && builder.getMustNotCount() == 0 ? null : builder.build();
  }

  private static void addConditions(
      Filter.Builder builder, AudiobookFilterConditions conditions, boolean excluded) {
    if (conditions == null || !conditions.hasConditions()) {
      return;
    }
    if (conditions.authors() != null && !conditions.authors().isEmpty()) {
      addCondition(builder, matchKeywords(AUTHORS, conditions.authors()), excluded);
    }
    if (conditions.narrators() != null && !conditions.narrators().isEmpty()) {
      addCondition(builder, matchKeywords(NARRATORS, conditions.narrators()), excluded);
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
    addRangeCondition(
        builder,
        PUBLICATION_YEAR,
        conditions.minimumPublicationYear(),
        conditions.maximumPublicationYear(),
        excluded);
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
