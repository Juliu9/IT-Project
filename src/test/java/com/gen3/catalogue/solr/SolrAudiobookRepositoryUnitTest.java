package com.gen3.catalogue.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SolrAudiobookRepositoryUnitTest {

  @Test
  void migrationUsesSameNamedSourceFieldsWithoutLegacyFallbacks() throws Exception {
    var client = mock(SolrClient.class);
    var response = mock(QueryResponse.class);
    var documents = new SolrDocumentList();
    var old = new SolrDocument();
    old.setField("id", "old");
    old.setField("durationMinutes", 99);
    old.setField("publicationYear", 2013);
    old.setField("pubdate", "not-a-date");
    documents.add(old);
    var current = new SolrDocument();
    current.setField("id", "current");
    current.setField("duration_minutes", 120);
    current.setField(
        "pubdate", java.util.Date.from(java.time.Instant.parse("2013-07-03T00:00:00Z")));
    documents.add(current);
    when(response.getResults()).thenReturn(documents);
    when(client.query(
            org.mockito.ArgumentMatchers.eq("books"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
    var rows = new SolrAudiobookRepository(client, "books").findStoredBooks(null, 10);
    assertEquals(null, rows.getFirst().record().durationMinutes());
    assertEquals(null, rows.getFirst().record().pubdate());
    assertEquals(null, rows.getFirst().record().publicationYear());
    assertEquals(120, rows.getLast().record().durationMinutes());
    assertEquals("2013-07-03T00:00:00Z", rows.getLast().record().pubdate());
  }

  @Test
  void missingVectorScanKeepsReadPagesBoundedAndResumesAfterTheLastSelectedBook() throws Exception {
    var client = mock(SolrClient.class);
    var responses = new java.util.ArrayList<QueryResponse>();
    for (var ids :
        List.of(List.of("a", "b"), List.of("c", "d"), List.of("d", "e"), List.<String>of())) {
      var documents = new SolrDocumentList();
      for (String id : ids) {
        var document = new SolrDocument();
        document.setField("id", id);
        document.setField("_version_", 42L);
        if (id.equals("a") || id.equals("e")) {
          document.setField("custom_vector", List.of(1f, 0f));
        }
        documents.add(document);
      }
      var response = mock(QueryResponse.class);
      when(response.getResults()).thenReturn(documents);
      responses.add(response);
    }
    when(client.query(
            org.mockito.ArgumentMatchers.eq("books"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(responses.get(0), responses.get(1), responses.get(2), responses.get(3));
    var store =
        new com.gen3.embeddingjob.store.SolrEmbeddingStore(
            new SolrAudiobookRepository(client, "books", "custom_vector", 2));
    var first = store.embeddingPage(null, 2, com.gen3.embeddingjob.EmbeddingMode.MISSING_ONLY);
    org.assertj.core.api.Assertions.assertThat(first.books())
        .extracting(book -> book.record().id())
        .containsExactly("b", "c");
    assertEquals("c", first.nextCursor());
    var second =
        store.embeddingPage(
            first.nextCursor(), 2, com.gen3.embeddingjob.EmbeddingMode.MISSING_ONLY);
    org.assertj.core.api.Assertions.assertThat(second.books())
        .extracting(book -> book.record().id())
        .containsExactly("d");
    org.junit.jupiter.api.Assertions.assertNull(second.nextCursor());
    var queries = ArgumentCaptor.forClass(SolrQuery.class);
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(4))
        .query(
            org.mockito.ArgumentMatchers.eq("books"),
            queries.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    org.assertj.core.api.Assertions.assertThat(queries.getAllValues())
        .allSatisfy(
            query -> {
              assertEquals(Integer.valueOf(2), query.getRows());
              org.assertj.core.api.Assertions.assertThat(query.getFields())
                  .contains("custom_vector", "_version_");
            });
  }

  @Test
  void referenceLookupUsesTheSharedAuthorVariants() throws Exception {
    var client = mock(SolrClient.class);
    var response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(new SolrDocumentList());
    when(client.query(
            org.mockito.ArgumentMatchers.eq("books"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
    new SolrAudiobookRepository(client, "books")
        .findBooksByTitle("The Hunger Games", "Suzanne Collins", 6);
    var captured = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client)
        .query(
            org.mockito.ArgumentMatchers.eq("books"),
            captured.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    org.assertj.core.api.Assertions.assertThat(captured.getValue().getFilterQueries()).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(captured.getValue().getFilterQueries()[0])
        .contains("Suzanne\\ Collins", "Collins,\\ Suzanne", "Collins,\\ Suzanne.");
    assertEquals(Integer.valueOf(100), captured.getValue().getRows());
  }

  @Test
  void personFiltersIncludeAndExcludeBothNameOrders() throws Exception {
    var client = mock(SolrClient.class);
    var response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(new SolrDocumentList());
    when(client.query(
            org.mockito.ArgumentMatchers.eq("books"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
    var conditions =
        new AudiobookFilterConditions(
            List.of("Suzanne Collins"),
            List.of("Fry, Stephen"),
            null,
            null,
            null,
            null,
            null,
            null);
    new SolrAudiobookRepository(client, "books")
        .searchKeyword("fantasy", new AudiobookFilters(conditions, conditions, List.of()), 5);
    var captured = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client)
        .query(
            org.mockito.ArgumentMatchers.eq("books"),
            captured.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    assertEquals(
        List.of(
            "authors:(\"Suzanne\\ Collins\" OR \"Collins,\\ Suzanne\" OR \"Suzanne\\ Collins.\" OR \"Collins,\\ Suzanne.\")",
            "narrators:(\"Fry,\\ Stephen\" OR \"Stephen\\ Fry\" OR \"Fry,\\ Stephen.\" OR \"Stephen\\ Fry.\")",
            "-authors:(\"Suzanne\\ Collins\" OR \"Collins,\\ Suzanne\" OR \"Suzanne\\ Collins.\" OR \"Collins,\\ Suzanne.\")",
            "-narrators:(\"Fry,\\ Stephen\" OR \"Stephen\\ Fry\" OR \"Fry,\\ Stephen.\" OR \"Stephen\\ Fry.\")"),
        java.util.Arrays.asList(captured.getValue().getFilterQueries()));
    assertEquals(List.of("Suzanne Collins"), conditions.authors());
  }

  @Test
  void vectorLookupBatchesExactIdsAndSkipsMissingVectors() throws Exception {
    var client = mock(SolrClient.class);
    var response = new QueryResponse();
    var docs = new SolrDocumentList();
    var document = new SolrDocument();
    document.setField("id", "book-1");
    document.setField("custom_vector", List.of(3f, 4f));
    docs.add(document);
    var missing = new SolrDocument();
    missing.setField("id", "missing-vector");
    docs.add(missing);
    var results = new org.apache.solr.common.util.NamedList<Object>();
    results.add("response", docs);
    response.setResponse(results);
    when(client.query(
            org.mockito.ArgumentMatchers.eq("books"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
    var repository = new SolrAudiobookRepository(client, "books", "custom_vector", 2);
    var ids =
        new java.util.ArrayList<>(
            java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> "book-" + i).toList());
    ids.add("special:+ \"id\"");
    ids.add("book-1");
    ids.add("");
    var vectors = repository.findEmbeddingsByBookIds(ids);
    org.assertj.core.api.Assertions.assertThat(vectors).containsOnlyKeys("book-1");
    org.assertj.core.api.Assertions.assertThat(vectors.get("book-1")).containsExactly(3, 4);
    var queries = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client, org.mockito.Mockito.times(2))
        .query(
            org.mockito.ArgumentMatchers.eq("books"),
            queries.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    assertEquals(List.of(100, 2), queries.getAllValues().stream().map(SolrQuery::getRows).toList());
    assertEquals("id,custom_vector", queries.getValue().getFields());
    org.assertj.core.api.Assertions.assertThat(queries.getValue().getFilterQueries()[0])
        .contains("special\\:");
  }

  @Test
  void blankVectorLookupsDoNotQueryTheWholeCatalogue() throws Exception {
    var client = mock(SolrClient.class);
    var repository = new SolrAudiobookRepository(client, "books", "embedding", 2);
    org.assertj.core.api.Assertions.assertThat(repository.findEmbeddingByBookId(null)).isEmpty();
    org.assertj.core.api.Assertions.assertThat(
            repository.findEmbeddingsByBookIds(java.util.Arrays.asList(null, " ")))
        .isEmpty();
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  void embeddingJobUsesAtomicVectorUpdatesWithoutReplacingMetadata() throws Exception {
    SolrClient client = mock(SolrClient.class);
    var repository = new SolrAudiobookRepository(client, "books", "embedding", 2);
    var record =
        new com.gen3.catalogue.model.AudiobookRecord(
            "book", "s", "Title", List.of(), "Description");
    new com.gen3.embeddingjob.store.SolrEmbeddingStore(repository)
        .updateEmbeddings(
            List.of(
                new com.gen3.embeddingjob.store.AudiobookEmbeddingStore.Update(
                    "123",
                    new com.gen3.catalogue.model.AudiobookEmbedding(
                        record, new float[] {0.6f, 0.8f}))));
    var documents = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client).add(org.mockito.ArgumentMatchers.eq("books"), documents.capture());
    var document =
        (org.apache.solr.common.SolrInputDocument) documents.getValue().iterator().next();
    assertEquals(
        java.util.Set.of("id", "embedding", "_version_"),
        document.getFieldNames().stream().collect(java.util.stream.Collectors.toSet()));
    assertEquals(123L, document.getFieldValue("_version_"));
    assertEquals(java.util.Map.of("set", List.of(0.6f, 0.8f)), document.getFieldValue("embedding"));
    verify(client).commit("books");
  }

  @Test
  void readsExistingVectorsAndSummariesIncludingRecordsWithNoVector() throws Exception {
    SolrClient client = mock(SolrClient.class);
    QueryResponse response = mock(QueryResponse.class);
    SolrDocument first = new SolrDocument();
    first.setField("id", "one");
    first.setField("title", "Title");
    first.setField("short_description", "Stored summary");
    first.setField("stored_vector", List.of(2.0f, 3.0f));
    SolrDocument second = new SolrDocument();
    second.setField("id", "two");
    SolrDocumentList documents = new SolrDocumentList();
    documents.add(first);
    documents.add(second);
    when(response.getResults()).thenReturn(documents);
    when(client.query(
            org.mockito.ArgumentMatchers.eq("books"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
    var records =
        new SolrAudiobookRepository(client, "books", "stored_vector", 2).findStoredBooks(null, 2);
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new float[] {2, 3}, records.getFirst().vector());
    assertEquals("Stored summary", records.getFirst().record().shortDescription());
    org.junit.jupiter.api.Assertions.assertNull(records.getLast().vector());
    var query = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client)
        .query(
            org.mockito.ArgumentMatchers.eq("books"),
            query.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    org.junit.jupiter.api.Assertions.assertTrue(
        query.getValue().getFields().contains("stored_vector"));
  }

  @Test
  void shouldQueryConfiguredCollectionAndOnlyRequestApiFields() throws Exception {
    SolrClient client = mock(SolrClient.class);
    QueryResponse expected = new QueryResponse();
    when(client.query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(expected);

    SolrAudiobookRepository repository = new SolrAudiobookRepository(client, "combinedbooks");

    QueryResponse actual = repository.search("mystery", 5);

    ArgumentCaptor<SolrQuery> queryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client)
        .query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            queryCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));

    SolrQuery query = queryCaptor.getValue();
    assertSame(expected, actual);
    assertEquals("mystery", query.getQuery());
    assertEquals("edismax", query.get("defType"));
    assertEquals("all", query.get("qf"));
    assertEquals("all", query.get("uf"));
    assertEquals(5, query.getRows());
    assertEquals(
        "id,source,title,authors,description,narrators,language,duration_minutes,pubdate,short_description,score,embedding",
        query.getFields());
  }

  /** The public repository boundary exposes plain records rather than Solr documents. */
  @Test
  void shouldMapSearchResultsToAudiobookRecords() throws Exception {
    SolrClient client = mock(SolrClient.class);
    QueryResponse response = mock(QueryResponse.class);
    SolrDocument document = new SolrDocument();
    document.setField("id", "book-1");
    document.setField("authors", java.util.List.of("Author One", "Author Two"));
    document.setField("pubdate", "2024-07-03");
    document.setField("short_description", "A brief description.");
    document.setField("score", 2.5f);
    SolrDocumentList documents = new SolrDocumentList();
    documents.setNumFound(7);
    documents.add(document);
    when(response.getResults()).thenReturn(documents);
    when(client.query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);

    var candidates =
        new SolrAudiobookRepository(client, "combinedbooks").searchKeyword("mystery", null, 5);
    var record = candidates.getFirst().audiobook();

    assertEquals("book-1", record.id());
    assertEquals(java.util.List.of("Author One", "Author Two"), record.authors());
    assertEquals(2024, record.publicationYear());
    assertEquals("A brief description.", record.shortDescription());
  }

  @Test
  void shouldBlendLexicalAndVectorScores() throws Exception {
    SolrClient client = mock(SolrClient.class);
    QueryResponse keywordResponse = mock(QueryResponse.class);
    SolrDocumentList keywordDocuments = new SolrDocumentList();
    keywordDocuments.setNumFound(2);
    keywordDocuments.add(document("book-1", 10.0, "Keyword match"));
    keywordDocuments.add(document("book-2", 5.0, "Semantic match"));
    when(keywordResponse.getResults()).thenReturn(keywordDocuments);

    QueryResponse vectorResponse = mock(QueryResponse.class);
    SolrDocumentList vectorDocuments = new SolrDocumentList();
    vectorDocuments.setNumFound(2);
    vectorDocuments.add(document("book-2", 0.9, "Semantic match"));
    vectorDocuments.add(document("book-3", 0.5, "Vector only"));
    when(vectorResponse.getResults()).thenReturn(vectorDocuments);

    when(client.query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(keywordResponse, vectorResponse);

    List<AudiobookCandidate> candidates =
        new SolrAudiobookRepository(client, "combinedbooks", "embedding", 2)
            .searchHybrid(new float[] {0.6f, 0.8f}, "mystery", AudiobookFilters.empty(), 2);

    assertEquals(
        List.of("book-2", "book-1"),
        candidates.stream().map(candidate -> candidate.audiobook().id()).toList());
    assertEquals(0.8, candidates.getFirst().score());

    ArgumentCaptor<SolrQuery> queryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client, org.mockito.Mockito.times(2))
        .query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            queryCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    assertEquals(
        "{!knn f=embedding topK=4}[0.6,0.8]", queryCaptor.getAllValues().get(1).getQuery());
  }

  @Test
  void shouldApplyIncludeAndExcludeSourceFilters() throws Exception {
    SolrClient client = mock(SolrClient.class);
    QueryResponse response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(new SolrDocumentList());
    when(client.query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            org.mockito.ArgumentMatchers.any(SolrQuery.class),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
    AudiobookFilters filters =
        new AudiobookFilters(
            new AudiobookFilterConditions(
                List.of(), List.of(), "English", "NLS", null, 600, 2020, null),
            new AudiobookFilterConditions(
                List.of(), List.of(), "French", "Legacy", null, null, null, 2000),
            List.of("shown-1", "shown-2"));

    new SolrAudiobookRepository(client, "combinedbooks").searchKeyword("mystery", filters, 5);

    ArgumentCaptor<SolrQuery> queryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client)
        .query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            queryCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));
    assertEquals(
        List.of(
            "language:\"English\"",
            "source:\"NLS\"",
            "duration_minutes:[* TO 600]",
            "pubdate:[2020-01-01T00:00:00Z TO *]",
            "-language:\"French\"",
            "-source:\"Legacy\"",
            "-pubdate:[* TO 2000-12-31T23:59:59.999Z]",
            "-id:(\"shown\\-1\" OR \"shown\\-2\")"),
        List.of(queryCaptor.getValue().getFilterQueries()));
  }

  private SolrDocument document(String id, double score, String title) {
    SolrDocument document = new SolrDocument();
    document.setField("id", id);
    document.setField("score", score);
    document.setField("title", title);
    return document;
  }
}
