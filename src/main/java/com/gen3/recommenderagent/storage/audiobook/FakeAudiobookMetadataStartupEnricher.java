package com.gen3.recommenderagent.storage.audiobook;

import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adds only absent fake metadata to existing Qdrant records when explicitly enabled. */
@Component
@ConditionalOnProperty(name = "audiobook.fake-metadata.enrich-on-startup", havingValue = "true")
@ConditionalOnExpression("'${audiobook.short-description.batch.mode:disabled}' == 'disabled'")
public class FakeAudiobookMetadataStartupEnricher implements ApplicationRunner {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(FakeAudiobookMetadataStartupEnricher.class);

  private final QdrantAudiobookRepository qdrant;
  private final FakeAudiobookMetadataEnricher metadataEnricher;

  public FakeAudiobookMetadataStartupEnricher(
      QdrantAudiobookRepository qdrant, FakeAudiobookMetadataEnricher metadataEnricher) {
    this.qdrant = qdrant;
    this.metadataEnricher = metadataEnricher;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    int updated = qdrant.enrichMissingMetadata(metadataEnricher);
    LOGGER.info("Added missing fake metadata to {} Qdrant audiobooks", updated);
  }
}
