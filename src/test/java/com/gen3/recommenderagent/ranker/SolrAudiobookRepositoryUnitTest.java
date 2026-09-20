package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SolrAudiobookRepositoryUnitTest {

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
    assertEquals("title rt_title authors rt_authors all", query.get("qf"));
    assertEquals(5, query.getRows());
    assertEquals("id,source,title,authors,description,score", query.getFields());
  }

  @Test
  void shouldBuildKnnQueryFromVector() throws Exception {
    SolrClient client = mock(SolrClient.class);
    QueryResponse expected = new QueryResponse();
    when(client.query(
        org.mockito.ArgumentMatchers.eq("combinedbooks"),
        org.mockito.ArgumentMatchers.any(SolrQuery.class),
        org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST)))
        .thenReturn(expected);

    SolrAudiobookRepository repository = new SolrAudiobookRepository(client, "combinedbooks");

    QueryResponse actual = repository.searchByVector(new float[] { 0.1f, -0.2f }, 10, "book_vector");

    ArgumentCaptor<SolrQuery> queryCaptor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client)
        .query(
            org.mockito.ArgumentMatchers.eq("combinedbooks"),
            queryCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(SolrRequest.METHOD.POST));

    SolrQuery query = queryCaptor.getValue();
    assertSame(expected, actual);
    assertEquals("{!knn f=book_vector topK=10}[0.1,-0.2]", query.getQuery());
    assertEquals(10, query.getRows());
  }

  @Test
  void shouldAddEmbeddingAndCommitDocument() throws Exception {
    SolrClient client = mock(SolrClient.class);
    SolrAudiobookRepository repository = new SolrAudiobookRepository(client, "combinedbooks");
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", "book-1");
    float[] embedding = new float[] { 0.3f, 0.4f };

    repository.indexWithEmbedding(document, embedding, "book_vector");

    assertSame(embedding, document.getFieldValue("book_vector"));
    verify(client).add("combinedbooks", document);
    verify(client).commit("combinedbooks");
  }

  @Test
  void shouldRejectEmptyQueryVector() {
    SolrClient client = mock(SolrClient.class);
    SolrAudiobookRepository repository = new SolrAudiobookRepository(client, "combinedbooks");

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> repository.searchByVector(new float[0], 10, "book_vector"));
  }
}
