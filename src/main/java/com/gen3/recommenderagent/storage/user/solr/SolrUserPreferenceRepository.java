package com.gen3.recommenderagent.storage.user.solr;

import com.gen3.catalogue.solr.SolrVectors;
import com.gen3.catalogue.vector.VectorMath;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Stores one user's taste vectors under their exact external ID in a separate Solr collection. */
@Repository
@ConditionalOnProperty(name = "audiobook.database", havingValue = "solr")
public class SolrUserPreferenceRepository implements UserPreferenceVectorRepository {
  private final SolrClient client;
  private final String collection;
  private final int dimension;

  public SolrUserPreferenceRepository(
      SolrClient client,
      @Value("${solr.user-preference-collection:user}") String collection,
      @Value("${solr.embedding-dimension:1536}") int dimension,
      @Value("${solr.collection}") String catalogue) {
    if (collection.isBlank() || collection.equals(catalogue) || dimension < 1) {
      throw new IllegalArgumentException(
          "Solr preferences need a separate collection and a positive vector dimension");
    }
    this.client = client;
    this.collection = collection;
    this.dimension = dimension;
  }

  @Override
  public void save(UserPreferenceEmbedding embedding) throws IOException {
    if (embedding == null) {
      return;
    }
    saveAll(List.of(embedding));
  }

  @Override
  public void saveAll(List<UserPreferenceEmbedding> embeddings) throws IOException {
    if (embeddings.isEmpty()) {
      return;
    }
    var documents =
        embeddings.stream()
            .map(
                embedding -> {
                  var document = new SolrInputDocument();
                  document.setField("id", embedding.userId());
                  document.setField(
                      "favouritesVector",
                      Collections.singletonMap(
                          "set", SolrVectors.values(embedding.favouritesVector(), dimension)));
                  document.setField(
                      "historyVector",
                      Collections.singletonMap(
                          "set", SolrVectors.values(embedding.historyVector(), dimension)));
                  return document;
                })
            .toList();
    try {
      client.add(collection, documents);
      client.commit(collection);
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Could not write Solr user preferences", exception);
    }
  }

  @Override
  public Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) throws IOException {
    if (userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    try {
      // POST avoids SolrJ GET escaping problems with quotes and commas in external IDs.
      var query = new SolrQuery("{!term f=id v=$userId}");
      query.set("userId", userId);
      query.setRows(1);
      query.setFields("id", "favouritesVector", "historyVector");
      var documents = client.query(collection, query, SolrRequest.METHOD.POST).getResults();
      if (documents.isEmpty()) {
        return Optional.empty();
      }
      var document = documents.getFirst();
      float[] favourites = SolrVectors.read(document.getFieldValue("favouritesVector"), dimension);
      float[] history = SolrVectors.read(document.getFieldValue("historyVector"), dimension);
      if (favourites == null && history == null) {
        return Optional.empty();
      }
      return Optional.of(
          new UserPreferenceEmbedding(
              userId,
              favourites == null ? null : VectorMath.normalize(favourites),
              history == null ? null : VectorMath.normalize(history)));
    } catch (SolrServerException | SolrException exception) {
      throw new IOException("Could not read Solr user preferences", exception);
    }
  }
}
