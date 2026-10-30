package com.gen3.recommenderagent.inputparser;

import java.util.List;

/**
 * Small, user-visible facts that may disambiguate a parser result.
 *
 * <p>This deliberately excludes raw turns, embeddings, and Redis session serialisation. It is
 * reference data only: the resolver remains the authority for what the request is allowed to do.
 */
public record SessionContext(
    String pendingQuestion, String lastAction, List<DisplayedBook> displayedBooks) {
  public record DisplayedBook(Integer rank, String title) {}

  public static final SessionContext EMPTY = new SessionContext(null, null, List.of());

  public SessionContext {
    displayedBooks = displayedBooks == null ? List.of() : List.copyOf(displayedBooks);
  }

  public boolean isEmpty() {
    return pendingQuestion == null && lastAction == null && displayedBooks.isEmpty();
  }
}
