package com.gen3.recommenderagent.storage.audiobook.solr;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.model.StoredAudiobook;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCatalogueRepository;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

/** Implements audiobook keyword, vector, catalogue, and indexing operations with Solr. */
@Repository
@ConditionalOnExpression(
    "'${audiobook.database:qdrant}' == 'solr' or '${audiobook.qdrant.migration.enabled:false}' == 'true'")
public class SolrAudiobookRepository
    implements AudiobookCatalogueRepository, AudiobookCandidateSearch, AudiobookVectorRepository {

  private static final double KEYWORD_WEIGHT = 0.4;
  private static final double VECTOR_WEIGHT = 0.6;

  private final SolrClient solrClient;
  private final String collection;
  private final String vectorField;
  private final int vectorDimension;

  @Autowired
  public SolrAudiobookRepository(
      SolrClient solrClient,
      @Value("${solr.collection}") String collection,
      @Value("${solr.vector-field:embedding}") String vectorField,
      @Value("${solr.embedding-dimension:1536}") int vectorDimension) {
    this.solrClient = solrClient;
    this.collection = collection;
    this.vectorField = vectorField;
    this.vectorDimension = vectorDimension;
  }

  public SolrAudiobookRepository(SolrClient solrClient, String collection) {
    this(solrClient, collection, "embedding", 1536);
  }

  /** Queries Solr using its native response type for the Solr adapter and its tests. */
  public QueryResponse search(String query, int limit) throws SolrServerException, IOException {
    return search(query, AudiobookFilters.empty(), limit);
  }

  /** Queries Solr lexically and adds any structured catalogue filters. */
  private QueryResponse search(String query, AudiobookFilters filters, int limit)
      throws SolrServerException, IOException {

    SolrQuery solrQuery = new SolrQuery();

    solrQuery.setQuery(query);
    solrQuery.set("defType", "edismax");
    solrQuery.set("qf", "all");
    solrQuery.set("uf", "all");
    solrQuery.setRows(limit);
    solrQuery.setFields(
        "id",
        "source",
        "title",
        "authors",
        "description",
        "narrators",
        "language",
        "durationMinutes",
        "publicationYear",
        "short_description",
        "score",
        vectorField);
    addFilters(solrQuery, filters);

    return solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
  }

  /** Adds required and excluded exact metadata constraints to a Solr query. */
  private void addFilters(SolrQuery query, AudiobookFilters filters) {
    if (filters == null || !filters.hasConditions()) {
      return;
    }
    addConditions(query, filters.mustInclude(), false);
    addConditions(query, filters.mustNotInclude(), true);
    addAnyValueFilter(query, "id", filters.excludedBookIds(), true);
  }

  private void addConditions(
      SolrQuery query, AudiobookFilterConditions conditions, boolean excluded) {
    if (conditions == null || !conditions.hasConditions()) {
      return;
    }
    addAnyValueFilter(query, "authors", conditions.authors(), excluded);
    addAnyValueFilter(query, "narrators", conditions.narrators(), excluded);
    addExactFilter(query, "language", conditions.language(), excluded);
    addExactFilter(query, "source", conditions.source(), excluded);
    addRangeFilter(
        query,
        "durationMinutes",
        conditions.minimumDurationMinutes(),
        conditions.maximumDurationMinutes(),
        excluded);
    addRangeFilter(
        query,
        "publicationYear",
        conditions.minimumPublicationYear(),
        conditions.maximumPublicationYear(),
        excluded);
  }

  private void addRangeFilter(
      SolrQuery query, String field, Integer minimum, Integer maximum, boolean excluded) {
    if (minimum == null && maximum == null) {
      return;
    }
    query.addFilterQuery(
        (excluded ? "-" : "")
            + field
            + ":["
            + (minimum == null ? "*" : minimum)
            + " TO "
            + (maximum == null ? "*" : maximum)
            + "]");
  }

  /** Adds one OR-based exact filter for a multivalued Solr field. */
  private void addAnyValueFilter(
      SolrQuery query, String field, List<String> values, boolean excluded) {
    if (values == null || values.isEmpty()) {
      return;
    }
    String alternatives =
        values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(ClientUtils::escapeQueryChars)
            .map(value -> "\"" + value + "\"")
            .reduce((left, right) -> left + " OR " + right)
            .orElse("");
    if (!alternatives.isBlank()) {
      query.addFilterQuery((excluded ? "-" : "") + field + ":(" + alternatives + ")");
    }
  }

  private void addExactFilter(SolrQuery query, String field, String value, boolean excluded) {
    if (value != null && !value.isBlank()) {
      query.addFilterQuery(
          (excluded ? "-" : "") + field + ":\"" + ClientUtils.escapeQueryChars(value) + "\"");
    }
  }

  /** Runs lexical and vector retrieval while preserving the blended Solr score. */
  @Override
  public List<AudiobookCandidate> searchHybrid(
      float[] queryVector, String query, AudiobookFilters filters, int limit) throws IOException {
    if (limit <= 0) {
      return List.of();
    }
    if (queryVector == null || queryVector.length == 0) {
      return searchKeyword(query, filters, limit);
    }
    validateDimension(queryVector);

    try {
      int candidateLimit = limit * 2;
      QueryResponse keywordResponse = search(query, filters, candidateLimit);
      QueryResponse vectorResponse;
      try {
        vectorResponse = searchVector(queryVector, filters, candidateLimit);
      } catch (SolrServerException | SolrException exception) {
        return toCandidates(keywordResponse.getResults().stream().limit(limit).toList());
      }
      List<SolrDocument> merged =
          mergeResults(keywordResponse.getResults(), vectorResponse.getResults());
      return toCandidates(merged.stream().limit(limit).toList());
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Solr hybrid audiobook search failed", exception);
    }
  }

  /** Stores a normalized embedding using an atomic update to the configured DenseVectorField. */
  @Override
  public void indexEmbedding(AudiobookRecord record, float[] vector) throws IOException {
    if (record.id() == null || record.id().isBlank() || vector == null || vector.length == 0) {
      return;
    }
    validateDimension(vector);

    indexEmbeddings(List.of(new AudiobookEmbedding(record, vector)));
  }

  /** Writes a batch of vector field updates and commits once. */
  @Override
  public void indexEmbeddings(List<AudiobookEmbedding> embeddings) throws IOException {
    writeEmbeddings(embeddings, null);
  }

  /** Rejects stale or deleted catalogue records instead of creating partial documents. */
  public void updateExistingEmbeddings(
      List<AudiobookEmbedding> embeddings, Map<String, Long> versions) throws IOException {
    writeEmbeddings(embeddings, java.util.Objects.requireNonNull(versions));
  }

  private void writeEmbeddings(List<AudiobookEmbedding> embeddings, Map<String, Long> versions)
      throws IOException {
    if (embeddings == null || embeddings.isEmpty()) {
      return;
    }

    List<SolrInputDocument> documents =
        embeddings.stream()
            .filter(
                embedding ->
                    embedding.record().id() != null
                        && !embedding.record().id().isBlank()
                        && embedding.vector() != null
                        && embedding.vector().length > 0)
            .peek(embedding -> validateDimension(embedding.vector()))
            .map(
                embedding -> {
                  SolrInputDocument document = new SolrInputDocument();
                  document.addField("id", embedding.record().id());
                  if (versions != null) {
                    Long version = versions.get(embedding.record().id());
                    if (version == null || version <= 1) {
                      throw new IllegalArgumentException(
                          "An exact source version is required for embedding updates");
                    }
                    document.addField("_version_", version);
                  }
                  document.addField(vectorField, Map.of("set", toList(embedding.vector())));
                  return document;
                })
            .toList();
    if (documents.isEmpty()) {
      return;
    }

    try {
      solrClient.add(collection, documents);
      solrClient.commit(collection);
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Could not store audiobook embedding in Solr", exception);
    }
  }

  /** Reads the complete catalogue in deterministic pages for the startup importer. */
  @Override
  public List<AudiobookRecord> findAllBooks(int offset, int limit) throws IOException {
    return cataloguePage(offset, limit, false).stream().map(this::toRecord).toList();
  }

  /** Retains each record's source version across the model call. */
  public List<StoredAudiobook> findEmbeddingRecords(int offset, int limit) throws IOException {
    var records = new ArrayList<StoredAudiobook>();
    for (var document : cataloguePage(offset, limit, false)) {
      Object version = document.getFieldValue("_version_");
      if (!(version instanceof Number number) || number.longValue() <= 1) {
        throw new IOException("Solr must return an exact _version_ for embedding maintenance");
      }
      records.add(new StoredAudiobook(toRecord(document), Long.toString(number.longValue())));
    }
    return records;
  }

  /** Reads existing catalogue values and vectors without generating or normalizing anything. */
  public List<AudiobookEmbedding> findStoredBooks(int offset, int limit) throws IOException {
    List<AudiobookEmbedding> records = new ArrayList<>();
    for (SolrDocument document : cataloguePage(offset, limit, true)) {
      Object stored = document.getFieldValue(vectorField);
      float[] vector = null;
      if (stored instanceof float[] values) {
        vector = values.clone();
      } else if (stored instanceof java.util.Collection<?> values) {
        vector = new float[values.size()];
        int index = 0;
        for (Object value : values) {
          if (!(value instanceof Number number)) {
            throw new IOException(
                "Invalid stored Solr vector for book " + document.getFieldValue("id"));
          }
          vector[index++] = number.floatValue();
        }
      } else if (stored != null) {
        throw new IOException(
            "Invalid stored Solr vector for book " + document.getFieldValue("id"));
      }
      records.add(new AudiobookEmbedding(toRecord(document), vector));
    }
    return records;
  }

  private SolrDocumentList cataloguePage(int offset, int limit, boolean includeVectors)
      throws IOException {
    SolrQuery solrQuery = new SolrQuery("*:* ".trim());
    solrQuery.setStart(Math.max(offset, 0));
    solrQuery.setRows(Math.max(limit, 1));
    solrQuery.setSort("id", SolrQuery.ORDER.asc);
    solrQuery.setFields(
        "id",
        "source",
        "title",
        "authors",
        "description",
        "narrators",
        "language",
        "durationMinutes",
        "publicationYear",
        "short_description",
        "_version_");
    if (includeVectors) {
      solrQuery.addField(vectorField);
    }
    try {
      QueryResponse response = solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
      return response.getResults();
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Could not load audiobook catalogue from Solr", exception);
    }
  }

  private QueryResponse searchVector(float[] queryVector, AudiobookFilters filters, int limit)
      throws SolrServerException, IOException {
    SolrQuery solrQuery = new SolrQuery();
    solrQuery.setQuery(
        "{!knn f=" + vectorField + " topK=" + limit + "}" + vectorLiteral(queryVector));
    solrQuery.setRows(limit);
    solrQuery.setFields(
        "id",
        "source",
        "title",
        "authors",
        "description",
        "narrators",
        "language",
        "durationMinutes",
        "publicationYear",
        "short_description",
        "score",
        vectorField);
    addFilters(solrQuery, filters);
    return solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
  }

  private List<SolrDocument> mergeResults(
      SolrDocumentList keywordDocuments, SolrDocumentList vectorDocuments) {
    Map<String, SolrDocument> documents = new LinkedHashMap<>();
    Map<String, Double> keywordScores = new HashMap<>();
    Map<String, Double> vectorScores = new HashMap<>();

    for (SolrDocument document : keywordDocuments) {
      String id = field(document, "id");
      if (id != null) {
        documents.put(id, copy(document));
        keywordScores.put(id, numericScore(document));
      }
    }
    for (SolrDocument document : vectorDocuments) {
      String id = field(document, "id");
      if (id != null) {
        documents.putIfAbsent(id, copy(document));
        vectorScores.put(id, numericScore(document));
      }
    }

    double maxKeyword = maxScore(keywordScores);
    double maxVector = maxScore(vectorScores);
    documents.forEach(
        (id, document) ->
            document.setField(
                "score",
                KEYWORD_WEIGHT * normalized(keywordScores.get(id), maxKeyword)
                    + VECTOR_WEIGHT * normalized(vectorScores.get(id), maxVector)));

    return documents.values().stream()
        .sorted((left, right) -> Double.compare(numericScore(right), numericScore(left)))
        .toList();
  }

  private SolrDocument copy(SolrDocument source) {
    SolrDocument copy = new SolrDocument();
    source.getFieldNames().forEach(name -> copy.setField(name, source.getFieldValue(name)));
    return copy;
  }

  private double numericScore(SolrDocument document) {
    Object score = document.getFieldValue("score");
    return score instanceof Number number ? number.doubleValue() : 0.0;
  }

  private double maxScore(Map<String, Double> scores) {
    return scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
  }

  private double normalized(Double score, double maximum) {
    return score == null || maximum <= 0.0 ? 0.0 : score / maximum;
  }

  private String vectorLiteral(float[] vector) {
    StringBuilder literal = new StringBuilder("[");
    for (int index = 0; index < vector.length; index++) {
      if (index > 0) {
        literal.append(',');
      }
      literal.append(vector[index]);
    }
    return literal.append(']').toString();
  }

  private List<Float> toList(float[] vector) {
    List<Float> values = new java.util.ArrayList<>(vector.length);
    for (float value : vector) {
      values.add(value);
    }
    return values;
  }

  private void validateDimension(float[] vector) {
    if (vector.length != vectorDimension) {
      throw new IllegalArgumentException(
          "Embedding dimension "
              + vector.length
              + " does not match Solr dimension "
              + vectorDimension);
    }
  }

  /** Runs a dense Solr search and retains each result's similarity score. */
  @Override
  public List<AudiobookCandidate> searchSemantic(
      float[] queryVector, AudiobookFilters filters, int limit) throws IOException {
    if (queryVector == null || queryVector.length == 0 || limit <= 0) {
      return List.of();
    }
    validateDimension(queryVector);
    try {
      QueryResponse response = searchVector(queryVector, filters, limit);
      return toCandidates(response.getResults());
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Solr semantic audiobook search failed", exception);
    }
  }

  /** Runs a lexical Solr search and retains each result's relevance score. */
  @Override
  public List<AudiobookCandidate> searchKeyword(String query, AudiobookFilters filters, int limit)
      throws IOException {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }
    try {
      QueryResponse response = search(query, filters, limit);
      return toCandidates(response.getResults());
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Solr audiobook search failed", exception);
    }
  }

  /** Runs a match-all Solr query constrained only by structured catalogue filters. */
  @Override
  public List<AudiobookCandidate> searchByFilters(AudiobookFilters filters, int limit)
      throws IOException {
    if (filters == null || !filters.hasConditions() || limit <= 0) {
      return List.of();
    }
    return searchKeyword("*:*", filters, limit);
  }

  private List<AudiobookCandidate> toCandidates(Collection<SolrDocument> documents)
      throws IOException {
    List<AudiobookCandidate> candidates = new ArrayList<>();
    for (SolrDocument document : documents) {
      Object score = document.getFieldValue("score");
      float[] vector = SolrVectors.read(document.getFieldValue(vectorField), vectorDimension);
      candidates.add(
          new AudiobookCandidate(
              toRecord(document),
              score instanceof Number number ? number.doubleValue() : null,
              vector == null ? null : VectorMath.normalize(vector)));
    }
    return candidates;
  }

  @Override
  public Optional<float[]> findEmbeddingByBookId(String bookId) throws IOException {
    return bookId == null || bookId.isBlank()
        ? Optional.empty()
        : Optional.ofNullable(findEmbeddingsByBookIds(List.of(bookId)).get(bookId));
  }

  @Override
  public Map<String, float[]> findEmbeddingsByBookIds(Collection<String> bookIds)
      throws IOException {
    if (bookIds == null || bookIds.isEmpty()) {
      return Map.of();
    }
    List<String> ids =
        bookIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    Map<String, float[]> vectors = new LinkedHashMap<>();
    for (int offset = 0; offset < ids.size(); offset += 100) {
      SolrQuery query = new SolrQuery("*:*");
      List<String> page = ids.subList(offset, Math.min(offset + 100, ids.size()));
      addAnyValueFilter(query, "id", page, false);
      query.setRows(page.size());
      query.setFields("id", vectorField);
      try {
        for (SolrDocument document :
            solrClient.query(collection, query, SolrRequest.METHOD.POST).getResults()) {
          float[] vector = SolrVectors.read(document.getFieldValue(vectorField), vectorDimension);
          if (vector != null) {
            vectors.put(field(document, "id"), vector);
          }
        }
      } catch (SolrServerException | SolrException exception) {
        throw new IOException("Could not read audiobook vectors from Solr", exception);
      }
    }
    return vectors;
  }

  /** Maps one Solr document to the fields used by the application. */
  private AudiobookRecord toRecord(SolrDocument document) {
    return new AudiobookRecord(
        field(document, "id"),
        field(document, "source"),
        field(document, "title"),
        stringList(document.getFieldValue("authors")),
        field(document, "description"),
        stringList(document.getFieldValue("narrators")),
        field(document, "language"),
        integer(document.getFieldValue("durationMinutes")),
        integer(document.getFieldValue("publicationYear")),
        field(document, "short_description"));
  }

  /** Converts a scalar or multivalued Solr field into a stable string list. */
  private List<String> stringList(Object value) {
    return value instanceof Collection<?> values
        ? values.stream().filter(item -> item != null).map(Object::toString).toList()
        : value == null ? List.of() : List.of(value.toString());
  }

  /** Converts an optional numeric Solr field into minutes. */
  private Integer integer(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? null : Integer.valueOf(value.toString());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  /** Converts an absent Solr value to null, as expected by the result record. */
  private String field(SolrDocument document, String name) {
    Object value = document.getFieldValue(name);
    return value == null ? null : value.toString();
  }
}
