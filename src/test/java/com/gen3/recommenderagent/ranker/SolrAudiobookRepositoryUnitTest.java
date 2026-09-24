package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookSearchPage;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
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
    assertEquals(
        "id,source,title,authors,description,narrators,language,durationMinutes,score",
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

    AudiobookSearchPage page =
        new SolrAudiobookRepository(client, "combinedbooks").searchBooks("mystery", 5);

    assertEquals(7, page.totalItems());
    assertEquals("book-1", page.records().getFirst().id());
    assertEquals(
        java.util.List.of("Author One", "Author Two"), page.records().getFirst().authors());
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
            .searchCandidates("mystery", 2, new float[] {0.6f, 0.8f});

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

  private SolrDocument document(String id, double score, String title) {
    SolrDocument document = new SolrDocument();
    document.setField("id", id);
    document.setField("score", score);
    document.setField("title", title);
    return document;
  }
}
