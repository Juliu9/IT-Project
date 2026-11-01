package com.gen3.userpreferencejob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.userpreferencejob.model.UserFavourite;
import com.gen3.userpreferencejob.model.UserHistory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserPreferenceImportSafetyTest {
  @TempDir Path directory;

  @Test
  void preservesExistingSideAndSkipsUsersWithNoMappedVectors() throws Exception {
    var books = new Books(Map.of("fav", new float[] {1, 0}));
    var users = new Users();
    users.saved.put(
        "partial", new UserPreferenceEmbedding("partial", new float[] {0, 1}, new float[] {0, 1}));
    var service = new UserPreferenceAggregatorService(books, users);

    service.aggregateAndSave(
        List.of(new UserFavourite("partial", "fav"), new UserFavourite("unmapped", "missing")),
        List.of(new UserHistory("partial", "missing", Instant.now())));

    assertThat(users.saved.get("partial").favouritesVector()).containsExactly(1, 0);
    assertThat(users.saved.get("partial").historyVector()).containsExactly(0, 1);
    assertThat(users.saved).doesNotContainKey("unmapped");
  }

  @Test
  void writeModeAbortsAfterCompletePreflightWhenUnmappedRatioExceedsLimit() throws Exception {
    var users = new Users();
    var importer =
        new StreamingUserPreferenceImporter(
            new UserFavouriteCsvReader(),
            new UserHistoryCsvReader(),
            new UserPreferenceAggregatorService(new Books(Map.of()), users),
            directory.resolve("work"),
            10,
            10,
            0.10,
            "write");
    Path favourites =
        Files.writeString(
            directory.resolve("favourites.csv"), "type,user,item\nbook,user,r:book:missing\n");
    Path history =
        Files.writeString(
            directory.resolve("history.csv"), "type,user,item,created_at,updated_at\n");

    assertThatThrownBy(() -> importer.run(favourites, history))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("100.00%")
        .hasMessageContaining("10.00%");
    assertThat(users.saved).isEmpty();
    assertThat(Files.readAllLines(directory.resolve("work/unmapped-users.txt")))
        .containsExactly("user");
  }

  private record Books(Map<String, float[]> vectors) implements AudiobookVectorRepository {
    @Override
    public Optional<float[]> findEmbeddingByBookId(String bookId) {
      return Optional.ofNullable(vectors.get(bookId));
    }

    @Override
    public Map<String, float[]> findEmbeddingsByBookIds(Collection<String> bookIds) {
      var found = new LinkedHashMap<String, float[]>();
      bookIds.forEach(
          id -> Optional.ofNullable(vectors.get(id)).ifPresent(value -> found.put(id, value)));
      return found;
    }
  }

  private static final class Users implements UserPreferenceVectorRepository {
    private final Map<String, UserPreferenceEmbedding> saved = new LinkedHashMap<>();

    @Override
    public void save(UserPreferenceEmbedding embedding) {
      saved.put(embedding.userId(), embedding);
    }

    @Override
    public void clear(String userId) {
      throw new AssertionError("Import must not clear profiles");
    }

    @Override
    public Optional<UserPreferenceEmbedding> findEmbeddingByUserId(String userId) {
      return Optional.ofNullable(saved.get(userId));
    }
  }
}
