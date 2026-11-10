package com.gen3.recommenderagent.domain.recommendation;

/** Shared recommendation limits and requested-count rules. */
public final class RecommendationPolicy {
  public static final int MAX_BOOK_COUNT = 5;
  public static final int MAX_SHOWN_BOOKS = 500;
  public static final int RECOMMENDATION_BATCH_SIZE = 20;

  /** Prevents instantiation of this utility class. */
  private RecommendationPolicy() {}

  /** Bounds a requested recommendation count to the supported range of one to five. */
  public static int clamp(int count) {
    return Math.clamp(count, 1, MAX_BOOK_COUNT);
  }

  /** Uses the default page count for a missing value and bounds supplied counts. */
  public static int clampOrDefault(Integer count) {
    return count == null ? MAX_BOOK_COUNT : clamp(count);
  }
}
