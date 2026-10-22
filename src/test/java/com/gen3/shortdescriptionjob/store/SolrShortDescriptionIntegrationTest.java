package com.gen3.shortdescriptionjob.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;

@Testcontainers(disabledWithoutDocker = true)
class SolrShortDescriptionIntegrationTest {
  @Container static final SolrContainer SERVER = SolrContainerTestSupport.newContainer();
  private static SolrClient client;
  private static final String COLLECTION = SolrContainerTestSupport.COLLECTION;

  @BeforeAll
  static void setup() throws Exception {
    client = SolrContainerTestSupport.newClient(SERVER);
    SolrContainerTestSupport.configureSchema(client);
    var type = new FieldTypeDefinition();
    type.setAttributes(
        Map.of(
            "name",
            "summary_vector",
            "class",
            "solr.DenseVectorField",
            "vectorDimension",
            2,
            "similarityFunction",
            "cosine"));
    new SchemaRequest.AddFieldType(type).process(client, COLLECTION);
    new SchemaRequest.AddField(
            Map.of("name", "embedding", "type", "summary_vector", "stored", true, "indexed", true))
        .process(client, COLLECTION);
  }

  @AfterAll
  static void close() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  private SolrShortDescriptionStore store(SolrClient connection) {
    return new SolrShortDescriptionStore(
        connection,
        new SolrAudiobookRepository(client, COLLECTION, "embedding", 2),
        COLLECTION,
        SolrContainerTestSupport.baseUrl(SERVER));
  }

  private void seed(String id, String summary) throws Exception {
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", id);
    document.addField("title", "Title");
    document.addField("description", "A long description.");
    document.addField("embedding", List.of(1f, 0f));
    document.addField("client_custom_s", "Keep this");
    if (summary != null) {
      document.addField("short_description", summary);
    }
    client.add(COLLECTION, document);
    client.commit(COLLECTION);
  }

  @Test
  void updatesOnlyMissingSummariesAndPreservesVectorsAndMetadata() throws Exception {
    seed("missing", null);
    seed("present", "Existing summary");
    var store = store(client);
    assertThat(store.findRecordsWithoutShortDescription(100))
        .extracting(book -> book.record().id())
        .contains("missing")
        .doesNotContain("present");
    store.updateShortDescriptions(
        List.of(
            new AudiobookEnrichmentStore.ShortDescriptionUpdate("missing", "Generated summary"),
            new AudiobookEnrichmentStore.ShortDescriptionUpdate("present", "Replacement")));
    var updated = client.getById(COLLECTION, "missing");
    assertThat(updated.getFieldValue("short_description")).isEqualTo("Generated summary");
    assertThat(updated.getFieldValue("title")).isEqualTo("Title");
    assertThat(updated.getFieldValue("client_custom_s")).isEqualTo("Keep this");
    assertThat(updated.getFieldValues("embedding")).hasSize(2);
    assertThat(client.getById(COLLECTION, "present").getFieldValue("short_description"))
        .isEqualTo("Existing summary");
  }

  @Test
  void concurrentManualSummaryIsNotOverwritten() throws Exception {
    seed("conflict", null);
    var intercepted = org.mockito.Mockito.spy(client);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var previous = client.getById(COLLECTION, "conflict");
              var manual = new SolrInputDocument();
              manual.addField("id", "conflict");
              manual.addField("short_description", Map.of("set", "Manual summary"));
              client.add(COLLECTION, manual);
              client.commit(COLLECTION);
              return previous;
            })
        .when(intercepted)
        .getById(COLLECTION, "conflict");
    assertThatThrownBy(
            () ->
                store(intercepted)
                    .updateShortDescriptions(
                        List.of(
                            new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                                "conflict", "Generated summary"))))
        .isInstanceOf(org.apache.solr.common.SolrException.class)
        .hasMessageContaining("version conflict");
    assertThat(client.getById(COLLECTION, "conflict").getFieldValue("short_description"))
        .isEqualTo("Manual summary");
  }
}
