package com.gen3.recommenderagent.storage.audiobook;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Supplies deterministic fake metadata until narrator, language, and duration are available from
 * the source catalogue.
 */
@Component
public class FakeAudiobookMetadataEnricher implements AudiobookMetadataEnricher {

  private static final List<String> NARRATORS =
      List.of("Stephen Fry", "Julia Whelan", "Ray Porter", "Bahni Turpin", "Simon Vance");
  private static final List<String> LANGUAGES = List.of("English", "French", "Spanish");

  /** Fills only missing values and produces the same fake values for the same audiobook ID. */
  @Override
  public AudiobookRecord enrich(AudiobookRecord record) {
    int seed = Math.floorMod(String.valueOf(record.id()).hashCode(), Integer.MAX_VALUE);
    List<String> narrators =
        record.narrators() == null || record.narrators().isEmpty()
            ? List.of(NARRATORS.get(seed % NARRATORS.size()))
            : record.narrators();
    String language =
        record.language() == null || record.language().isBlank()
            ? LANGUAGES.get(seed % LANGUAGES.size())
            : record.language();
    Integer duration =
        record.durationMinutes() == null || record.durationMinutes() <= 0
            ? 240 + (seed % 721)
            : record.durationMinutes();

    return new AudiobookRecord(
        record.id(),
        record.source(),
        record.title(),
        record.authors(),
        record.description(),
        narrators,
        language,
        duration);
  }
}
