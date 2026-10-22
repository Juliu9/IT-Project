package com.gen3.shortdescriptionjob.provider;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Provider-specific batch format and transport; no database references cross this boundary. */
public interface ShortDescriptionBatchProvider {
  String id();

  /** Upload input without starting generation, so a failed upload can safely be retried. */
  String upload(List<AudiobookRecord> books, Path directory)
      throws IOException, InterruptedException;

  /**
   * Start generation; a failed call can have an unknown outcome and must not be retried blindly.
   */
  String submit(String inputId) throws IOException, InterruptedException;

  /** Return descriptions keyed by book ID; throw if the batch is not completed. */
  Map<String, String> completedDescriptions(String batchId)
      throws IOException, InterruptedException;
}
