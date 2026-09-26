package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCatalogueRepository;
import java.io.IOException;
import java.util.List;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/books")
@ConditionalOnExpression(
    "'${audiobook.candidate-retriever:qdrant}' == 'solr' || '${audiobook.qdrant.migration.enabled:false}' == 'true'")
public class BookSearchController {

  private static final int MAX_LIMIT = 20;

  private final AudiobookCatalogueRepository audiobookRepository;

  public BookSearchController(
      @Qualifier("solrAudiobookRepository") AudiobookCatalogueRepository audiobookRepository) {
    this.audiobookRepository = audiobookRepository;
  }

  @GetMapping("/search")
  public BookSearchResponse search(
      @RequestParam("q") String query,
      @RequestParam(value = "limit", defaultValue = "5") int limit) {
    String normalizedQuery = query == null ? "" : query.trim();
    if (normalizedQuery.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Query parameter 'q' must not be blank");
    }

    int normalizedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

    try {
      var response =
          audiobookRepository.searchBooks(
              ClientUtils.escapeQueryChars(normalizedQuery), normalizedLimit);

      List<BookSearchResult> results = response.records().stream().map(this::toResult).toList();

      return new BookSearchResponse(response.totalItems(), results);
    } catch (IOException exception) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Book catalogue is temporarily unavailable", exception);
    }
  }

  private BookSearchResult toResult(AudiobookRecord record) {
    return new BookSearchResult(
        record.id(),
        record.source(),
        record.title(),
        record.authors(),
        record.description(),
        record.narrators(),
        record.language(),
        record.durationMinutes());
  }
}
