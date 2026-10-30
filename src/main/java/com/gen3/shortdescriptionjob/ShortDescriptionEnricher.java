package com.gen3.shortdescriptionjob;

import com.gen3.catalogue.model.StoredAudiobook;
import com.gen3.shared.jobruntime.JobProgress;
import com.gen3.shortdescriptionjob.provider.ShortDescriptionGenerator;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Generates summaries in the selected mode and saves each completed request. */
@Component
@ConditionalOnProperty(name = "audiobook.short-description.enabled", havingValue = "true")
public class ShortDescriptionEnricher implements ApplicationRunner {
  private static final int BOOKS_PER_REQUEST = 10;
  private final AudiobookEnrichmentStore store;
  private final ShortDescriptionGenerator generator;
  private final int maximumRecords;
  private final int concurrency;
  private final ShortDescriptionMode mode;

  public ShortDescriptionEnricher(
      AudiobookEnrichmentStore store,
      ShortDescriptionGenerator generator,
      @Value("${audiobook.short-description.maximum-records:100000}") int maximumRecords,
      @Value("${audiobook.short-description.concurrency:5}") int concurrency,
      @Value("${audiobook.short-description.mode:missing-only}") String mode) {
    if (maximumRecords < 1 || concurrency < 1) {
      throw new IllegalArgumentException(
          "Short-description maximum-records and concurrency must be positive");
    }
    this.store = store;
    this.generator = generator;
    this.maximumRecords = maximumRecords;
    this.concurrency = concurrency;
    this.mode = ShortDescriptionMode.parse(mode);
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    try (var progress = new JobProgress("short.descriptions")) {
      progress.stage("selecting books for " + mode);
      var books =
          store.findRecordsForShortDescription(maximumRecords, mode).stream()
              .filter(AudiobookEnrichmentStore::eligible)
              .filter(
                  book ->
                      mode == ShortDescriptionMode.REBUILD_ALL
                          || book.record().shortDescription() == null
                          || book.record().shortDescription().isBlank())
              .toList();
      var ids = new HashSet<String>();
      for (var book : books) {
        if (!ids.add(book.record().id())) {
          throw new IllegalArgumentException(
              "Duplicate bookId in short-description input: " + book.record().id());
        }
      }
      progress.update(0, books.size());
      if (books.isEmpty()) {
        LoggerFactory.getLogger(getClass())
            .info("No audiobook descriptions were available for short-description enrichment");
        return;
      }
      var executor = Executors.newFixedThreadPool(concurrency);
      var completions =
          new ExecutorCompletionService<List<AudiobookEnrichmentStore.ShortDescriptionUpdate>>(
              executor);
      var pending = new HashSet<Future<List<AudiobookEnrichmentStore.ShortDescriptionUpdate>>>();
      int next = 0;
      int processed = 0;
      try {
        while (next < books.size() || !pending.isEmpty()) {
          while (next < books.size() && pending.size() < concurrency) {
            var group = books.subList(next, Math.min(next + BOOKS_PER_REQUEST, books.size()));
            pending.add(completions.submit(() -> generate(group)));
            next += group.size();
          }
          progress.stage("generating short descriptions");
          var completed = completions.take();
          pending.remove(completed);
          var updates = completed.get();
          progress.stage("writing short descriptions");
          store.updateShortDescriptions(updates, mode);
          processed += updates.size();
          progress.update(processed, books.size());
          LoggerFactory.getLogger(getClass())
              .info("short.description.progress processed={} total={}", processed, books.size());
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw exception;
      } finally {
        pending.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        executor.close();
      }
      LoggerFactory.getLogger(getClass())
          .info("Completed short-description generation for {} records", processed);
    }
  }

  private List<AudiobookEnrichmentStore.ShortDescriptionUpdate> generate(
      List<StoredAudiobook> books) throws IOException, InterruptedException {
    var descriptions = generator.generate(books.stream().map(StoredAudiobook::record).toList());
    var expected = new HashSet<String>();
    books.forEach(book -> expected.add(book.record().id()));
    if (descriptions == null
        || !descriptions.keySet().equals(expected)
        || descriptions.values().stream().anyMatch(text -> text == null || text.isBlank())) {
      throw new IOException(
          "Short-description output must contain exactly one nonblank summary for every requested book");
    }
    var updates = new ArrayList<AudiobookEnrichmentStore.ShortDescriptionUpdate>();
    for (var book : books) {
      var words = descriptions.get(book.record().id()).trim().split("\\s+");
      updates.add(
          new AudiobookEnrichmentStore.ShortDescriptionUpdate(
              book.storageReference(),
              String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 20)))));
    }
    return updates;
  }
}
