package com.gen3.userpreferencejob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.userpreferencejob.model.UserFavourite;
import com.gen3.userpreferencejob.model.UserHistory;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies aggregation of catalogue vectors into the two stored user preference vectors. */
class UserPreferenceAggregatorServiceTest {

  /** Averages favourites and applies the 180-day half-life to history before saving. */
  @Test
  void aggregatesAvailableVectors() throws IOException {
    AudiobookVectorRepository audiobooks = mock(AudiobookVectorRepository.class);
    UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);
    when(audiobooks.findEmbeddingsByBookIds(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(
            Map.of(
                "fav",
                new float[] {1, 0},
                "recent",
                new float[] {1, 0},
                "old",
                new float[] {0, 1}));
    Instant now = Instant.now();
    UserPreferenceAggregatorService service =
        new UserPreferenceAggregatorService(audiobooks, users);

    service.aggregateAndSave(
        List.of(new UserFavourite("user-1", "fav"), new UserFavourite("user-1", "missing")),
        List.of(
            new UserHistory("user-1", "recent", now),
            new UserHistory("user-1", "old", now.minus(180, ChronoUnit.DAYS))));

    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    verify(users).saveAll(saved.capture());
    verifyNoMoreInteractions(users);
    verify(audiobooks).findEmbeddingsByBookIds(Set.of("fav", "missing", "recent", "old"));
    assertArrayEquals(new float[] {1, 0}, saved.getValue().getFirst().favouritesVector(), 0.0001f);
    assertArrayEquals(
        new float[] {0.8944272f, 0.4472136f}, saved.getValue().getFirst().historyVector(), 0.0001f);
  }

  @Test
  void reimportWithNoAvailableVectorsClearsBothPreviousTasteSignals() throws IOException {
    var books = mock(AudiobookVectorRepository.class);
    var users = mock(UserPreferenceVectorRepository.class);
    when(books.findEmbeddingsByBookIds(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(Map.of("book", new float[] {1, 0}), Map.of());
    var service = new UserPreferenceAggregatorService(books, users);
    var favourites = List.of(new UserFavourite("user", "book"));
    var history = List.of(new UserHistory("user", "book", Instant.now()));
    service.aggregateAndSave(favourites, history);
    service.aggregateAndSave(favourites, history);
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    verify(users).saveAll(saved.capture());
    assertArrayEquals(new float[] {1, 0}, saved.getValue().getFirst().favouritesVector());
    assertArrayEquals(new float[] {1, 0}, saved.getValue().getFirst().historyVector());
    verify(users).clearAll(List.of("user"));
    verifyNoMoreInteractions(users);
  }

  @Test
  void clearsMissingVectorsOncePerImportGroup() throws IOException {
    var books = mock(AudiobookVectorRepository.class);
    var users = mock(UserPreferenceVectorRepository.class);
    when(books.findEmbeddingsByBookIds(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(Map.of());
    new UserPreferenceAggregatorService(books, users)
        .aggregateAndSave(
            List.of(new UserFavourite("one", "missing"), new UserFavourite("two", "missing")),
            List.of());
    ArgumentCaptor<List<String>> cleared = ArgumentCaptor.forClass(List.class);
    verify(users).clearAll(cleared.capture());
    org.assertj.core.api.Assertions.assertThat(cleared.getValue())
        .containsExactlyInAnyOrder("one", "two");
    verifyNoMoreInteractions(users);
  }

  @Test
  void duplicateFavouritePairsDoNotIncreaseWeightOrMergeDifferentUsers() throws IOException {
    var books = mock(AudiobookVectorRepository.class);
    var users = mock(UserPreferenceVectorRepository.class);
    when(books.findEmbeddingsByBookIds(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(Map.of("a", new float[] {1, 0}, "b", new float[] {0, 1}));
    new UserPreferenceAggregatorService(books, users)
        .aggregateAndSave(
            List.of(
                new UserFavourite("u", "a"),
                new UserFavourite("u", "a"),
                new UserFavourite("u", "b"),
                new UserFavourite("other", "a")),
            List.of());
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    verify(users).saveAll(saved.capture());
    var byUser =
        saved.getValue().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    UserPreferenceEmbedding::userId, UserPreferenceEmbedding::favouritesVector));
    assertArrayEquals(new float[] {0.70710677f, 0.70710677f}, byUser.get("u"), 0.0001f);
    assertArrayEquals(new float[] {1, 0}, byUser.get("other"), 0.0001f);
  }

  @Test
  void writesBoundedBatchesIncludingTheFinalPartialBatch() throws IOException {
    var books = mock(AudiobookVectorRepository.class);
    var users = mock(UserPreferenceVectorRepository.class);
    when(books.findEmbeddingsByBookIds(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(Map.of("book", new float[] {1, 0}));
    var favourites =
        java.util.stream.IntStream.range(0, 501)
            .mapToObj(i -> new UserFavourite("user-" + i, "book"))
            .toList();
    new UserPreferenceAggregatorService(books, users).aggregateAndSave(favourites, List.of());
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    verify(users, org.mockito.Mockito.times(6)).saveAll(saved.capture());
    org.assertj.core.api.Assertions.assertThat(saved.getAllValues())
        .extracting(List::size)
        .containsExactly(100, 100, 100, 100, 100, 1);
  }

  @Test
  void capsDistinctBookIdsAcrossFavouritesAndHistoryAndKeepsUsersWhole() throws IOException {
    var books = mock(AudiobookVectorRepository.class);
    var users = mock(UserPreferenceVectorRepository.class);
    var requested = new java.util.ArrayList<Set<String>>();
    when(books.findEmbeddingsByBookIds(org.mockito.ArgumentMatchers.anyCollection()))
        .thenAnswer(
            call -> {
              java.util.Collection<String> ids = call.getArgument(0);
              requested.add(Set.copyOf(ids));
              return ids.stream()
                  .collect(java.util.stream.Collectors.toMap(id -> id, id -> new float[] {1, 0}));
            });
    new UserPreferenceAggregatorService(books, users, 100, 2)
        .aggregateAndSave(
            List.of(
                new UserFavourite("a", "one"),
                new UserFavourite("a", "one"),
                new UserFavourite("b", "two"),
                new UserFavourite("c", "three")),
            List.of(new UserHistory("a", "two", Instant.now())));
    org.assertj.core.api.Assertions.assertThat(requested)
        .containsExactly(Set.of("one", "two"), Set.of("three"));
    ArgumentCaptor<List<UserPreferenceEmbedding>> saved = ArgumentCaptor.forClass(List.class);
    verify(users, org.mockito.Mockito.times(2)).saveAll(saved.capture());
    org.assertj.core.api.Assertions.assertThat(saved.getAllValues())
        .extracting(List::size)
        .containsExactly(2, 1);
  }

  @Test
  void rejectsAnOversizedUserBeforeAnyReadsOrWrites() {
    var books = mock(AudiobookVectorRepository.class);
    var users = mock(UserPreferenceVectorRepository.class);
    var input =
        List.of(
            new UserFavourite("a-small", "one"),
            new UserFavourite("z-large", "one"),
            new UserFavourite("z-large", "two"),
            new UserFavourite("z-large", "three"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new UserPreferenceAggregatorService(books, users, 1, 2)
                    .aggregateAndSave(input, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("z-large")
        .hasMessageContaining("3")
        .hasMessageContaining("2");
    org.mockito.Mockito.verifyNoInteractions(books, users);
  }
}
