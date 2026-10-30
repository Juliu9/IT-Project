package com.gen3.recommenderagent.testsupport;

/** Explicit full dates for fixtures whose assertions exercise year-based policy. */
public final class CatalogueDates {
  private CatalogueDates() {}

  public static String atYear(Integer year) {
    return year == null ? null : String.format(java.util.Locale.ROOT, "%04d-01-01", year);
  }
}
