package com.gen3.catalogue.model;

import java.util.List;
import java.util.Locale;

/** Minimal catalogue metadata for resolving a named reference without downloading vectors. */
public record AudiobookTitle(String bookId, String title, List<String> authors) {
  public AudiobookTitle {
    authors = authors == null ? List.of() : List.copyOf(authors);
  }

  public static String normalize(String text) {
    return text == null
        ? ""
        : text.replace("$b", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .strip();
  }
}
