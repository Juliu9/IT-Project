package com.gen3.shared.readhistory.postgres;

import com.gen3.shared.identity.OpaqueIds;
import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import com.gen3.shared.readhistory.port.ReadHistoryWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/** Indexed, bounded reads and idempotent batched inserts; never creates or alters schemas. */
public final class PostgresReadHistoryRepository
    implements ReadHistoryRepository, ReadHistoryWriter {
  private final DataSource dataSource;

  /** Creates the history reader and writer with the explicit PostgreSQL data source. */
  public PostgresReadHistoryRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** {@inheritDoc} */
  @Override
  public Set<String> findReadBookIds(String userId, int limit) throws IOException {
    if (!OpaqueIds.isValid(userId) || limit < 1) {
      throw new IllegalArgumentException("Valid user ID and positive history limit required");
    }
    try (var connection = dataSource.getConnection();
        var query =
            connection.prepareStatement(
                "SELECT book_id FROM read_history WHERE user_id = ? LIMIT ?")) {
      query.setQueryTimeout(5);
      query.setString(1, userId);
      query.setInt(2, limit);
      try (var result = query.executeQuery()) {
        Set<String> ids = new HashSet<>();
        while (result.next()) {
          ids.add(result.getString(1));
        }
        return Set.copyOf(ids);
      }
    } catch (SQLException exception) {
      throw new IOException("Read-history lookup failed", exception);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void addReadBooks(List<ReadBook> books) throws IOException {
    for (var book : books) {
      if (!OpaqueIds.isValid(book.userId()) || book.bookId() == null || book.bookId().isBlank()) {
        throw new IllegalArgumentException("Valid user and catalogue book IDs required");
      }
    }
    if (books.isEmpty()) {
      return;
    }
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO read_history (user_id, book_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
        insert.setQueryTimeout(60);
        for (var book : books) {
          insert.setString(1, book.userId());
          insert.setString(2, book.bookId());
          insert.addBatch();
        }
        insert.executeBatch();
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IOException(
          "Read-history batch failed; previous batches may remain committed", exception);
    }
  }
}
