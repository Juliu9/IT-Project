package com.gen3.shared.readhistory.port;

import java.io.IOException;
import java.util.List;

/** Additive writes are separate from the recommendation server's read-only contract. */
public interface ReadHistoryWriter {
  /** Adds user/book pairs to read history without duplicating existing pairs. */
  void addReadBooks(List<ReadBook> books) throws IOException;

  record ReadBook(String userId, String bookId) {}
}
