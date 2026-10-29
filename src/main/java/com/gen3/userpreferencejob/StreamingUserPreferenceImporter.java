package com.gen3.userpreferencejob;

import com.gen3.maintenance.JobProgress;
import com.gen3.userpreferencejob.UserPreferenceExternalSort.Row;
import com.gen3.userpreferencejob.model.UserFavourite;
import com.gen3.userpreferencejob.model.UserHistory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Parses and externally sorts input, validates all users, then aggregates bounded whole-user
 * batches.
 */
@Service
public class StreamingUserPreferenceImporter {
  private final UserFavouriteCsvReader favourites;
  private final UserHistoryCsvReader history;
  private final UserPreferenceAggregatorService aggregator;
  private final Path workDirectory;
  private final int maximumRows;
  private final int maximumBooks;

  public StreamingUserPreferenceImporter(
      UserFavouriteCsvReader favourites,
      UserHistoryCsvReader history,
      UserPreferenceAggregatorService aggregator,
      @Value("${user.preferences.import.work-directory:./data/user-import-work}")
          Path workDirectory,
      @Value("${user.preferences.import.max-records-in-memory:100000}") int maximumRows,
      @Value("${user.preferences.import.max-book-ids-per-group:100000}") int maximumBooks) {
    if (maximumRows < 1 || maximumBooks < 1) {
      throw new IllegalArgumentException("Streaming import limits must be positive");
    }
    this.favourites = favourites;
    this.history = history;
    this.aggregator = aggregator;
    this.workDirectory = workDirectory;
    this.maximumRows = maximumRows;
    this.maximumBooks = maximumBooks;
  }

  public Result run(Path favouritesCsv, Path historyCsv) throws IOException {
    try (var progress = new JobProgress("user.import.stream");
        var sort = new UserPreferenceExternalSort(workDirectory, maximumRows)) {
      long[] counts = new long[2];
      long started = System.nanoTime();
      progress.stage("reading and spilling favourites CSV");
      favourites.read(
          favouritesCsv,
          record -> {
            sort.add(new Row(record.userId(), record.bookId(), false, 0));
            if (++counts[0] % 10000 == 0) {
              progress.update(counts[0], -1);
            }
          });
      progress.stage("reading and spilling history CSV");
      history.read(
          historyCsv,
          record -> {
            sort.add(
                new Row(
                    record.userId(),
                    record.bookId(),
                    true,
                    record.lastInteractionTime().getEpochSecond()));
            if (++counts[1] % 10000 == 0) {
              progress.update(counts[0] + counts[1], -1);
            }
          });
      long csvReadMs = elapsedMs(started);
      started = System.nanoTime();
      progress.stage("merging sorted input runs");
      progress.update(0, -1);
      Path prepared = sort.finish(progress);
      Validation validation = validate(prepared, progress);
      long sortMs = elapsedMs(started);
      started = System.nanoTime();
      aggregate(prepared, validation.users(), progress);
      return new Result(
          counts[0],
          counts[1],
          validation.users(),
          validation.records(),
          csvReadMs,
          sortMs,
          elapsedMs(started));
    }
  }

  private Validation validate(Path path, JobProgress progress) throws IOException {
    progress.stage("validating complete user histories");
    progress.update(0, -1);
    long records = 0;
    long users = 0;
    String user = null;
    String book = null;
    int bookCount = 0;
    try (var reader = new UserPreferenceExternalSort.Reader(path)) {
      while (reader.advance()) {
        Row row = reader.current();
        if (!row.userId().equals(user)) {
          users++;
          user = row.userId();
          book = null;
          bookCount = 0;
        }
        if (!row.bookId().equals(book)) {
          book = row.bookId();
          if (++bookCount > maximumBooks) {
            throw new IllegalArgumentException(
                "User " + user + " exceeds configured distinct-book limit " + maximumBooks);
          }
        }
        if (++records % 10000 == 0) {
          progress.update(records, -1);
        }
      }
    }
    return new Validation(users, records);
  }

  private void aggregate(Path path, long totalUsers, JobProgress progress) throws IOException {
    var batchFavourites = new ArrayList<UserFavourite>();
    var batchHistory = new ArrayList<UserHistory>();
    var userFavourites = new ArrayList<UserFavourite>();
    var userHistory = new ArrayList<UserHistory>();
    Instant asOf = Instant.now();
    long processed = 0;
    String user = null;
    progress.stage("aggregating and saving user batches");
    progress.update(0, totalUsers);
    try (var reader = new UserPreferenceExternalSort.Reader(path)) {
      boolean available = reader.advance();
      while (available) {
        Row row = reader.current();
        user = row.userId();
        do {
          if (row.history()) {
            userHistory.add(
                new UserHistory(user, row.bookId(), Instant.ofEpochSecond(row.epochSecond())));
          } else {
            userFavourites.add(new UserFavourite(user, row.bookId()));
          }
          available = reader.advance();
          row = reader.current();
        } while (available && row.userId().equals(user));
        int size = batchFavourites.size() + batchHistory.size();
        if (size > 0 && (long) size + userFavourites.size() + userHistory.size() > maximumRows) {
          aggregator.aggregateAndSave(batchFavourites, batchHistory, asOf);
          batchFavourites.clear();
          batchHistory.clear();
        }
        batchFavourites.addAll(userFavourites);
        batchHistory.addAll(userHistory);
        userFavourites.clear();
        userHistory.clear();
        progress.update(++processed, totalUsers);
      }
    }
    if (!batchFavourites.isEmpty() || !batchHistory.isEmpty()) {
      aggregator.aggregateAndSave(batchFavourites, batchHistory, asOf);
    }
  }

  private static long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private record Validation(long users, long records) {}

  public record Result(
      long favourites,
      long history,
      long users,
      long uniqueRecords,
      long csvReadMs,
      long sortMs,
      long aggregationMs) {}
}
