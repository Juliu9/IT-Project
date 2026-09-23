package com.gen3.recommenderagent.storage.audiobook.solr;

import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.AudiobookSearchPage;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Implements audiobook keyword, vector, catalogue, and indexing operations with Solr. */
@Repository
public class SolrAudiobookRepository implements AudiobookRepository {

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

    SolrQuery solrQuery = new SolrQuery();

    solrQuery.setQuery(query);
    solrQuery.set("defType", "edismax");
    solrQuery.set("qf", "title rt_title authors rt_authors all");
    solrQuery.setRows(limit);
    solrQuery.setFields("id", "source", "title", "authors", "description", "score");

    return solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
  }

  /** Runs lexical edismax and Solr kNN retrieval, then blends their normalized scores. */
  @Override
  public AudiobookSearchPage searchBooks(String query, int limit, float[] queryVector)
      throws IOException {
    List<AudiobookRecord> records =
        searchCandidates(query, limit, queryVector).stream()
            .map(AudiobookCandidate::audiobook)
            .toList();
    return new AudiobookSearchPage(records.size(), records);
  }

  /** Runs lexical and vector retrieval while preserving the blended Solr score. */
  @Override
  public List<AudiobookCandidate> searchCandidates(
      String query, int limit, float[] queryVector) throws IOException {
    if (queryVector == null || queryVector.length == 0) {
      return searchCandidates(query, limit);
    }
    validateDimension(queryVector);

    try {
      int candidateLimit = Math.max(limit * 2, limit);
      QueryResponse keywordResponse = search(query, candidateLimit);
      QueryResponse vectorResponse;
      try {
        vectorResponse = searchVector(queryVector, candidateLimit);
      } catch (SolrServerException exception) {
        return keywordResponse.getResults().stream()
            .limit(limit)
            .map(this::toCandidate)
            .toList();
      }
      List<SolrDocument> merged =
          mergeResults(keywordResponse.getResults(), vectorResponse.getResults());
      return merged.stream().limit(limit).map(this::toCandidate).toList();
    } catch (SolrServerException exception) {
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

    indexEmbeddings(List.of(new AudiobookRepository.AudiobookEmbedding(record, vector)));
  }

  /** Writes a batch of vector field updates and commits once. */
  @Override
  public void indexEmbeddings(List<AudiobookRepository.AudiobookEmbedding> embeddings)
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
    } catch (SolrServerException exception) {
      throw new IOException("Could not store audiobook embedding in Solr", exception);
    }
  }

  /** Reads the complete catalogue in deterministic pages for the startup importer. */
  @Override
  public List<AudiobookRecord> findAllBooks(int offset, int limit) throws IOException {
    SolrQuery solrQuery = new SolrQuery("*:* ".trim());
    solrQuery.setStart(Math.max(offset, 0));
    solrQuery.setRows(Math.max(limit, 1));
    solrQuery.setSort("id", SolrQuery.ORDER.asc);
    solrQuery.setFields("id", "source", "title", "authors", "description");
    try {
      QueryResponse response = solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
      return response.getResults().stream().map(this::toRecord).toList();
    } catch (SolrServerException exception) {
      throw new IOException("Could not load audiobook catalogue from Solr", exception);
    }
  }

  private QueryResponse searchVector(float[] queryVector, int limit)
      throws SolrServerException, IOException {
    SolrQuery solrQuery = new SolrQuery();
    solrQuery.setQuery(
        "{!knn f=" + vectorField + " topK=" + limit + "}" + vectorLiteral(queryVector));
    solrQuery.setRows(limit);
    solrQuery.setFields("id", "source", "title", "authors", "description", "score");
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

  /** Converts Solr's response into the repository's database-independent search page. */
  @Override
  public AudiobookSearchPage searchBooks(String query, int limit) throws IOException {
    try {
      QueryResponse response = search(query, limit);
      return new AudiobookSearchPage(
          response.getResults().getNumFound(),
          response.getResults().stream().map(this::toRecord).toList());
    } catch (SolrServerException exception) {
      throw new IOException("Solr audiobook search failed", exception);
    }
  }

  /** Runs a lexical Solr search and retains each result's relevance score. */
  @Override
  public List<AudiobookCandidate> searchCandidates(String query, int limit) throws IOException {
    try {
      QueryResponse response = search(query, limit);
      return response.getResults().stream().map(this::toCandidate).toList();
    } catch (SolrServerException exception) {
      throw new IOException("Solr audiobook search failed", exception);
    }
  }

  /** Converts a Solr result into the shared candidate type without adding score to the book. */
  private AudiobookCandidate toCandidate(SolrDocument document) {
    Object score = document.getFieldValue("score");
    Double value = score instanceof Number number ? number.doubleValue() : null;
    return new AudiobookCandidate(toRecord(document), value);
  }

  /** Maps one Solr document to the fields used by the application. */
  private AudiobookRecord toRecord(SolrDocument document) {
    Object authorValue = document.getFieldValue("authors");
    List<String> authors =
        authorValue instanceof Collection<?> values
            ? values.stream().filter(value -> value != null).map(Object::toString).toList()
            : authorValue == null ? List.of() : List.of(authorValue.toString());
    return new AudiobookRecord(
        field(document, "id"),
        field(document, "source"),
        field(document, "title"),
        authors,
        field(document, "description"));
  }

  /** Converts an absent Solr value to null, as expected by the result record. */
  private String field(SolrDocument document, String name) {
    Object value = document.getFieldValue(name);
    return value == null ? null : value.toString();
  }
}
