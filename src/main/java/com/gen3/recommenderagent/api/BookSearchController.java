package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.ranker.SolrAudiobookRepository;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/v1/books")
public class BookSearchController {

    private static final int MAX_LIMIT = 20;

    private final SolrAudiobookRepository audiobookRepository;

    public BookSearchController(SolrAudiobookRepository audiobookRepository) {
        this.audiobookRepository = audiobookRepository;
    }

    @GetMapping("/search")
    public BookSearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit
    ) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Query parameter 'q' must not be blank"
            );
        }

        int normalizedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        try {
            QueryResponse response = audiobookRepository.search(
                    ClientUtils.escapeQueryChars(normalizedQuery),
                    normalizedLimit
            );

            List<BookSearchResult> results = response.getResults().stream()
                    .map(this::toResult)
                    .toList();

            return new BookSearchResponse(
                    response.getResults().getNumFound(),
                    results
            );
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Book catalogue is temporarily unavailable",
                    exception
            );
        }
    }

    private BookSearchResult toResult(SolrDocument document) {
        return new BookSearchResult(
                stringValue(document, "id"),
                stringValue(document, "source"),
                stringValue(document, "title"),
                stringListValue(document.getFieldValue("authors")),
                stringValue(document, "description"),
                numberValue(document.getFieldValue("score"))
        );
    }

    private String stringValue(SolrDocument document, String field) {
        Object value = document.getFieldValue(field);
        return value == null ? null : value.toString();
    }

    private List<String> stringListValue(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(Object::toString).toList();
        }
        return List.of(value.toString());
    }

    private Double numberValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
