package com.gen3.catalogue.model;

import java.util.ArrayList;
import java.util.List;

/** Keeps bounded choices while adapters scan past partial matches to discover exact titles. */
public final class AudiobookTitleMatches {
  private final String title;
  private final String author;
  private final int limit;
  private final List<AudiobookTitle> exact = new ArrayList<>();
  private final List<AudiobookTitle> partial = new ArrayList<>();

  public AudiobookTitleMatches(String title, String author, int limit) {
    this.title = AudiobookTitle.normalize(title);
    this.author = PersonNameNormalizer.comparisonKey(author);
    this.limit = limit;
  }

  public void add(AudiobookTitle book) {
    if (!author.isBlank()
        && book.authors().stream()
            .noneMatch(name -> PersonNameNormalizer.comparisonKey(name).equals(author))) {
      return;
    }
    var target = AudiobookTitle.normalize(book.title()).equals(title) ? exact : partial;
    if (target.size() < limit && !target.contains(book)) {
      target.add(book);
    }
  }

  public boolean full() {
    return exact.size() == limit;
  }

  public List<AudiobookTitle> results() {
    return List.copyOf(exact.isEmpty() ? partial : exact);
  }
}
