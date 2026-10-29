package com.gen3.userpreferencejob;

import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.catalogue.vector.VectorMath;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.userpreferencejob.model.UserFavourite;
import com.gen3.userpreferencejob.model.UserHistory;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UserPreferenceAggregatorService {

  private final AudiobookVectorRepository audiobookVectorRepository;
  private final UserPreferenceVectorRepository userPreferenceVectorRepository;

  private final int maximumBookIds;

  public UserPreferenceAggregatorService(
      AudiobookVectorRepository audiobookVectorRepository,
      UserPreferenceVectorRepository userPreferenceVectorRepository) {
    this(audiobookVectorRepository, userPreferenceVectorRepository, 100_000);
  }

  @Autowired
  public UserPreferenceAggregatorService(
      AudiobookVectorRepository audiobookVectorRepository,
      UserPreferenceVectorRepository userPreferenceVectorRepository,
      @Value("${user.preferences.import.max-book-ids-per-group:100000}") int maximumBookIds) {
    if (maximumBookIds < 1) {
      throw new IllegalArgumentException("Preference import book-ID limit must be positive");
    }
    this.maximumBookIds = maximumBookIds;
    this.audiobookVectorRepository = audiobookVectorRepository;
    this.userPreferenceVectorRepository = userPreferenceVectorRepository;
  }

  public void aggregateAndSave(List<UserFavourite> favourites, List<UserHistory> history)
      throws IOException {
    aggregateAndSave(favourites, history, Instant.now());
  }

  public void aggregateAndSave(
      List<UserFavourite> favourites, List<UserHistory> history, Instant asOf) throws IOException {
    try (var progress = new com.gen3.jobruntime.JobProgress("user.import.aggregate")) {
      aggregateAndSave(favourites, history, progress, asOf);
    }
  }

  private void aggregateAndSave(
      List<UserFavourite> favourites,
      List<UserHistory> history,
      com.gen3.jobruntime.JobProgress progress,
      Instant now)
      throws IOException {
    progress.stage("grouping input records");
    // Group by User ID
    Map<String, List<UserFavourite>> favsByUser =
        favourites.stream().distinct().collect(Collectors.groupingBy(UserFavourite::userId));
    Map<String, List<UserHistory>> histByUser =
        history.stream().collect(Collectors.groupingBy(UserHistory::userId));

    Set<String> allUserIds = new TreeSet<>();
    allUserIds.addAll(favsByUser.keySet());
    allUserIds.addAll(histByUser.keySet());
    progress.stage("validating user histories");
    progress.update(0, allUserIds.size());
    // Validate the entire input before any writes; never truncate one user's history.
    int validated = 0;
    for (String userId : allUserIds) {
      int count =
          bookIds(
                  favsByUser.getOrDefault(userId, List.of()),
                  histByUser.getOrDefault(userId, List.of()))
              .size();
      if (count > maximumBookIds) {
        throw new IllegalArgumentException(
            "User "
                + userId
                + " references "
                + count
                + " distinct book IDs; configured limit is "
                + maximumBookIds);
      }
      progress.update(++validated, allUserIds.size());
    }
    progress.update(0, allUserIds.size());
    List<String> group = new ArrayList<>();
    Set<String> groupBooks = new LinkedHashSet<>();
    int processed = 0;
    for (String userId : allUserIds) {
      progress.stage("forming user groups");
      Set<String> ids =
          bookIds(
              favsByUser.getOrDefault(userId, List.of()),
              histByUser.getOrDefault(userId, List.of()));
      long additions = ids.stream().filter(id -> !groupBooks.contains(id)).count();
      if (!group.isEmpty() && groupBooks.size() + additions > maximumBookIds) {
        aggregateGroup(
            group, groupBooks, favsByUser, histByUser, now, progress, processed, allUserIds.size());
        processed += group.size();
        group.clear();
        groupBooks.clear();
      }
      group.add(userId);
      groupBooks.addAll(ids);
    }
    if (!group.isEmpty()) {
      aggregateGroup(
          group, groupBooks, favsByUser, histByUser, now, progress, processed, allUserIds.size());
    }
  }

  private void aggregateGroup(
      List<String> userIds,
      Set<String> bookIds,
      Map<String, List<UserFavourite>> favsByUser,
      Map<String, List<UserHistory>> histByUser,
      Instant now,
      com.gen3.jobruntime.JobProgress progress,
      int processed,
      int total)
      throws IOException {
    progress.stage("looking up local book vectors");
    Map<String, float[]> vectors = audiobookVectorRepository.findEmbeddingsByBookIds(bookIds);
    List<UserPreferenceEmbedding> pending = new ArrayList<>();
    List<String> usersToClear = new ArrayList<>();
    for (int index = 0; index < userIds.size(); index++) {
      String userId = userIds.get(index);
      progress.stage("calculating user vectors");
      progress.update(processed + index, total);
      // 1. Process Favourites (Standard Average)
      List<float[]> favVectors = new ArrayList<>();
      for (UserFavourite fav : favsByUser.getOrDefault(userId, List.of())) {
        addVector(favVectors, vectors.get(fav.bookId()));
      }
      float[] aggregatedFavs = VectorMath.average(favVectors);

      // 2. Process History (Time-Weighted Average)
      List<float[]> histVectors = new ArrayList<>();
      List<Double> histWeights = new ArrayList<>();

      for (UserHistory hist : histByUser.getOrDefault(userId, List.of())) {
        float[] vector = vectors.get(hist.bookId());
        if (vector != null && vector.length > 0) {
          histVectors.add(vector);

          // Calculate weight based on the LAST interaction
          long daysAgo = Duration.between(hist.lastInteractionTime(), now).toDays();
          daysAgo = Math.max(0, daysAgo); // Prevent negative days if clocks are out of sync

          // Exponential decay: weight halves every 180 days
          double weight = Math.pow(0.5, daysAgo / 180.0);
          histWeights.add(weight);
        }
      }
      float[] aggregatedHist = VectorMath.weightedAverage(histVectors, histWeights);

      if (aggregatedFavs.length == 0 && aggregatedHist.length == 0) {
        usersToClear.add(userId);
        if (usersToClear.size() == 500) {
          progress.stage("clearing user profiles");
          userPreferenceVectorRepository.clearAll(List.copyOf(usersToClear));
          usersToClear.clear();
        }
        continue;
      }
      pending.add(new UserPreferenceEmbedding(userId, aggregatedFavs, aggregatedHist));
      if (pending.size() == 500) {
        progress.stage("saving user profiles");
        userPreferenceVectorRepository.saveAll(List.copyOf(pending));
        pending.clear();
      }
    }
    progress.update(processed + userIds.size(), total);
    if (!usersToClear.isEmpty()) {
      progress.stage("clearing user profiles");
      userPreferenceVectorRepository.clearAll(List.copyOf(usersToClear));
    }
    if (!pending.isEmpty()) {
      progress.stage("saving user profiles");
      userPreferenceVectorRepository.saveAll(List.copyOf(pending));
    }
  }

  private Set<String> bookIds(List<UserFavourite> favourites, List<UserHistory> history) {
    Set<String> ids = new LinkedHashSet<>();
    favourites.stream().map(UserFavourite::bookId).forEach(ids::add);
    history.stream().map(UserHistory::bookId).forEach(ids::add);
    ids.removeIf(id -> id == null || id.isBlank());
    return ids;
  }

  private void addVector(List<float[]> vectors, float[] vector) {
    if (vector != null && vector.length > 0) {
      vectors.add(vector);
    }
  }
}
