package com.gen3.shortdescriptionjob;

/** Whether to fill missing summaries or deliberately replace existing ones. */
public enum ShortDescriptionMode {
  MISSING_ONLY,
  REBUILD_ALL;

  /** Resolves the summary-generation mode and rejects unsupported configuration values. */
  public static ShortDescriptionMode parse(String value) {
    if ("missing-only".equals(value)) {
      return MISSING_ONLY;
    }
    if ("rebuild-all".equals(value)) {
      return REBUILD_ALL;
    }
    throw new IllegalArgumentException(
        "audiobook.short-description.mode must be missing-only or rebuild-all");
  }
}
