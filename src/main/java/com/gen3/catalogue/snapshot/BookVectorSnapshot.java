package com.gen3.catalogue.snapshot;

import com.gen3.catalogue.port.AudiobookVectorRepository;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/** Persistent, checksummed catalogue vectors; only complete snapshots are published. */
public final class BookVectorSnapshot implements AudiobookVectorRepository {
  private static final int MAGIC = 0x42565331;
  private final Path file;
  private final String backend;
  private final String collection;
  private final String model;
  private final int dimensions;
  private Map<String, float[]> loaded;

  public BookVectorSnapshot(
      Path file, String backend, String collection, String model, int dimensions) {
    if (dimensions < 1 || backend.isBlank() || collection.isBlank() || model.isBlank()) {
      throw new IllegalArgumentException("Snapshot identity and dimensions must be configured");
    }
    this.file = file;
    this.backend = backend;
    this.collection = collection;
    this.model = model;
    this.dimensions = dimensions;
  }

  public synchronized void rebuild(Source source, int pageSize) throws IOException {
    if (pageSize < 1) {
      throw new IllegalArgumentException("Snapshot page size must be positive");
    }
    Path parent = file.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, "snapshot-", ".tmp");
    long count = 0;
    try (var progress = new com.gen3.maintenance.JobProgress("book.snapshot.rebuild")) {
      var digest = sha256();
      try (var bytes =
              new DigestOutputStream(
                  new BufferedOutputStream(Files.newOutputStream(temporary)), digest);
          var output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeInt(1);
        output.writeUTF(backend);
        output.writeUTF(collection);
        output.writeUTF(model);
        output.writeInt(dimensions);
        output.writeUTF(Instant.now().toString());
        var seen = new HashSet<String>();
        var vectorBytes = java.nio.ByteBuffer.allocate(Math.multiplyExact(dimensions, Float.BYTES));
        String cursor = null;
        do {
          progress.stage("reading catalogue and writing snapshot");
          Page page = source.page(cursor, pageSize);
          for (Entry entry : page.entries()) {
            if (entry.bookId() == null || entry.bookId().isBlank() || !seen.add(entry.bookId())) {
              throw new IOException("Missing or duplicate snapshot book ID: " + entry.bookId());
            }
            if (entry.vector() == null || entry.vector().length == 0) {
              continue;
            }
            validateVector(entry.vector());
            output.writeBoolean(true);
            output.writeUTF(entry.bookId());
            vectorBytes.asFloatBuffer().put(entry.vector());
            output.write(vectorBytes.array());
            count++;
          }
          if (page.nextCursor() != null
              && (page.nextCursor().equals(cursor) || page.entries().isEmpty())) {
            throw new IOException("Snapshot source did not advance its cursor");
          }
          cursor = page.nextCursor();
          progress.update(seen.size(), -1);
        } while (cursor != null);
        if (count == 0) {
          throw new IOException("Refusing to publish an empty book-vector snapshot");
        }
        output.writeBoolean(false);
        output.writeLong(count);
        bytes.on(false);
        output.write(digest.digest());
      }
      read(temporary, false, progress);
      progress.stage("publishing snapshot");
      Files.move(
          temporary,
          file.toAbsolutePath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      LoggerFactory.getLogger(BookVectorSnapshot.class)
          .info(
              "book.vector.snapshot.published backend={} collection={} books={} file={}",
              backend,
              collection,
              count,
              file);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Validates the entire last published snapshot before any user-profile writes. */
  public synchronized void validate() throws IOException {
    if (loaded == null) {
      try (var progress = new com.gen3.maintenance.JobProgress("book.snapshot.load")) {
        loaded = read(file, true, progress);
      }
    }
  }

  @Override
  public Optional<float[]> findEmbeddingByBookId(String bookId) throws IOException {
    validate();
    return Optional.ofNullable(loaded.get(bookId));
  }

  @Override
  public Map<String, float[]> findEmbeddingsByBookIds(Collection<String> bookIds)
      throws IOException {
    validate();
    var result = new HashMap<String, float[]>();
    for (String id : bookIds) {
      float[] vector = loaded.get(id);
      if (vector != null) {
        result.put(id, vector);
      }
    }
    return result;
  }

  private Map<String, float[]> read(
      Path path, boolean retain, com.gen3.maintenance.JobProgress progress) throws IOException {
    progress.stage(retain ? "loading local vectors" : "validating snapshot");
    progress.update(0, -1);
    var result = new HashMap<String, float[]>();
    var seen = new HashSet<String>();
    var digest = sha256();
    try (var bytes =
            new DigestInputStream(new BufferedInputStream(Files.newInputStream(path)), digest);
        var input = new DataInputStream(bytes)) {
      if (input.readInt() != MAGIC
          || input.readInt() != 1
          || !input.readUTF().equals(backend)
          || !input.readUTF().equals(collection)
          || !input.readUTF().equals(model)
          || input.readInt() != dimensions) {
        throw new IOException("Incompatible book-vector snapshot: " + path);
      }
      String createdAt = input.readUTF();
      var vectorBytes = java.nio.ByteBuffer.allocate(Math.multiplyExact(dimensions, Float.BYTES));
      while (input.readBoolean()) {
        String id = input.readUTF();
        if (id.isBlank() || !seen.add(id)) {
          throw new IOException("Missing or duplicate book ID in snapshot");
        }
        float[] vector = new float[dimensions];
        input.readFully(vectorBytes.array());
        vectorBytes.asFloatBuffer().get(vector);
        validateVector(vector);
        if (retain) {
          result.put(id, vector);
        }
        if (seen.size() % 1000 == 0) {
          progress.update(seen.size(), -1);
        }
      }
      if (input.readLong() != seen.size() || seen.isEmpty()) {
        throw new IOException("Invalid snapshot book count");
      }
      bytes.on(false);
      byte[] checksum = input.readNBytes(32);
      if (!MessageDigest.isEqual(digest.digest(), checksum) || input.read() != -1) {
        throw new IOException("Book-vector snapshot checksum mismatch: " + path);
      }
      if (retain) {
        LoggerFactory.getLogger(BookVectorSnapshot.class)
            .info(
                "book.vector.snapshot.loaded backend={} collection={} books={} createdAt={} file={}",
                backend,
                collection,
                seen.size(),
                createdAt,
                path);
      }
      progress.update(seen.size(), seen.size());
    } catch (IOException exception) {
      throw new IOException(
          "Cannot use book-vector snapshot " + path + "; rebuild it before importing users",
          exception);
    }
    return result;
  }

  private void validateVector(float[] vector) throws IOException {
    if (vector.length != dimensions) {
      throw new IOException("Snapshot vector dimension mismatch");
    }
    double norm = 0;
    for (float value : vector) {
      if (!Float.isFinite(value)) {
        throw new IOException("Non-finite snapshot vector");
      }
      norm += (double) value * value;
    }
    if (norm == 0) {
      throw new IOException("Zero snapshot vector");
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public interface Source {
    Page page(String cursor, int limit) throws IOException;
  }

  public record Entry(String bookId, float[] vector) {}

  public record Page(java.util.List<Entry> entries, String nextCursor) {}
}
