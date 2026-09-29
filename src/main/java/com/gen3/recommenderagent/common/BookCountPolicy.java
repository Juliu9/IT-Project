package com.gen3.recommenderagent.common;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;

/** Applies the shared default and bounds for requested recommendation counts. */
public final class BookCountPolicy {

  private BookCountPolicy() {}

  public static int clamp(int count) {
    return Math.clamp(count, 1, MAX_BOOK_COUNT);
  }

  public static int clampOrDefault(Integer count) {
    return count == null ? MAX_BOOK_COUNT : clamp(count);
  }
}
