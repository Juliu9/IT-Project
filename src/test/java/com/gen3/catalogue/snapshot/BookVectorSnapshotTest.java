package com.gen3.catalogue.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookVectorSnapshotTest {
  @TempDir Path directory;

  private BookVectorSnapshot snapshot(String backend, String model, int dimensions) {
    return new BookVectorSnapshot(
        directory.resolve("vectors.bin"), backend, "books", model, dimensions);
  }

  private BookVectorSnapshot.Source source(float[] vector) {
    return (cursor, limit) ->
        new BookVectorSnapshot.Page(List.of(new BookVectorSnapshot.Entry("book", vector)), null);
  }

  @Test
  void persistsExactVectorsAndReusesThePublishedSnapshotAcrossProcessesAndGroups()
      throws Exception {
    var writer = snapshot("qdrant", "model", 2);
    writer.rebuild(source(new float[] {0.6f, 0.8f}), 10);
    var reader = snapshot("qdrant", "model", 2);
    assertThat(reader.findEmbeddingByBookId("book").orElseThrow()).containsExactly(0.6f, 0.8f);
    assertThat(reader.findEmbeddingByBookId("missing")).isEmpty();
    Files.delete(directory.resolve("vectors.bin"));
    assertThat(reader.findEmbeddingsByBookIds(List.of("book", "missing"))).containsOnlyKeys("book");
  }

  @Test
  void publishesAllPagesAndSkipsBooksWithoutVectors() throws Exception {
    var snapshot = snapshot("solr", "model", 2);
    snapshot.rebuild(
        (cursor, limit) ->
            cursor == null
                ? new BookVectorSnapshot.Page(
                    List.of(
                        new BookVectorSnapshot.Entry("one", new float[] {1, 0}),
                        new BookVectorSnapshot.Entry("missing", null)),
                    "next")
                : new BookVectorSnapshot.Page(
                    List.of(new BookVectorSnapshot.Entry("two", new float[] {0, 1})), null),
        2);
    assertThat(snapshot.findEmbeddingsByBookIds(List.of("one", "two", "missing")))
        .containsOnlyKeys("one", "two");
  }

  @Test
  void failedRefreshKeepsPreviousFileAndReadersPinnedToTheirVersion() throws Exception {
    var snapshot = snapshot("qdrant", "model", 2);
    snapshot.rebuild(source(new float[] {1, 0}), 1);
    snapshot.validate();
    byte[] before = Files.readAllBytes(directory.resolve("vectors.bin"));
    assertThatThrownBy(
            () ->
                snapshot.rebuild(
                    (cursor, limit) -> {
                      throw new IOException("unavailable");
                    },
                    1))
        .isInstanceOf(IOException.class);
    assertThat(Files.readAllBytes(directory.resolve("vectors.bin"))).isEqualTo(before);
    snapshot.rebuild(source(new float[] {0, 1}), 1);
    assertThat(snapshot.findEmbeddingByBookId("book").orElseThrow()).containsExactly(1, 0);
    assertThat(snapshot("qdrant", "model", 2).findEmbeddingByBookId("book").orElseThrow())
        .containsExactly(0, 1);
    try (var files = Files.list(directory)) {
      assertThat(files.map(path -> path.getFileName().toString()).toList())
          .containsExactly("vectors.bin");
    }
  }

  @Test
  void rejectsMissingIncompatibleTruncatedAndCorruptSnapshots() throws Exception {
    assertThatThrownBy(() -> snapshot("qdrant", "model", 2).validate())
        .isInstanceOf(IOException.class);
    snapshot("qdrant", "model", 2).rebuild(source(new float[] {1, 0}), 1);
    for (var incompatible :
        List.of(
            snapshot("solr", "model", 2),
            snapshot("qdrant", "other", 2),
            snapshot("qdrant", "model", 3),
            new BookVectorSnapshot(
                directory.resolve("vectors.bin"), "qdrant", "other", "model", 2))) {
      assertThatThrownBy(incompatible::validate).isInstanceOf(IOException.class);
    }
    byte[] original = Files.readAllBytes(directory.resolve("vectors.bin"));
    byte[] corrupt = original.clone();
    corrupt[corrupt.length - 1] ^= 1;
    Files.write(directory.resolve("vectors.bin"), corrupt);
    assertThatThrownBy(() -> snapshot("qdrant", "model", 2).validate())
        .isInstanceOf(IOException.class);
    Files.write(
        directory.resolve("vectors.bin"), java.util.Arrays.copyOf(original, original.length - 10));
    assertThatThrownBy(() -> snapshot("qdrant", "model", 2).validate())
        .isInstanceOf(IOException.class);
  }

  @Test
  void rejectsBadVectorsEmptyExportsAndDuplicatesWithoutReplacingLastValidFile() throws Exception {
    var snapshot = snapshot("qdrant", "model", 2);
    snapshot.rebuild(source(new float[] {1, 0}), 1);
    byte[] before = Files.readAllBytes(directory.resolve("vectors.bin"));
    for (float[] invalid :
        new float[][] {{1}, {0, 0}, {Float.NaN, 1}, {Float.POSITIVE_INFINITY, 1}}) {
      assertThatThrownBy(() -> snapshot.rebuild(source(invalid), 1))
          .isInstanceOf(IOException.class);
    }
    assertThatThrownBy(
            () ->
                snapshot.rebuild(
                    (cursor, limit) -> new BookVectorSnapshot.Page(List.of(), null), 1))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(
            () ->
                snapshot.rebuild(
                    (cursor, limit) ->
                        new BookVectorSnapshot.Page(
                            List.of(new BookVectorSnapshot.Entry("dup", new float[] {1, 0})),
                            cursor == null ? "next" : null),
                    1))
        .isInstanceOf(IOException.class);
    assertThat(Files.readAllBytes(directory.resolve("vectors.bin"))).isEqualTo(before);
  }
}
