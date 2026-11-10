package com.gen3.shared.audiobookcatalogue.model;

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

  /** Returns an instance with no available values or evidence. */
  public static AudiobookFilterConditions empty() {
    return new AudiobookFilterConditions(List.of(), List.of(), null, null, null, null, null, null);
  }

  /** Reports whether any exact inclusion, exclusion, or range bounds are present. */
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
