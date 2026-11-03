package com.gen3.userpreferencejob;

import com.gen3.shared.csv.UserFavouriteCsvReader;
import com.gen3.shared.csv.UserHistoryCsvReader;
import com.gen3.shared.csv.model.UserFavourite;
import com.gen3.shared.csv.model.UserHistory;
import com.gen3.shared.jobruntime.JobProgress;
import com.gen3.userpreferencejob.UserPreferenceExternalSort.Row;
import java.io.IOException;
import java.nio.file.Files;
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
  private final double maximumUnmappedUserRatio;
  private final boolean writeMode;

  public StreamingUserPreferenceImporter(
      UserFavouriteCsvReader favourites,
      UserHistoryCsvReader history,
      UserPreferenceAggregatorService aggregator,
      @Value("${user.preferences.import.work-directory:./data/user-import-work}")
          Path workDirectory,
      @Value("${user.preferences.import.max-records-in-memory:100000}") int maximumRows,
      @Value("${user.preferences.import.max-book-ids-per-group:100000}") int maximumBooks,
      @Value("${user.preferences.import.max-unmapped-user-ratio:0.10}")
          double maximumUnmappedUserRatio,
      @Value("${user.preferences.import.mode:dry-run}") String mode) {
    if (maximumRows < 1 || maximumBooks < 1) {
      throw new IllegalArgumentException("Streaming import limits must be positive");
    }
    if (maximumUnmappedUserRatio < 0 || maximumUnmappedUserRatio > 1) {
      throw new IllegalArgumentException("Unmapped-user ratio must be between 0 and 1");
    }
    this.favourites = favourites;
    this.history = history;
    this.aggregator = aggregator;
    this.workDirectory = workDirectory;
    this.maximumRows = maximumRows;
    this.maximumBooks = maximumBooks;
    this.maximumUnmappedUserRatio = maximumUnmappedUserRatio;
    this.writeMode = "write".equals(mode);
  }

  public Result run(Path favouritesCsv, Path historyCsv) throws IOException {
    return run(favouritesCsv, historyCsv, plan -> {});
  }

  public Result run(Path favouritesCsv, Path historyCsv, Approval approval) throws IOException {
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
      var plan = aggregate(prepared, validation.users(), progress, false);
      Path unmappedReport = writeUnmappedReport(plan.unmappedUsers());
      double unmappedRatio =
          validation.users() == 0 ? 0 : (double) plan.unmappedUsers().size() / validation.users();
      if (writeMode && unmappedRatio > maximumUnmappedUserRatio) {
        throw new IllegalStateException(
            "Refusing user-preference import: unmapped-user ratio "
                + String.format(java.util.Locale.ROOT, "%.2f%%", unmappedRatio * 100)
                + " exceeds configured limit "
                + String.format(java.util.Locale.ROOT, "%.2f%%", maximumUnmappedUserRatio * 100));
      }
      approval.approve(plan);
      if (writeMode) {
        aggregate(prepared, validation.users(), progress, true);
      }
      return new Result(
          counts[0],
          counts[1],
          validation.users(),
          validation.records(),
          plan.fullUpdates(),
          plan.partialUpdates(),
          plan.unmappedUsers().size(),
          unmappedReport,
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

  private UserPreferenceAggregatorService.Result aggregate(
      Path path, long totalUsers, JobProgress progress, boolean write) throws IOException {
    var batchFavourites = new ArrayList<UserFavourite>();
    var batchHistory = new ArrayList<UserHistory>();
    var userFavourites = new ArrayList<UserFavourite>();
    var userHistory = new ArrayList<UserHistory>();
    Instant asOf = Instant.now();
    long processed = 0;
    String user = null;
    var result = UserPreferenceAggregatorService.Result.empty();
    progress.stage(write ? "aggregating and saving user batches" : "preflighting user batches");
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
          result =
              result.plus(
                  write
                      ? save(batchFavourites, batchHistory, asOf)
                      : aggregator.preflight(batchFavourites, batchHistory, asOf));
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
      result =
          result.plus(
              write
                  ? save(batchFavourites, batchHistory, asOf)
                  : aggregator.preflight(batchFavourites, batchHistory, asOf));
    }
    return result;
  }

  private UserPreferenceAggregatorService.Result save(
      java.util.List<UserFavourite> favourites, java.util.List<UserHistory> history, Instant asOf)
      throws IOException {
    aggregator.aggregateAndSave(favourites, history, asOf);
    return UserPreferenceAggregatorService.Result.empty();
  }

  private Path writeUnmappedReport(java.util.List<String> userIds) throws IOException {
    if (userIds.isEmpty()) {
      return null;
    }
    Files.createDirectories(workDirectory);
    Path report = workDirectory.resolve("unmapped-users.txt");
    Files.write(report, userIds);
    try {
      Files.setPosixFilePermissions(
          report,
          java.util.Set.of(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX platforms retain their normal owner ACLs.
    }
    return report;
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
      long fullUpdates,
      long partialUpdates,
      long unmappedUsers,
      Path unmappedReport,
      long csvReadMs,
      long sortMs,
      long aggregationMs) {}

  @FunctionalInterface
  public interface Approval {
    void approve(UserPreferenceAggregatorService.Result plan) throws IOException;
  }
}
