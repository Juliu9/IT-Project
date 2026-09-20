/* This class is responsible for communication with Solr */
package com.gen3.recommenderagent.ranker;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
// Executes semantic similarity search using Solr's DenseVectorField and kNN
// parser.
public class SolrAudiobookRepository {

  private final SolrClient solrClient;
  private final String collection;

  public SolrAudiobookRepository(
      SolrClient solrClient, @Value("${solr.collection}") String collection) {
    this.solrClient = solrClient;
    this.collection = collection;
  }

  public QueryResponse search(String query, int limit) throws SolrServerException, IOException {

    SolrQuery solrQuery = new SolrQuery();

    solrQuery.setQuery(query);
    solrQuery.set("defType", "edismax");
    solrQuery.set("qf", "title rt_title authors rt_authors all");
    solrQuery.setRows(limit);
    solrQuery.setFields("id", "source", "title", "authors", "description", "score");

    return solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
  }

  // Creates a Solr k-nearest-neighbour query to search for audiobook vectors
  // closest to the user's query vector
  public QueryResponse searchByVector(float[] queryVector, int limit, String vectorField)
      throws SolrServerException, IOException {
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("Query vector must not be empty");
    }

    SolrQuery solrQuery = new SolrQuery();
    solrQuery.setQuery(
        "{!knn f=" + vectorField + " topK=" + limit + "}" + formatVector(queryVector));
    solrQuery.setRows(limit);
    solrQuery.setFields("id", "source", "title", "authors", "description", "score");

    return solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
  }

  public QueryResponse findAudiobooks(int start, int limit)
      throws SolrServerException, IOException {
    SolrQuery solrQuery = new SolrQuery("*:*");
    solrQuery.setStart(start);
    solrQuery.setRows(limit);
    solrQuery.setSort("id", SolrQuery.ORDER.asc);
    solrQuery.setFields("id", "title", "authors", "description", "genres");
    return solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);
  }

  // This method adds the vector to the Solr document.
  // Sends the document to Solr.
  // Commits the collection.
  public void indexWithEmbedding(SolrInputDocument document, float[] embedding, String vectorField)
      throws SolrServerException, IOException {
    if (document == null || embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("Document and embedding are required");
    }

    document.setField(vectorField, embedding);
    solrClient.add(collection, document);
    solrClient.commit(collection);
  }

  public void updateEmbeddings(List<SolrInputDocument> documents)
      throws SolrServerException, IOException {
    if (documents == null || documents.isEmpty()) {
      throw new IllegalArgumentException("Embedding documents must not be empty");
    }

    solrClient.add(collection, documents);
    solrClient.commit(collection);
  }

  public SolrInputDocument vectorUpdate(String id, float[] embedding, String vectorField) {
    if (id == null || id.isBlank() || embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("Document id and embedding are required");
    }

    SolrInputDocument update = new SolrInputDocument();
    update.setField("id", id);
    update.setField(vectorField, Map.of("set", embedding));
    return update;
  }

  private String formatVector(float[] vector) {
    StringBuilder formatted = new StringBuilder("[");
    for (int index = 0; index < vector.length; index++) {
      if (index > 0) {
        formatted.append(",");
      }
      formatted.append(vector[index]);
    }
    return formatted.append("]").toString();
  }
}
