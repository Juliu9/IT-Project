package com.gen3.userpreferencejob;

import com.gen3.userpreferencejob.model.UserFavourite;
import com.gen3.userpreferencejob.model.UserHistory;
import java.nio.file.Path;
import java.util.List;
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

  private final UserFavouriteCsvReader favouriteCsvReader;
  private final UserHistoryCsvReader historyCsvReader;
  private final UserPreferenceAggregatorService aggregatorService;

  @Value("${user.preferences.favourites-csv-path}")
  private String favouritesCsvPath;

  @Value("${user.preferences.history-csv-path}")
  private String historyCsvPath;

  @Value("${user.preferences.import.mode:dry-run}")
  private String mode;

  public UserPreferenceStartupImporter(
      UserFavouriteCsvReader favouriteCsvReader,
      UserHistoryCsvReader historyCsvReader,
      UserPreferenceAggregatorService aggregatorService) {
    this.favouriteCsvReader = favouriteCsvReader;
    this.historyCsvReader = historyCsvReader;
    this.aggregatorService = aggregatorService;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    long started = System.nanoTime();
    LOGGER.info("Starting User Preference Vector Aggregation... mode={}", mode);

    List<UserFavourite> favourites = favouriteCsvReader.read(Path.of(favouritesCsvPath));
    List<UserHistory> history = historyCsvReader.read(Path.of(historyCsvPath));
    long readMs = (System.nanoTime() - started) / 1_000_000;
    long aggregationStarted = System.nanoTime();

    aggregatorService.aggregateAndSave(favourites, history);

    LOGGER.info(
        "user.preferences.import.completed mode={} favourites={} history={} csvReadMs={} aggregationMs={} totalMs={}",
        mode,
        favourites.size(),
        history.size(),
        readMs,
        (System.nanoTime() - aggregationStarted) / 1_000_000,
        (System.nanoTime() - started) / 1_000_000);
    LOGGER.info(
        "dry-run".equals(mode)
            ? "Dry run completed. No user preference vectors were saved or cleared."
            : "Successfully aggregated and saved user preference vectors to the selected database.");
  }
}
