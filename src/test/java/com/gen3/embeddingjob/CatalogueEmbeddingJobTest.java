package com.gen3.embeddingjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.model.StoredAudiobook;
import com.gen3.embeddingjob.store.AudiobookEmbeddingStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

class CatalogueEmbeddingJobTest {
  private final AudiobookEmbeddingStore store = mock(AudiobookEmbeddingStore.class);
  private final EmbeddingModel model = mock(EmbeddingModel.class);
  private final StoredAudiobook book =
      new StoredAudiobook(
          new AudiobookRecord("book", "s", "Title", List.of(), "Description"), "opaque-reference");

  @Test
  void pagesThroughCatalogueAndWritesNormalizedEmbeddingsToOriginalReferences() throws Exception {
    when(store.embeddingPage(null, 1, EmbeddingMode.MISSING_ONLY))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(book), "next"));
    when(store.embeddingPage("next", 1, EmbeddingMode.MISSING_ONLY))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(book), null));
    when(model.embed(anyList())).thenReturn(List.of(new float[] {3, 4}));
    new CatalogueEmbeddingJob(
            store, new AudiobookEmbeddingIndexer(model), 1, 0, EmbeddingMode.MISSING_ONLY)
        .run();
    var updates = ArgumentCaptor.forClass(List.class);
    verify(store, times(2)).updateEmbeddings(updates.capture());
    var update = (AudiobookEmbeddingStore.Update) updates.getValue().getFirst();
    assertThat(update.storageReference()).isEqualTo("opaque-reference");
    assertThat(update.embedding().vector()).containsExactly(0.6f, 0.8f);
    verify(model, times(2)).embed(anyList());
  }

  @Test
  void maximumRecordsCapsTheJobAndBlankRecordsAreNotSentToTheModel() throws Exception {
    var blank =
        new StoredAudiobook(new AudiobookRecord("blank", null, null, List.of(), null), "blank-ref");
    when(store.embeddingPage(null, 1, EmbeddingMode.MISSING_ONLY))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(blank), "next"));
    new CatalogueEmbeddingJob(
            store, new AudiobookEmbeddingIndexer(model), 10, 1, EmbeddingMode.MISSING_ONLY)
        .run();
    verify(store).embeddingPage(null, 1, EmbeddingMode.MISSING_ONLY);
    verifyNoMoreInteractions(store);
    verifyNoInteractions(model);
  }

  @Test
  void modelFailureDoesNotWriteThePage() throws Exception {
    when(store.embeddingPage(null, 1, EmbeddingMode.MISSING_ONLY))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(book), null));
    when(model.embed(anyList())).thenThrow(new IllegalStateException("provider unavailable"));
    assertThatThrownBy(
            () ->
                new CatalogueEmbeddingJob(
                        store,
                        new AudiobookEmbeddingIndexer(model),
                        1,
                        0,
                        EmbeddingMode.MISSING_ONLY)
                    .run())
        .hasMessage("provider unavailable");
    verify(store, never()).updateEmbeddings(anyList());
  }

  @Test
  void noMatchingRecordsMakesNoModelOrWriteCalls() throws Exception {
    when(store.embeddingPage(null, 10, EmbeddingMode.MISSING_ONLY))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(), null));
    new CatalogueEmbeddingJob(
            store, new AudiobookEmbeddingIndexer(model), 10, 0, EmbeddingMode.MISSING_ONLY)
        .run();
    verifyNoInteractions(model);
    verify(store, never()).updateEmbeddings(anyList());
  }

  @Test
  void rebuildAllPassesItsSelectionToEveryPage() throws Exception {
    when(store.embeddingPage(null, 1, EmbeddingMode.REBUILD_ALL))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(book), "next"));
    when(store.embeddingPage("next", 1, EmbeddingMode.REBUILD_ALL))
        .thenReturn(new AudiobookEmbeddingStore.Page(List.of(book), null));
    when(model.embed(anyList())).thenReturn(List.of(new float[] {1, 0}));
    new CatalogueEmbeddingJob(
            store, new AudiobookEmbeddingIndexer(model), 1, 0, EmbeddingMode.REBUILD_ALL)
        .run();
    verify(store).embeddingPage(null, 1, EmbeddingMode.REBUILD_ALL);
    verify(store).embeddingPage("next", 1, EmbeddingMode.REBUILD_ALL);
    verify(model, times(2)).embed(anyList());
  }
}
