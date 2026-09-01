package com.gen3.recommenderagent.api;

import java.util.List;

public record BookSearchResponse(
        long totalItems,
        List<BookSearchResult> results
) {
}
