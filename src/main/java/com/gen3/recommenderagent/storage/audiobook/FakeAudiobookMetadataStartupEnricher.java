package com.gen3.recommenderagent.storage.audiobook;

import com.gen3.recommenderagent.storage.audiobook.port.AudiobookEnrichmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adds only absent fake metadata to existing audiobook records when explicitly enabled. */
@Component
@ConditionalOnProperty(name = "audiobook.fake-metadata.enrich-on-startup", havingValue = "true")
@ConditionalOnExpression("'${audiobook.short-description.batch.mode:disabled}' == 'disabled'")
public class FakeAudiobookMetadataStartupEnricher implements ApplicationRunner {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(FakeAudiobookMetadataStartupEnricher.class);

  private final AudiobookEnrichmentStore enrichmentStore;
  private final FakeAudiobookMetadataEnricher metadataEnricher;

  public FakeAudiobookMetadataStartupEnricher(
      AudiobookEnrichmentStore enrichmentStore, FakeAudiobookMetadataEnricher metadataEnricher) {
    this.enrichmentStore = enrichmentStore;
    this.metadataEnricher = metadataEnricher;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    int updated = enrichmentStore.enrichMissingMetadata(metadataEnricher);
    LOGGER.info("Added missing fake metadata to {} audiobooks", updated);
  }
}
