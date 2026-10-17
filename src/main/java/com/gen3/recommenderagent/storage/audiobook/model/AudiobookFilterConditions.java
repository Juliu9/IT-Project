package com.gen3.recommenderagent.storage.audiobook.model;

import java.util.List;

/** One group of exact catalogue conditions for a storage query. */
public record AudiobookFilterConditions(
    List<String> authors,
    List<String> narrators,
    String language,
    String source,
    Integer minimumDurationMinutes,
    Integer maximumDurationMinutes,
    Integer minimumPublicationYear,
    Integer maximumPublicationYear) {

  public static AudiobookFilterConditions empty() {
    return new AudiobookFilterConditions(List.of(), List.of(), null, null, null, null, null, null);
  }

  public boolean hasConditions() {
    return (authors != null && !authors.isEmpty())
        || (narrators != null && !narrators.isEmpty())
        || (language != null && !language.isBlank())
        || (source != null && !source.isBlank())
        || minimumDurationMinutes != null
        || maximumDurationMinutes != null
        || minimumPublicationYear != null
        || maximumPublicationYear != null;
  }
}
