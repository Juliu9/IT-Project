package com.gen3.embeddingjob;

/** Whether to fill absent dense vectors or deliberately regenerate existing ones. */
public enum EmbeddingMode {
  MISSING_ONLY,
  REBUILD_ALL;

  public static EmbeddingMode parse(String value) {
    if ("missing-only".equals(value)) {
      return MISSING_ONLY;
    }
    if ("rebuild-all".equals(value)) {
      return REBUILD_ALL;
    }
    throw new IllegalArgumentException(
        "audiobook.embedding-index.mode must be missing-only or rebuild-all");
  }
}
