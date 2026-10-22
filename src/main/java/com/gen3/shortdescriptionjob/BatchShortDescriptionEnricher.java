package com.gen3.shortdescriptionjob;

import com.gen3.recommenderagent.storage.audiobook.model.StoredAudiobook;
import com.gen3.shortdescriptionjob.provider.ShortDescriptionBatchProvider;
import com.gen3.shortdescriptionjob.store.AudiobookEnrichmentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates and imports provider batch jobs that add one short, spoiler-free description per book.
 * This job is explicitly opt-in and never runs on normal application startup.
 */
@Component
@ConditionalOnExpression("'${audiobook.short-description.batch.mode:disabled}' != 'disabled'")
public class BatchShortDescriptionEnricher implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(BatchShortDescriptionEnricher.class);
  private static final int MAX_DESCRIPTION_WORDS = 20;
  private final AudiobookEnrichmentStore enrichmentStore;
  private final ObjectMapper mapper;
  private final ShortDescriptionBatchProvider provider;
  private final String mode;
  private final String batchId;
  private final Path outputDirectory;
  private final int maximumRecords;

  @Autowired
  public BatchShortDescriptionEnricher(
      AudiobookEnrichmentStore enrichmentStore,
      ObjectMapper mapper,
      ShortDescriptionBatchProvider provider,
      @Value("${audiobook.short-description.batch.mode:disabled}") String mode,
      @Value("${audiobook.short-description.batch.id:}") String batchId,
      @Value("${audiobook.short-description.batch.output-directory:./data/short-descriptions}")
          Path outputDirectory,
      @Value("${audiobook.short-description.batch.maximum-records:100000}") int maximumRecords) {
    this.enrichmentStore = enrichmentStore;
    this.mapper = mapper;
    this.provider = provider;
    this.mode = mode;
    this.batchId = batchId;
    this.outputDirectory = outputDirectory;
    this.maximumRecords = Math.max(1, maximumRecords);
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    if ("disabled".equalsIgnoreCase(mode)) {
      return;
    }
    if ("submit".equalsIgnoreCase(mode)) {
      submit();
      return;
    }
    if ("import".equalsIgnoreCase(mode)) {
      importCompletedBatch();
      return;
    }
    throw new IllegalArgumentException("Unknown audiobook.short-description.batch.mode: " + mode);
  }

  private void submit() throws IOException, InterruptedException {
    Path manifest = manifestPath();
    if (Files.exists(manifest)) {
      throw new IllegalStateException(
          "A short-description batch is still pending at "
              + manifest
              + ". Import it before submitting another batch.");
    }
    List<StoredAudiobook> books =
        enrichmentStore.findRecordsWithoutShortDescription(maximumRecords);
    Files.createDirectories(outputDirectory);
    List<StoredAudiobook> validBooks = validBooks(books);
    int records = validBooks.size();
    if (records == 0) {
      LOGGER.info("No audiobook descriptions were available for short-description enrichment");
      return;
    }

    // Uploads cannot create a generation batch, so upload failure is safe to retry.
    String inputId =
        provider.upload(validBooks.stream().map(StoredAudiobook::record).toList(), outputDirectory);
    // Once submission starts, an uncertain outcome must retain the record references.
    writeManifest(manifest, "", validBooks);
    String submittedBatchId;
    try {
      submittedBatchId = provider.submit(inputId);
    } catch (IOException exception) {
      throw new IOException(
          "Batch submission may have been accepted. The pending manifest is retained. Reconcile the provider's batch ID and set AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID to import it before retrying.",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      InterruptedException interrupted =
          new InterruptedException(
              "Batch submission was interrupted; its outcome is unknown. The pending manifest is retained. Reconcile the provider's batch ID before retrying.");
      interrupted.initCause(exception);
      throw interrupted;
    }
    writeManifest(manifest, submittedBatchId, validBooks);
    LOGGER.info(
        "Submitted short-description batch {} for {} records. Set AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import after it completes.",
        submittedBatchId,
        records);
  }

  private List<StoredAudiobook> validBooks(List<StoredAudiobook> records) {
    var valid = records.stream().filter(AudiobookEnrichmentStore::eligible).toList();
    var ids = new java.util.HashSet<String>();
    for (var book : valid) {
      if (!ids.add(book.record().id())) {
        throw new IllegalArgumentException(
            "Duplicate bookId in summary batch: " + book.record().id());
      }
    }
    return valid;
  }

  private void importCompletedBatch() throws IOException, InterruptedException {
    BatchManifest manifest = readManifest();
    String importedBatchId = hasText(batchId) ? batchId : manifest.batchId();
    if (!hasText(importedBatchId)) {
      throw new IllegalArgumentException(
          "audiobook.short-description.batch.id is required because the pending manifest has no batch ID");
    }
    if (hasText(manifest.batchId()) && !manifest.batchId().equals(importedBatchId)) {
      throw new IllegalArgumentException(
          "Configured batch ID does not match the pending short-description batch");
    }
    Map<String, String> descriptions = provider.completedDescriptions(importedBatchId);
    List<AudiobookEnrichmentStore.ShortDescriptionUpdate> updates =
        descriptions.entrySet().stream()
            .filter(
                entry ->
                    manifest.storageReferences().containsKey(entry.getKey())
                        && hasText(entry.getValue()))
            .map(
                entry ->
                    new AudiobookEnrichmentStore.ShortDescriptionUpdate(
                        manifest.storageReferences().get(entry.getKey()),
                        trimToWordLimit(entry.getValue())))
            .toList();
    enrichmentStore.updateShortDescriptions(updates);
    Files.delete(manifestPath());
    LOGGER.info("Imported {} generated short audiobook descriptions", updates.size());
  }

  private Path manifestPath() {
    return outputDirectory.resolve("short-description-batch.json");
  }

  void writeManifest(Path manifest, String submittedBatchId, List<StoredAudiobook> books)
      throws IOException {
    Map<String, String> storageReferences = new LinkedHashMap<>();
    for (StoredAudiobook book : books) {
      storageReferences.put(book.record().id(), book.storageReference());
    }
    Path temporary =
        Files.createTempFile(manifest.toAbsolutePath().getParent(), "batch-manifest-", ".tmp");
    try {
      mapper.writeValue(
          temporary.toFile(),
          new StoredBatchManifest(
              submittedBatchId,
              enrichmentStore.datasetIdentity(),
              storageReferences,
              provider.id()));
      Files.move(
          temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private BatchManifest readManifest() throws IOException {
    Path manifest = manifestPath();
    if (!Files.exists(manifest)) {
      throw new IllegalStateException(
          "No pending short-description batch manifest was found at "
              + manifest
              + ". Import cannot safely identify the stored audiobooks.");
    }
    StoredBatchManifest stored = mapper.readValue(manifest.toFile(), StoredBatchManifest.class);
    if (stored.origin() == null) {
      throw new IllegalStateException(
          "Legacy batch manifest has no database identity. Import is refused; the manifest is unchanged. Restore its originating configuration and recover the batch explicitly before submitting another.");
    }
    // Existing manifests were produced only by OpenAI.
    String storedProvider = hasText(stored.provider()) ? stored.provider() : "openai";
    if (!storedProvider.equals(provider.id())) {
      throw new IllegalStateException(
          "Batch provider does not match the selected provider. Restore its originating configuration before importing; the manifest is unchanged.");
    }
    if (!stored.origin().equals(enrichmentStore.datasetIdentity())) {
      throw new IllegalStateException(
          "Batch database/dataset does not match the selected database. Restore its originating configuration before importing; the manifest is unchanged.");
    }
    return new BatchManifest(stored.batchId(), stored.pointIds());
  }

  Map<String, String> pendingStorageReferences() throws IOException {
    return readManifest().storageReferences();
  }

  private record StoredBatchManifest(
      String batchId,
      AudiobookEnrichmentStore.DatasetIdentity origin,
      Map<String, String> pointIds,
      String provider) {}

  private record BatchManifest(String batchId, Map<String, String> storageReferences) {}

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimToWordLimit(String description) {
    String[] words = description.trim().split("\\s+");
    return String.join(
        " ", java.util.Arrays.copyOf(words, Math.min(words.length, MAX_DESCRIPTION_WORDS)));
  }
}
