package com.gen3.shared.identity;

/** Shared boundary rule for caller-supplied user and session identifiers. */
public final class OpaqueIds {
  /** Prevents instantiation of this utility class. */
  private OpaqueIds() {}

  /** Checks that an opaque user or session ID is nonblank and within the supported length limit. */
  public static boolean isValid(String value) {
    return value != null && !value.isBlank() && value.length() <= 256;
  }
}
