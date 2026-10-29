package com.gen3.shortdescriptionjob.provider;

import com.gen3.catalogue.model.AudiobookRecord;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Generates summaries keyed by book ID without receiving database-specific references. */
public interface ShortDescriptionGenerator {
  Map<String, String> generate(List<AudiobookRecord> books)
      throws IOException, InterruptedException;
}
