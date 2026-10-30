package com.gen3.userpreferencejob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class StreamingUserPreferenceImporterTest {
  @TempDir Path directory;
  private final AudiobookVectorRepository books = mock(AudiobookVectorRepository.class);
  private final UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);

  private StreamingUserPreferenceImporter importer(int rows, int bookLimit) throws Exception {
    when(books.findEmbeddingsByBookIds(anyCollection()))
        .thenAnswer(
            call -> {
              java.util.Collection<String> ids = call.getArgument(0);
              return ids.stream()
                  .collect(
                      java.util.stream.Collectors.toMap(
                          id -> id,
                          id -> id.endsWith("_b") ? new float[] {0, 1} : new float[] {1, 0}));
            });
    return new StreamingUserPreferenceImporter(
        new UserFavouriteCsvReader(),
        new UserHistoryCsvReader(),
        new UserPreferenceAggregatorService(books, users, bookLimit),
        directory.resolve("work"),
        rows,
        bookLimit);
  }

  private Path csv(String file, String contents) throws Exception {
    return Files.writeString(directory.resolve(file), contents);
  }

  private void assertClean() throws Exception {
    try (var files = Files.list(directory.resolve("work"))) {
      assertThat(files.toList()).isEmpty();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("invalidUserRows")
  void rejectsInvalidIdsInEitherInputBeforeAnyVectorReadsOrWrites(boolean inHistory, String id)
      throws Exception {
    var service = importer(1, 10);
    String header = "type,user,item,created_at,updated_at\n";
    String valid = "book,valid,r:book:a,2026-01-01 00:00:00,NULL\n";
    String invalid = "book,\"" + id + "\",r:book:missing,2026-01-01 00:00:00,NULL\n";
    var favourites = csv("f.csv", header + valid + (inHistory ? "" : invalid));
    var history = csv("h.csv", header + (inHistory ? invalid : ""));
    assertThatThrownBy(() -> service.run(favourites, history))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("CSV user must be nonblank and at most 256 characters");
    verifyNoInteractions(books, users);
    assertClean();
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidUserRows() {
    return java.util.stream.Stream.of(false, true)
        .flatMap(
            history ->
                java.util.stream.Stream.of("", "   ", "\u3000", "u".repeat(257))
                    .map(id -> org.junit.jupiter.params.provider.Arguments.of(history, id)));
  }

  @Test
  void acceptsMaximumLengthOpaqueIdWithoutChangingIt() throws Exception {
    String id = "u".repeat(256);
    var result =
        importer(1, 10)
            .run(
                csv("f.csv", "type,user,item\nbook," + id + ",r:book:a\n"),
                csv("h.csv", "type,user,item,created_at,updated_at\n"));
    assertThat(result.users()).isEqualTo(1);
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(users).saveAll(saved.capture());
    assertThat(saved.getValue().getFirst().userId()).isEqualTo(id);
  }

  @Test
  void mergesUnsortedDuplicatesAcrossRunsAndNeverSplitsAUser() throws Exception {
    var favourites =
        csv(
            "f.csv",
            "type,user,item\nbook,z,r:book:a\nbook,\"a,one\",r:book:a\nbook,z,r:book:b\nbook,z,r:book:a\n");
    var history =
        csv(
            "h.csv",
            "type,user,item,created_at,updated_at\nbook,z,r:book:a,2020-01-01 00:00:00,NULL\nbook,z,r:book:b,2026-01-01 00:00:00,NULL\nbook,z,r:book:a,2021-01-01 00:00:00,2026-01-01 00:00:00\n");
    var result = importer(2, 2).run(favourites, history);
    assertThat(result.favourites()).isEqualTo(4);
    assertThat(result.history()).isEqualTo(3);
    assertThat(result.users()).isEqualTo(2);
    assertThat(result.uniqueRecords()).isEqualTo(5);
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(users, org.mockito.Mockito.times(2)).saveAll(saved.capture());
    var profiles = saved.getAllValues().stream().flatMap(List::stream).toList();
    assertThat(profiles).extracting(UserPreferenceEmbedding::userId).containsExactly("a,one", "z");
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new float[] {0.70710677f, 0.70710677f}, profiles.getLast().favouritesVector(), 0.000001f);
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new float[] {0.70710677f, 0.70710677f}, profiles.getLast().historyVector(), 0.000001f);
    assertClean();
  }

  @Test
  void supportsMultipleMergePassesWithoutKeepingAllRunsOpen() throws Exception {
    var input = new StringBuilder("type,user,item\n");
    for (int i = 69; i >= 0; i--) {
      input.append("book,user-").append(i).append(",r:book:a\n");
    }
    var result =
        importer(1, 10)
            .run(
                csv("f.csv", input.toString()),
                csv("h.csv", "type,user,item,created_at,updated_at\n"));
    assertThat(result.users()).isEqualTo(70);
    assertThat(result.uniqueRecords()).isEqualTo(70);
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(users, org.mockito.Mockito.times(70)).saveAll(saved.capture());
    assertThat(
            saved.getAllValues().stream()
                .flatMap(List::stream)
                .map(UserPreferenceEmbedding::userId)
                .distinct()
                .count())
        .isEqualTo(70);
    assertClean();
  }

  @Test
  void validatesEveryUserBeforeAnyWritesAndCleansFailedSpills() throws Exception {
    var service = importer(1, 2);
    var favourites =
        csv(
            "f.csv",
            "type,user,item\nbook,a,r:book:a\nbook,z,r:book:a\nbook,z,r:book:b\nbook,z,r:book:c\n");
    var history = csv("h.csv", "type,user,item,created_at,updated_at\n");
    assertThatThrownBy(() -> service.run(favourites, history))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("z");
    verifyNoInteractions(books, users);
    assertClean();
  }

  @Test
  void malformedLateCsvCannotLeaveEarlierUsersWritten() throws Exception {
    var service = importer(1, 10);
    var favourites = csv("f.csv", "type,user,item\nbook,a,r:book:a\nbook,b,r:book:b\n");
    var history =
        csv("h.csv", "type,user,item,created_at,updated_at\nbook,z,r:book:a,not-a-date,NULL\n");
    assertThatThrownBy(() -> service.run(favourites, history))
        .isInstanceOf(java.time.format.DateTimeParseException.class);
    verifyNoInteractions(books, users);
    assertClean();
  }

  @Test
  void emptyInputsDoNotWriteOrClearProfiles() throws Exception {
    var result =
        importer(1, 10)
            .run(
                csv("f.csv", "type,user,item\n"),
                csv("h.csv", "type,user,item,created_at,updated_at\n"));
    assertThat(result.users()).isZero();
    verifyNoInteractions(books, users);
    assertClean();
  }
}
