package com.gen3.userpreferencejob;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserPreferenceImportManifestTest {
  @TempDir Path directory;

  @Test
  void writeApprovalRequiresTheExactReviewedFilesSnapshotAndOutcome() throws Exception {
    Path favourites = Files.writeString(directory.resolve("favourites.csv"), "type,user,item\n");
    Path history =
        Files.writeString(
            directory.resolve("history.csv"), "type,user,item,created_at,updated_at\n");
    Path snapshotFile = directory.resolve("snapshot.bin");
    var snapshot = new BookVectorSnapshot(snapshotFile, "qdrant", "books", "model", 2);
    snapshot.rebuild(
        (cursor, limit) ->
            new BookVectorSnapshot.Page(
                cursor == null
                    ? List.of(new BookVectorSnapshot.Entry("book", new float[] {1, 0}))
                    : List.of(),
                null),
        10);
    var manifest = new UserPreferenceImportManifest(directory.resolve("approved.properties"));
    var plan = new UserPreferenceAggregatorService.Result(1, 0, List.of());

    manifest.approve("dry-run", favourites, history, snapshot, plan);
    manifest.approve("write", favourites, history, snapshot, plan);

    Files.writeString(
        favourites, "book,user,r:book:changed\n", java.nio.file.StandardOpenOption.APPEND);
    assertThatThrownBy(() -> manifest.approve("write", favourites, history, snapshot, plan))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("differs from the approved dry run");
  }
}
