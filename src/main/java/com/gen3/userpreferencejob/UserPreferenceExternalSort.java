package com.gen3.userpreferencejob;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded external merge sort; keeps whole users together without assuming CSV row order. */
final class UserPreferenceExternalSort implements AutoCloseable {
  private static final int FAN_IN = 32;
  private static final Comparator<Row> ORDER =
      Comparator.comparing(Row::userId)
          .thenComparing(Row::bookId)
          .thenComparing(Row::history)
          .thenComparing(Comparator.comparingLong(Row::epochSecond).reversed());
  private final Path directory;
  private final int maximumRows;
  private final List<Row> buffer = new ArrayList<>();
  private final List<Path> runs = new ArrayList<>();

  UserPreferenceExternalSort(Path workDirectory, int maximumRows) throws IOException {
    if (maximumRows < 1) {
      throw new IllegalArgumentException("Import memory record limit must be positive");
    }
    this.maximumRows = maximumRows;
    Files.createDirectories(workDirectory);
    directory = Files.createTempDirectory(workDirectory, "user-import-");
  }

  void add(Row row) throws IOException {
    buffer.add(row);
    if (buffer.size() == maximumRows) {
      flush();
    }
  }

  Path finish(com.gen3.maintenance.JobProgress progress) throws IOException {
    flush();
    if (runs.isEmpty()) {
      Path empty = Files.createTempFile(directory, "empty-", ".bin");
      try (var output = output(empty)) {
        output.writeBoolean(false);
      }
      return empty;
    }
    while (runs.size() > 1) {
      var merged = new ArrayList<Path>();
      for (int start = 0; start < runs.size(); start += FAN_IN) {
        List<Path> group = runs.subList(start, Math.min(start + FAN_IN, runs.size()));
        Path target = Files.createTempFile(directory, "merged-", ".bin");
        merge(group, target, progress);
        merged.add(target);
        for (Path input : group) {
          Files.delete(input);
        }
      }
      runs.clear();
      runs.addAll(merged);
    }
    return runs.getFirst();
  }

  private void flush() throws IOException {
    if (buffer.isEmpty()) {
      return;
    }
    buffer.sort(ORDER);
    Path run = Files.createTempFile(directory, "run-", ".bin");
    try (var output = output(run)) {
      Row previous = null;
      for (Row row : buffer) {
        if (!sameKey(previous, row)) {
          write(output, row);
          previous = row;
        }
      }
      output.writeBoolean(false);
    }
    runs.add(run);
    buffer.clear();
  }

  private void merge(List<Path> sources, Path target, com.gen3.maintenance.JobProgress progress)
      throws IOException {
    var readers = new ArrayList<Reader>();
    var queue =
        new PriorityQueue<Reader>((left, right) -> ORDER.compare(left.current, right.current));
    long processed = 0;
    try (var output = output(target)) {
      for (Path source : sources) {
        var reader = new Reader(source);
        readers.add(reader);
        if (reader.advance()) {
          queue.add(reader);
        }
      }
      Row previous = null;
      while (!queue.isEmpty()) {
        Reader reader = queue.remove();
        Row row = reader.current;
        if (!sameKey(previous, row)) {
          write(output, row);
          previous = row;
        }
        if (++processed % 10000 == 0) {
          progress.update(processed, -1);
        }
        if (reader.advance()) {
          queue.add(reader);
        }
      }
      output.writeBoolean(false);
    } finally {
      IOException failure = null;
      for (Reader reader : readers) {
        try {
          reader.close();
        } catch (IOException exception) {
          failure = exception;
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static boolean sameKey(Row left, Row right) {
    return left != null
        && left.userId().equals(right.userId())
        && left.bookId().equals(right.bookId())
        && left.history() == right.history();
  }

  private static DataOutputStream output(Path path) throws IOException {
    return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
  }

  private static void write(DataOutputStream output, Row row) throws IOException {
    output.writeBoolean(true);
    output.writeUTF(row.userId());
    output.writeUTF(row.bookId());
    output.writeBoolean(row.history());
    output.writeLong(row.epochSecond());
  }

  @Override
  public void close() throws IOException {
    // Only remove the unique temporary directory created by this import.
    try (var files = Files.walk(directory)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  record Row(String userId, String bookId, boolean history, long epochSecond) {}

  static final class Reader implements AutoCloseable {
    private final DataInputStream input;
    private Row current;

    Reader(Path path) throws IOException {
      input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
    }

    boolean advance() throws IOException {
      if (!input.readBoolean()) {
        current = null;
        return false;
      }
      current = new Row(input.readUTF(), input.readUTF(), input.readBoolean(), input.readLong());
      return true;
    }

    Row current() {
      return current;
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }
}
