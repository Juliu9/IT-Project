package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("external")
public class SolrAudiobookRepositoryTest {

  @Test
  public void testSearch() throws Exception {

    String solrUrl = System.getenv("SOLR_URL");
    String username = System.getenv("SOLR_USERNAME");
    String password = System.getenv("SOLR_PASSWORD");

    SolrClient solrClient =
        new HttpJdkSolrClient.Builder(solrUrl).withBasicAuthCredentials(username, password).build();

    try {
      SolrAudiobookRepository repository = new SolrAudiobookRepository(solrClient, "combinedbooks");
      QueryResponse response = repository.search("mystery", 1);

      System.out.println("SUCCESS!");
      System.out.println("Status Code: " + response.getStatus());
      System.out.println("Query Time: " + response.getQTime() + "ms");
      System.out.println("Documents Found: " + response.getResults().getNumFound());

      assertNotNull(response);
      assertFalse(response.getResults().isEmpty());
      assertNotNull(response.getResults().getFirst().getFieldValue("id"));
      assertNotNull(response.getResults().getFirst().getFieldValue("title"));

    } finally {
      solrClient.close();
    }
  }
}
