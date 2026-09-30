package com.gen3.recommenderagent.storage.user.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.storage.user.model.UserFavourite;
import com.gen3.recommenderagent.storage.user.model.UserHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the exported preference CSV contract used by the one-off importer. */
class UserPreferenceCsvReaderTest {

  @TempDir Path temporaryDirectory;

  /** Reads book favourites, normalizes catalogue IDs, and skips other item types. */
  @Test
  void readsBookFavourites() throws IOException {
    Path csv = temporaryDirectory.resolve("favourites.csv");
    Files.writeString(
        csv,
        "user,source,type,item,created_at,updated_at\n"
            + "1005,GCB,book,rnib:book:14186_part1:The title,2026-07-02 12:46:28,NULL\n"
            + "1005,GCB,author,rnib:author:9:Someone,2026-07-02 12:46:28,NULL\n");

    List<UserFavourite> favourites = new UserFavouriteCsvReader().read(csv);

    assertThat(favourites).containsExactly(new UserFavourite("1005", "rnib_14186_part1"));
  }

  /** Keeps the latest timestamp when one user has multiple rows for the same book. */
  @Test
  void keepsLatestHistoryInteraction() throws IOException {
    Path csv = temporaryDirectory.resolve("recents.csv");
    Files.writeString(
        csv,
        "user,source,type,item,detail,created_at,updated_at\n"
            + "1001,GCB,book,rnib:book:42:Title,365,2020-01-01 00:00:00,NULL\n"
            + "1001,GCB,book,rnib:book:42:Title,365,2021-01-01 00:00:00,2022-01-01 00:00:00\n"
            + "1001,GCB,author,rnib:author:7:Author,365,2023-01-01 00:00:00,NULL\n");

    List<UserHistory> history = new UserHistoryCsvReader().read(csv);

    assertThat(history)
        .containsExactly(new UserHistory("1001", "rnib_42", Instant.parse("2022-01-01T00:00:00Z")));
  }

  /** Rejects malformed catalogue item identifiers instead of producing an unusable point ID. */
  @Test
  void rejectsMalformedBookItem() {
    assertThatThrownBy(() -> BookIdExtractor.extractAndNormalize("not-a-book"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid book item format");
  }
}
