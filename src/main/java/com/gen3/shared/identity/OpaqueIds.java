package com.gen3.shared.identity;

/** Shared boundary rule for caller-supplied user and session identifiers. */
public final class OpaqueIds {
  private OpaqueIds() {}

  public static boolean isValid(String value) {
    return value != null && !value.isBlank() && value.length() <= 256;
  }
}
