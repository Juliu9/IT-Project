package com.gen3.userpreferencejob;

import com.gen3.shared.jobruntime.JobProgress;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "user.preferences.import-on-startup", havingValue = "true")
public class UserPreferenceStartupImporter implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(UserPreferenceStartupImporter.class);

  private final StreamingUserPreferenceImporter importer;
  private final com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot snapshot;

  @Value("${user.preferences.favourites-csv-path}")
  private String favouritesCsvPath;

  @Value("${user.preferences.history-csv-path}")
  private String historyCsvPath;

  @Value("${user.preferences.import.mode:dry-run}")
  private String mode;

  @Value("${user.preferences.import.manifest-path:./data/user-import-work/approved.properties}")
  private Path manifestPath;

  public UserPreferenceStartupImporter(
      StreamingUserPreferenceImporter importer,
      com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot snapshot) {
    this.importer = importer;
    this.snapshot = snapshot;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    long started = System.nanoTime();
    LOGGER.info("Starting User Preference Vector Aggregation... mode={}", mode);
    long snapshotLoadMs;
    try (var progress = new JobProgress("user.import.prepare")) {
      progress.stage("validating and loading snapshot");
      snapshot.validate();
      snapshotLoadMs = (System.nanoTime() - started) / 1_000_000;
    }
    Path favourites = Path.of(favouritesCsvPath);
    Path history = Path.of(historyCsvPath);
    var manifest = new UserPreferenceImportManifest(manifestPath);
    var result =
        importer.run(
            favourites,
            history,
            plan -> manifest.approve(mode, favourites, history, snapshot, plan));

    LOGGER.info(
        "user.preferences.import.completed mode={} favourites={} history={} users={} uniqueRecords={} fullUpdates={} partialUpdates={} unmappedUsers={} unmappedReport={} snapshotLoadMs={} csvReadMs={} sortMs={} aggregationMs={} totalMs={}",
        mode,
        result.favourites(),
        result.history(),
        result.users(),
        result.uniqueRecords(),
        result.fullUpdates(),
        result.partialUpdates(),
        result.unmappedUsers(),
        result.unmappedReport(),
        snapshotLoadMs,
        result.csvReadMs(),
        result.sortMs(),
        result.aggregationMs(),
        (System.nanoTime() - started) / 1_000_000);
    LOGGER.info(
        "dry-run".equals(mode)
            ? "Dry run completed. No user preference vectors were changed."
            : "Successfully aggregated and saved user preference vectors to the selected database.");
  }
}
