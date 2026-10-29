package com.gen3.shortdescriptionjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.model.StoredAudiobook;
import com.gen3.shortdescriptionjob.provider.ShortDescriptionGenerator;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ShortDescriptionEnricherTest {
  private final AudiobookEnrichmentStore store = mock(AudiobookEnrichmentStore.class);
  private final ShortDescriptionGenerator generator = mock(ShortDescriptionGenerator.class);

  private StoredAudiobook book(int index) {
    return new StoredAudiobook(
        new AudiobookRecord("id-" + index, "s", "Title", List.of(), "Description"),
        "storage-" + index);
  }

  @Test
  void rebuildAllIncludesExistingSummariesAndPassesModeToWrites() throws Exception {
    var existing =
        new StoredAudiobook(
            new AudiobookRecord(
                "existing",
                "s",
                "Title",
                List.of(),
                "Description",
                List.of(),
                "en",
                60,
                2020,
                "Old summary"),
            "opaque-reference");
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.REBUILD_ALL))
        .thenReturn(List.of(existing));
    when(generator.generate(List.of(existing.record())))
        .thenReturn(Map.of("existing", "New summary"));
    new ShortDescriptionEnricher(store, generator, 100, 1, "rebuild-all").run(null);
    verify(store)
        .updateShortDescriptions(
            List.of(
                new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                    "opaque-reference", "New summary")),
            ShortDescriptionMode.REBUILD_ALL);
  }

  @Test
  void missingOnlyDefensivelySkipsExistingSummaries() throws Exception {
    var existing =
        new StoredAudiobook(
            new AudiobookRecord(
                "existing",
                "s",
                "Title",
                List.of(),
                "Description",
                List.of(),
                "en",
                60,
                2020,
                "Old summary"),
            "opaque-reference");
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(List.of(existing));
    new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null);
    verifyNoInteractions(generator);
    verify(store, never()).updateShortDescriptions(anyList(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void skipsEmptySelectionWithoutCallingAiOrWriting() throws Exception {
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(List.of());
    new ShortDescriptionEnricher(store, generator, 100, 5, "missing-only").run(null);
    verifyNoInteractions(generator);
    verify(store, never())
        .updateShortDescriptions(
            anyList(), org.mockito.ArgumentMatchers.eq(ShortDescriptionMode.MISSING_ONLY));
  }

  @Test
  void groupsTenBooksAndSavesEachCompletedRequestUsingOriginalStorageReferences() throws Exception {
    var books = IntStream.range(0, 23).mapToObj(this::book).toList();
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(books);
    when(generator.generate(anyList()))
        .thenAnswer(
            call -> {
              List<AudiobookRecord> input = call.getArgument(0);
              return input.stream()
                  .collect(
                      java.util.stream.Collectors.toMap(
                          AudiobookRecord::id, book -> "A concise summary."));
            });
    new ShortDescriptionEnricher(store, generator, 100, 2, "missing-only").run(null);
    ArgumentCaptor<List<AudiobookRecord>> groups = ArgumentCaptor.captor();
    verify(generator, times(3)).generate(groups.capture());
    assertThat(groups.getAllValues()).extracting(List::size).containsExactlyInAnyOrder(10, 10, 3);
    ArgumentCaptor<List<AudiobookEnrichmentStore.ShortDescriptionUpdate>> updates =
        ArgumentCaptor.captor();
    verify(store, times(3))
        .updateShortDescriptions(
            updates.capture(), org.mockito.ArgumentMatchers.eq(ShortDescriptionMode.MISSING_ONLY));
    var all =
        updates.getAllValues().stream()
            .flatMap(List::stream)
            .map(AudiobookEnrichmentStore.ShortDescriptionUpdate.class::cast)
            .toList();
    assertThat(all).hasSize(23);
    assertThat(all)
        .extracting(AudiobookEnrichmentStore.ShortDescriptionUpdate::storageReference)
        .containsExactlyInAnyOrderElementsOf(
            books.stream().map(StoredAudiobook::storageReference).toList());
  }

  @Test
  void preservesSavedRequestsAndStopsAfterGenerationFailure() throws Exception {
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(IntStream.range(0, 11).mapToObj(this::book).toList());
    when(generator.generate(anyList()))
        .thenAnswer(
            call -> {
              List<AudiobookRecord> input = call.getArgument(0);
              if (input.getFirst().id().equals("id-10")) {
                throw new IOException("provider failed");
              }
              return input.stream()
                  .collect(
                      java.util.stream.Collectors.toMap(AudiobookRecord::id, book -> "Summary"));
            });
    assertThatThrownBy(
            () -> new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null))
        .hasRootCauseMessage("provider failed");
    verify(store)
        .updateShortDescriptions(
            anyList(), org.mockito.ArgumentMatchers.eq(ShortDescriptionMode.MISSING_ONLY));
  }

  @Test
  void refusesMissingUnknownOrBlankSummariesBeforeWriting() throws Exception {
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(List.of(book(0)));
    for (var output :
        List.of(Map.<String, String>of(), Map.of("unknown", "Summary"), Map.of("id-0", " "))) {
      when(generator.generate(anyList())).thenReturn(output);
      assertThatThrownBy(
              () ->
                  new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null))
          .hasRootCauseInstanceOf(IOException.class);
    }
    verify(store, never())
        .updateShortDescriptions(
            anyList(), org.mockito.ArgumentMatchers.eq(ShortDescriptionMode.MISSING_ONLY));
  }

  @Test
  void enforcesTwentyWordLimit() throws Exception {
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(List.of(book(0)));
    when(generator.generate(anyList())).thenReturn(Map.of("id-0", "word ".repeat(30)));
    new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null);
    verify(store)
        .updateShortDescriptions(
            List.of(
                new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                    "storage-0", String.join(" ", java.util.Collections.nCopies(20, "word")))),
            ShortDescriptionMode.MISSING_ONLY);
  }

  @Test
  void rejectsDuplicateIdsBeforeCallingAi() throws Exception {
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(List.of(book(0), book(0)));
    assertThatThrownBy(
            () -> new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate bookId");
    verifyNoInteractions(generator);
  }

  @Test
  void boundsConcurrentCallsAndWritesOnTheRunnerThread() throws Exception {
    when(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
        .thenReturn(IntStream.range(0, 21).mapToObj(this::book).toList());
    var started = new CountDownLatch(2);
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    var runner = Thread.currentThread();
    when(generator.generate(anyList()))
        .thenAnswer(
            call -> {
              peak.accumulateAndGet(active.incrementAndGet(), Math::max);
              started.countDown();
              assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
              List<AudiobookRecord> input = call.getArgument(0);
              active.decrementAndGet();
              return input.stream()
                  .collect(
                      java.util.stream.Collectors.toMap(AudiobookRecord::id, book -> "Summary"));
            });
    doAnswer(
            call -> {
              assertThat(Thread.currentThread()).isSameAs(runner);
              return null;
            })
        .when(store)
        .updateShortDescriptions(
            anyList(), org.mockito.ArgumentMatchers.eq(ShortDescriptionMode.MISSING_ONLY));
    new ShortDescriptionEnricher(store, generator, 100, 2, "missing-only").run(null);
    assertThat(peak.get()).isEqualTo(2);
  }

  @Test
  void rejectsInvalidLimits() {
    assertThatThrownBy(() -> new ShortDescriptionEnricher(store, generator, 0, 5, "missing-only"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShortDescriptionEnricher(store, generator, 100, 0, "missing-only"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
