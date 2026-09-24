package com.gen3.recommenderagent.storage.audiobook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies predictable temporary metadata used while the source catalogue lacks these fields. */
class FakeAudiobookMetadataEnricherTest {

  /** Generates stable values for a book ID and leaves real values untouched. */
  @Test
  void generatesStableValuesAndPreservesExistingMetadata() {
    FakeAudiobookMetadataEnricher enricher = new FakeAudiobookMetadataEnricher();
    AudiobookRecord missing =
        new AudiobookRecord("book-1", "catalogue", "Title", List.of("Author"), "Summary");
    AudiobookRecord complete =
        new AudiobookRecord(
            "book-2",
            "catalogue",
            "Title",
            List.of("Author"),
            "Summary",
            List.of("Real Narrator"),
            "German",
            123);

    assertThat(enricher.enrich(missing)).isEqualTo(enricher.enrich(missing));
    assertThat(enricher.enrich(missing).narrators()).isNotEmpty();
    assertThat(enricher.enrich(missing).language()).isNotBlank();
    assertThat(enricher.enrich(missing).durationMinutes()).isPositive();
    assertThat(enricher.enrich(complete)).isEqualTo(complete);
  }
}
