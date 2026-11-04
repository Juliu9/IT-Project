package com.gen3.readhistoryjob;

import com.gen3.shared.csv.UserHistoryCsvReader;
import com.gen3.shared.jobruntime.JobProgress;
import com.gen3.shared.readhistory.port.ReadHistoryWriter;
import com.gen3.shared.readhistory.port.ReadHistoryWriter.ReadBook;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Streams history independently of preference aggregation, catalogue access, or embeddings. */
public final class ReadHistoryImporter {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReadHistoryImporter.class);
  private final UserHistoryCsvReader reader;
  private final ReadHistoryWriter writer;

  /** Connects the streaming history reader to the batched read-history writer. */
  public ReadHistoryImporter(UserHistoryCsvReader reader, ReadHistoryWriter writer) {
    this.reader = reader;
    this.writer = writer;
  }

  /**
   * Streams CSV book interactions and writes bounded batches; earlier batches survive later
   * failures.
   */
  public long run(Path path, int batchSize) throws IOException {
    if (batchSize < 1 || batchSize > 100_000) {
      throw new IllegalArgumentException("Read-history batch size must be between 1 and 100000");
    }
    List<ReadBook> batch = new ArrayList<>();
    long[] processed = {0};
    try (var progress = new JobProgress("read.history.import")) {
      progress.stage("reading CSV and adding history");
      reader.read(
          path,
          record -> {
            batch.add(new ReadBook(record.userId(), record.bookId()));
            if (batch.size() == batchSize) {
              writer.addReadBooks(batch);
              processed[0] += batch.size();
              batch.clear();
              progress.update(processed[0], -1);
              LOGGER.info("read.history.import.progress committedRows={}", processed[0]);
            }
          });
      if (!batch.isEmpty()) {
        writer.addReadBooks(batch);
        processed[0] += batch.size();
      }
      progress.update(processed[0], processed[0]);
      LOGGER.info("read.history.import.completed processedRows={} mode=ADDITIVE", processed[0]);
      return processed[0];
    }
  }
}
