package com.gen3.recommenderagent.domain.recommendation;

/** Shared recommendation limits and requested-count rules. */
public final class RecommendationPolicy {
  public static final int MAX_BOOK_COUNT = 5;
  public static final int MAX_SHOWN_BOOKS = 500;
  public static final int RECOMMENDATION_BATCH_SIZE = 20;

  private RecommendationPolicy() {}

  public static int clamp(int count) {
    return Math.clamp(count, 1, MAX_BOOK_COUNT);
  }

  public static int clampOrDefault(Integer count) {
    return count == null ? MAX_BOOK_COUNT : clamp(count);
  }
}
