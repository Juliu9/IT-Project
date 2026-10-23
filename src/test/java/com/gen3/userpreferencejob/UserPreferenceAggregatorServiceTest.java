package com.gen3.userpreferencejob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
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
    verify(users, org.mockito.Mockito.times(2)).saveAll(saved.capture());
    org.assertj.core.api.Assertions.assertThat(saved.getAllValues())
        .extracting(List::size)
        .containsExactly(500, 1);
  }
}
