package com.gen3.recommenderagent.storage.user;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.model.UserFavourite;
import com.gen3.recommenderagent.storage.user.model.UserHistory;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies aggregation of catalogue vectors into the two stored user preference vectors. */
class UserPreferenceAggregatorServiceTest {

  /** Averages favourites and applies the 180-day half-life to history before saving. */
  @Test
  void aggregatesAvailableVectors() throws IOException {
    AudiobookVectorRepository audiobooks = mock(AudiobookVectorRepository.class);
    UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);
    when(audiobooks.findEmbeddingByBookId("fav")).thenReturn(Optional.of(new float[] {1, 0}));
    when(audiobooks.findEmbeddingByBookId("missing")).thenReturn(Optional.empty());
    when(audiobooks.findEmbeddingByBookId("recent")).thenReturn(Optional.of(new float[] {1, 0}));
    when(audiobooks.findEmbeddingByBookId("old")).thenReturn(Optional.of(new float[] {0, 1}));
    Instant now = Instant.now();
    UserPreferenceAggregatorService service =
        new UserPreferenceAggregatorService(audiobooks, users);

    service.aggregateAndSave(
        List.of(new UserFavourite("user-1", "fav"), new UserFavourite("user-1", "missing")),
        List.of(
            new UserHistory("user-1", "recent", now),
            new UserHistory("user-1", "old", now.minus(180, ChronoUnit.DAYS))));

    ArgumentCaptor<UserPreferenceEmbedding> saved =
        ArgumentCaptor.forClass(UserPreferenceEmbedding.class);
    verify(users).save(saved.capture());
    verifyNoMoreInteractions(users);
    assertArrayEquals(new float[] {1, 0}, saved.getValue().favouritesVector(), 0.0001f);
    assertArrayEquals(
        new float[] {0.8944272f, 0.4472136f}, saved.getValue().historyVector(), 0.0001f);
  }
}
