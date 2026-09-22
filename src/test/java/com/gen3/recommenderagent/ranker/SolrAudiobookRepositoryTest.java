package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

@Testcontainers(disabledWithoutDocker = true)
class SolrAudiobookRepositoryTest {

  @Container static final SolrContainer SOLR = SolrContainerTestSupport.newContainer();

  private static SolrClient solrClient;

  @BeforeAll
  static void setUpSolr() throws Exception {
    solrClient = SolrContainerTestSupport.newClient(SOLR);
    SolrContainerTestSupport.configureSchema(solrClient);
    SolrContainerTestSupport.seedBooks(solrClient);
  }

  @AfterAll
  static void closeSolrClient() throws Exception {
    if (solrClient != null) {
      solrClient.close();
    }
  }

  @Test
  void shouldRetrieveOnlyScienceFictionBooksFromDockerSolr() throws Exception {
    SolrAudiobookRepository repository =
        new SolrAudiobookRepository(solrClient, SolrContainerTestSupport.COLLECTION);

    QueryResponse response = repository.search("science fiction", 10);

    Set<String> returnedIds =
        response.getResults().stream()
            .map(document -> document.getFieldValue("id").toString())
            .collect(Collectors.toSet());

    assertEquals(5, response.getResults().getNumFound());
    assertEquals(Set.of("book-101", "book-202", "book-303", "book-505", "book-606"), returnedIds);
    assertFalse(returnedIds.contains("book-404"));
  }
}
