package com.gen3.recommenderagent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.ranker.AudiobookRecord;
import com.gen3.recommenderagent.ranker.AudiobookRepository;
import com.gen3.recommenderagent.ranker.AudiobookSearchPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookSearchControllerTest {

  private final AudiobookRepository repository = mock(AudiobookRepository.class);
  private final BookSearchController controller = new BookSearchController(repository);

  @Test
  void shouldMapSolrResponseAndClampLimit() throws Exception {
    AudiobookRecord record =
        new AudiobookRecord(
            "rnib_709742",
            "rnib",
            "Curse of the Mystery Mutt",
            List.of("Steven Butler"),
            "A mystery story",
            4.25);
    when(repository.searchBooks("mystery", 20))
        .thenReturn(new AudiobookSearchPage(42, List.of(record)));

    BookSearchResponse response = controller.search("  mystery  ", 99);

    assertEquals(42, response.totalItems());
    assertEquals(1, response.results().size());
    assertEquals("rnib_709742", response.results().getFirst().id());
    assertEquals(List.of("Steven Butler"), response.results().getFirst().authors());
    verify(repository).searchBooks("mystery", 20);
  }

  @Test
  void shouldRejectBlankQuery() {
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> controller.search("   ", 5));
  }

  @Test
  void shouldExposeMinimalHttpApi() throws Exception {
    when(repository.searchBooks("mystery", 5)).thenReturn(new AudiobookSearchPage(0, List.of()));

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
