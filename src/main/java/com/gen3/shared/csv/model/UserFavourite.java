package com.gen3.shared.csv.model;

public record UserFavourite(String userId, String bookId) {

  /** Creates a favourite interaction and rejects blank user or book IDs. */
  public UserFavourite {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("User ID must not be blank");
    }
    if (bookId == null || bookId.isBlank()) {
      throw new IllegalArgumentException("Book ID must not be blank");
    }
  }
}
