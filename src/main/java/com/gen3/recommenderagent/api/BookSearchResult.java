package com.gen3.recommenderagent.api;

import java.util.List;

/** Public catalogue result including the metadata used by Qdrant filters. */
public record BookSearchResult(
    String id,
    String source,
    String title,
    List<String> authors,
    String description,
    List<String> narrators,
    String language,
    Integer durationMinutes) {}
