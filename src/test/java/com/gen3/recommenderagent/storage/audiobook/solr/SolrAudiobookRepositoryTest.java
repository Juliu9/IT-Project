package com.gen3.recommenderagent.storage.audiobook.solr;

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
  void embeddingMaintenanceJobWritesVectorsWhilePreservingSummariesAndCustomFields()
      throws Exception {
    String collection = SolrContainerTestSupport.COLLECTION;
    var type = new org.apache.solr.client.solrj.request.schema.FieldTypeDefinition();
    type.setAttributes(
        java.util.Map.of(
            "name",
            "job_vector",
            "class",
            "solr.DenseVectorField",
            "vectorDimension",
            1536,
            "similarityFunction",
            "cosine"));
    new org.apache.solr.client.solrj.request.schema.SchemaRequest.AddFieldType(type)
        .process(solrClient, collection);
    new org.apache.solr.client.solrj.request.schema.SchemaRequest.AddField(
            java.util.Map.of(
                "name", "embedding", "type", "job_vector", "stored", true, "indexed", true))
        .process(solrClient, collection);
    var metadata = new org.apache.solr.common.SolrInputDocument();
    metadata.addField("id", "book-101");
    metadata.addField("short_description", java.util.Map.of("set", "Keep this summary"));
    metadata.addField("client_custom_s", java.util.Map.of("set", "Keep this custom field"));
    solrClient.add(collection, metadata);
    solrClient.commit(collection);
    var original = solrClient.getById(collection, "book-101");
    var model = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
    float[] generated = new float[1536];
    generated[0] = 3;
    generated[1] = 4;
    org.mockito.Mockito.when(model.embed(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(java.util.List.of(generated));
    var repository = new SolrAudiobookRepository(solrClient, collection, "embedding", 1536);
    new com.gen3.embeddingjob.CatalogueEmbeddingJob(
            new com.gen3.embeddingjob.store.SolrEmbeddingStore(repository),
            new com.gen3.embeddingjob.AudiobookEmbeddingIndexer(model),
            1,
            1)
        .run();
    var updated = solrClient.getById(collection, "book-101");
    assertEquals(original.getFieldValue("title"), updated.getFieldValue("title"));
    assertEquals(original.getFieldValue("rt_title"), updated.getFieldValue("rt_title"));
    assertEquals("Keep this summary", updated.getFieldValue("short_description"));
    assertEquals("Keep this custom field", updated.getFieldValue("client_custom_s"));
    float[] expected = new float[1536];
    expected[0] = 0.6f;
    expected[1] = 0.8f;
    org.assertj.core.api.Assertions.assertThat(repository.findStoredBooks(0, 1).getFirst().vector())
        .containsExactly(expected);
    org.mockito.Mockito.verify(model).embed(org.mockito.ArgumentMatchers.anyList());
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
