package com.gen3.recommenderagent.storage.user.importer;

import com.gen3.recommenderagent.storage.user.UserPreferenceAggregatorService;
import com.gen3.recommenderagent.storage.user.model.UserFavourite;
import com.gen3.recommenderagent.storage.user.model.UserHistory;
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
    LOGGER.info("Starting User Preference Vector Aggregation...");

    List<UserFavourite> favourites = favouriteCsvReader.read(Path.of(favouritesCsvPath));
    List<UserHistory> history = historyCsvReader.read(Path.of(historyCsvPath));

    aggregatorService.aggregateAndSave(favourites, history);

    LOGGER.info("Successfully aggregated and saved user preference vectors to Qdrant.");
  }
}
