package com.gen3.recommenderagent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.ranker.SolrAudiobookRepository;
import java.util.List;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.Test;

class BookSearchControllerTest {

  private final SolrAudiobookRepository repository = mock(SolrAudiobookRepository.class);
  private final BookSearchController controller = new BookSearchController(repository);

  @Test
  void shouldMapSolrResponseAndClampLimit() throws Exception {
    SolrDocument document = new SolrDocument();
    document.setField("id", "rnib_709742");
    document.setField("source", "rnib");
    document.setField("title", "Curse of the Mystery Mutt");
    document.setField("authors", List.of("Steven Butler"));
    document.setField("description", "A mystery story");
    document.setField("score", 4.25);

    SolrDocumentList documents = new SolrDocumentList();
    documents.setNumFound(42);
    documents.add(document);

    QueryResponse queryResponse = mock(QueryResponse.class);
    when(queryResponse.getResults()).thenReturn(documents);
    when(repository.search("mystery", 20)).thenReturn(queryResponse);

    BookSearchResponse response = controller.search("  mystery  ", 99);

    assertEquals(42, response.totalItems());
    assertEquals(1, response.results().size());
    assertEquals("rnib_709742", response.results().getFirst().id());
    assertEquals(List.of("Steven Butler"), response.results().getFirst().authors());
    verify(repository).search("mystery", 20);
  }

  @Test
  void shouldRejectBlankQuery() {
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> controller.search("   ", 5));
  }

  @Test
  void shouldExposeMinimalHttpApi() throws Exception {
    SolrDocumentList documents = new SolrDocumentList();
    documents.setNumFound(0);

    QueryResponse queryResponse = mock(QueryResponse.class);
    when(queryResponse.getResults()).thenReturn(documents);
    when(repository.search("mystery", 5)).thenReturn(queryResponse);

    org.springframework.test.web.servlet.MockMvc mockMvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .build();

    mockMvc
        .perform(get("/api/v1/books/search").queryParam("q", "mystery"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(0))
        .andExpect(jsonPath("$.results").isArray());
  }
}
