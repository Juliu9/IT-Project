package com.gen3.catalogue.solr;

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
  }

  @AfterAll
  static void closeSolrClient() throws Exception {
    if (solrClient != null) {
      solrClient.close();
    }
  }

  @Test
  void nameOrderVariantsMatchAndExcludeExactStringFields() throws Exception {
    String collection = "person-order-test";
    org.assertj.core.api.Assertions.assertThat(
            SOLR.execInContainer("solr", "create", "-c", collection).getExitCode())
        .isZero();
    for (String field : java.util.List.of("authors", "narrators", "source", "all")) {
      new org.apache.solr.client.solrj.request.schema.SchemaRequest.AddField(
              java.util.Map.of(
                  "name",
                  field,
                  "type",
                  "string",
                  "stored",
                  true,
                  "indexed",
                  true,
                  "multiValued",
                  field.equals("authors") || field.equals("narrators")))
          .process(solrClient, collection);
    }
    for (String id : java.util.List.of("forward", "comma", "period", "other")) {
      var document = new org.apache.solr.common.SolrInputDocument();
      document.setField("id", id);
      document.setField("source", "name-test");
      document.setField(
          "authors",
          java.util.List.of(
              switch (id) {
                case "forward" -> "Suzanne Collins";
                case "comma" -> "Collins, Suzanne";
                case "period" -> "Collins, Suzanne.";
                default -> "Another Author";
              }));
      document.setField(
          "narrators", java.util.List.of(id.equals("comma") ? "Fry, Stephen" : "Stephen Fry"));
      solrClient.add(collection, document);
    }
    for (var entry :
        java.util.Map.of(
                "initials-forward",
                "J. K. Rowling",
                "initials-comma",
                "Rowling, J. K.",
                "initials-compact",
                "Rowling, J.K.",
                "initials-separated",
                "J K Rowling")
            .entrySet()) {
      var document = new org.apache.solr.common.SolrInputDocument();
      document.setField("id", entry.getKey());
      document.setField("source", "initials-test");
      document.setField("authors", java.util.List.of(entry.getValue()));
      document.setField("narrators", java.util.List.of(entry.getValue()));
      solrClient.add(collection, document);
    }
    solrClient.commit(collection);
    var repository = new SolrAudiobookRepository(solrClient, collection);
    var names =
        new com.gen3.catalogue.model.AudiobookFilterConditions(
            java.util.List.of("Suzanne Collins"),
            java.util.List.of("Stephen Fry"),
            null,
            null,
            null,
            null,
            null,
            null);
    var empty = com.gen3.catalogue.model.AudiobookFilterConditions.empty();
    var initials =
        new com.gen3.catalogue.model.AudiobookFilterConditions(
            java.util.List.of("J. K. Rowling"),
            java.util.List.of("J. K. Rowling"),
            null,
            null,
            null,
            null,
            null,
            null);
    org.assertj.core.api.Assertions.assertThat(
            repository.searchByFilters(
                new com.gen3.catalogue.model.AudiobookFilters(initials, empty, java.util.List.of()),
                10))
        .extracting(book -> book.audiobook().id())
        .containsExactlyInAnyOrder(
            "initials-forward", "initials-comma", "initials-compact", "initials-separated");
    var excludeInitials =
        new com.gen3.catalogue.model.AudiobookFilterConditions(
            java.util.List.of(),
            java.util.List.of("Rowling, J. K."),
            null,
            null,
            null,
            null,
            null,
            null);
    org.assertj.core.api.Assertions.assertThat(
            repository.searchByFilters(
                new com.gen3.catalogue.model.AudiobookFilters(
                    initials, excludeInitials, java.util.List.of()),
                10))
        .isEmpty();
    org.assertj.core.api.Assertions.assertThat(
            repository.searchByFilters(
                new com.gen3.catalogue.model.AudiobookFilters(names, empty, java.util.List.of()),
                10))
        .extracting(book -> book.audiobook().id())
        .containsExactlyInAnyOrder("forward", "comma", "period");
    var all =
        new com.gen3.catalogue.model.AudiobookFilterConditions(
            java.util.List.of(), java.util.List.of(), null, "name-test", null, null, null, null);
    var excludedAuthor =
        new com.gen3.catalogue.model.AudiobookFilterConditions(
            java.util.List.of("Collins, Suzanne"),
            java.util.List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    org.assertj.core.api.Assertions.assertThat(
            repository.searchByFilters(
                new com.gen3.catalogue.model.AudiobookFilters(
                    all, excludedAuthor, java.util.List.of()),
                10))
        .extracting(book -> book.audiobook().id())
        .containsExactly("other");
    var excludedNarrator =
        new com.gen3.catalogue.model.AudiobookFilterConditions(
            java.util.List.of(),
            java.util.List.of("Fry, Stephen"),
            null,
            null,
            null,
            null,
            null,
            null);
    org.assertj.core.api.Assertions.assertThat(
            repository.searchByFilters(
                new com.gen3.catalogue.model.AudiobookFilters(
                    all, excludedNarrator, java.util.List.of()),
                10))
        .isEmpty();
  }

  @Test
  void metadataLookupReturnsOnlyRequestedCatalogueRecords() throws Exception {
    var repository = new SolrAudiobookRepository(solrClient, SolrContainerTestSupport.COLLECTION);
    var books = repository.findBooksByIds(java.util.List.of("book-404", "missing-id"));
    org.assertj.core.api.Assertions.assertThat(books).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(books.getFirst().id()).isEqualTo("book-404");
    org.assertj.core.api.Assertions.assertThat(books.getFirst().title()).isEqualTo("The Hobbit");
    org.assertj.core.api.Assertions.assertThat(books.getFirst().authors())
        .containsExactly("J. R. R. Tolkien");
    org.assertj.core.api.Assertions.assertThat(repository.findBooksByIds(java.util.List.of()))
        .isEmpty();
  }

  @Test
  void titleLookupUsesOnlyTitleAndReturnsEditionMetadata() throws Exception {
    var document = new org.apache.solr.common.SolrInputDocument();
    document.setField("id", "title-lookup");
    document.setField("title", "The Hunger Games");
    document.setField("authors", java.util.List.of("Suzanne Collins"));
    solrClient.add(SolrContainerTestSupport.COLLECTION, document);
    solrClient.commit(SolrContainerTestSupport.COLLECTION);
    var repository = new SolrAudiobookRepository(solrClient, SolrContainerTestSupport.COLLECTION);
    var results = repository.findBooksByTitle("the hunger games", null, 6);
    org.assertj.core.api.Assertions.assertThat(results)
        .extracting(com.gen3.catalogue.model.AudiobookTitle::bookId)
        .containsExactly("title-lookup");
    org.assertj.core.api.Assertions.assertThat(
            repository.findBooksByTitle("the hunger games", "suzanne collins", 6))
        .hasSize(1);
    org.assertj.core.api.Assertions.assertThat(
            repository.findBooksByTitle("the hunger games", "other author", 6))
        .isEmpty();
    org.assertj.core.api.Assertions.assertThat(results.getFirst().authors())
        .containsExactly("Suzanne Collins");
    org.assertj.core.api.Assertions.assertThat(
            repository.findBooksByTitle("Suzanne Collins", null, 6))
        .isEmpty();
    org.assertj.core.api.Assertions.assertThat(repository.findBooksByTitle("\" OR *:*", null, 6))
        .isEmpty();
  }

  @Test
  void embeddingMaintenanceJobWritesVectorsWhilePreservingSummariesAndCustomFields()
      throws Exception {
    String collection = SolrContainerTestSupport.COLLECTION;
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void embeddingUpdatesRejectRecordsDeletedOrEditedAfterReading(boolean deleted) throws Exception {
    String collection = SolrContainerTestSupport.COLLECTION;
    String id = deleted ? "raced-deletion" : "raced-edit";
    var original = new org.apache.solr.common.SolrInputDocument();
    original.addField("id", id);
    original.addField("title", "Original");
    original.addField("description", "Old description");
    solrClient.add(collection, original);
    for (var entry :
        java.util.Map.of("initials-forward", "J. K. Rowling", "initials-comma", "Rowling, J. K.")
            .entrySet()) {
      var document = new org.apache.solr.common.SolrInputDocument();
      document.setField("id", entry.getKey());
      document.setField("source", "initials-test");
      document.setField("authors", java.util.List.of(entry.getValue()));
      document.setField("narrators", java.util.List.of(entry.getValue()));
      solrClient.add(collection, document);
    }
    solrClient.commit(collection);
    var repository = new SolrAudiobookRepository(solrClient, collection);
    var store = new com.gen3.embeddingjob.store.SolrEmbeddingStore(repository);
    var source =
        store.embeddingPage(null, 100).books().stream()
            .filter(book -> id.equals(book.record().id()))
            .findFirst()
            .orElseThrow();
    if (deleted) {
      solrClient.deleteById(collection, id);
    } else {
      var edit = new org.apache.solr.common.SolrInputDocument();
      edit.addField("id", id);
      edit.addField("description", java.util.Map.of("set", "New description"));
      solrClient.add(collection, edit);
    }
    solrClient.commit(collection);
    float[] vector = new float[1536];
    vector[0] = 1;
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                store.updateEmbeddings(
                    java.util.List.of(
                        new com.gen3.embeddingjob.store.AudiobookEmbeddingStore.Update(
                            source.storageReference(),
                            new com.gen3.catalogue.model.AudiobookEmbedding(
                                source.record(), vector)))))
        .isInstanceOf(java.io.IOException.class);
    var current = solrClient.getById(collection, id);
    if (deleted) {
      org.junit.jupiter.api.Assertions.assertNull(current);
    } else {
      assertEquals("New description", current.getFieldValue("description"));
      org.assertj.core.api.Assertions.assertThat(current.getFieldValue("embedding")).isNull();
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
