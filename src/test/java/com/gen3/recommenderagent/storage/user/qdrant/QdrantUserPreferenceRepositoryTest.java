package com.gen3.recommenderagent.storage.user.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;

class QdrantUserPreferenceRepositoryTest {

  @Test
  void doesNotCreateMissingCollectionWhenReadingPreferences() throws Exception {
    QdrantClient client = mock(QdrantClient.class);
    when(client.collectionExistsAsync("user")).thenReturn(Futures.immediateFuture(false));
    QdrantUserPreferenceRepository repository =
        new QdrantUserPreferenceRepository(client, new QdrantUserPreferenceMapper(), "user", 2);

    assertThat(repository.findEmbeddingByUserId("user-1")).isEmpty();

    verify(client, never()).createCollectionAsync(any());
    verify(client, never())
        .retrieveAsync(anyString(), anyList(), anyBoolean(), anyBoolean(), isNull());
  }
}
