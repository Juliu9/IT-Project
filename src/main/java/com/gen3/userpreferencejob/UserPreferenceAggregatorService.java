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

  private final int maximumUsers;
  private final int maximumBookIds;

  public UserPreferenceAggregatorService(
      AudiobookVectorRepository audiobookVectorRepository,
      UserPreferenceVectorRepository userPreferenceVectorRepository) {
    this(audiobookVectorRepository, userPreferenceVectorRepository, 100, 10_000);
  }

  @Autowired
  public UserPreferenceAggregatorService(
      AudiobookVectorRepository audiobookVectorRepository,
      UserPreferenceVectorRepository userPreferenceVectorRepository,
      @Value("${user.preferences.import.max-users-per-group:100}") int maximumUsers,
      @Value("${user.preferences.import.max-book-ids-per-group:10000}") int maximumBookIds) {
    if (maximumUsers < 1 || maximumBookIds < 1) {
      throw new IllegalArgumentException("Preference import group limits must be positive");
    }
    this.maximumUsers = maximumUsers;
    this.maximumBookIds = maximumBookIds;
    this.audiobookVectorRepository = audiobookVectorRepository;
    this.userPreferenceVectorRepository = userPreferenceVectorRepository;
  }

  public void aggregateAndSave(List<UserFavourite> favourites, List<UserHistory> history)
      throws IOException {
    // Group by User ID
    Map<String, List<UserFavourite>> favsByUser =
        favourites.stream().collect(Collectors.groupingBy(UserFavourite::userId));
    Map<String, List<UserHistory>> histByUser =
        history.stream().collect(Collectors.groupingBy(UserHistory::userId));

    Set<String> allUserIds = new TreeSet<>();
    allUserIds.addAll(favsByUser.keySet());
    allUserIds.addAll(histByUser.keySet());
    // Validate the entire input before any writes; never truncate one user's history.
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
    }
    Instant now = Instant.now();
    List<String> group = new ArrayList<>();
    Set<String> groupBooks = new LinkedHashSet<>();
    for (String userId : allUserIds) {
      Set<String> ids =
          bookIds(
              favsByUser.getOrDefault(userId, List.of()),
              histByUser.getOrDefault(userId, List.of()));
      long additions = ids.stream().filter(id -> !groupBooks.contains(id)).count();
      if (!group.isEmpty()
          && (group.size() == maximumUsers || groupBooks.size() + additions > maximumBookIds)) {
        aggregateGroup(group, groupBooks, favsByUser, histByUser, now);
        group.clear();
        groupBooks.clear();
      }
      group.add(userId);
      groupBooks.addAll(ids);
    }
    if (!group.isEmpty()) {
      aggregateGroup(group, groupBooks, favsByUser, histByUser, now);
    }
  }

  private void aggregateGroup(
      List<String> userIds,
      Set<String> bookIds,
      Map<String, List<UserFavourite>> favsByUser,
      Map<String, List<UserHistory>> histByUser,
      Instant now)
      throws IOException {
    Map<String, float[]> vectors = audiobookVectorRepository.findEmbeddingsByBookIds(bookIds);
    List<UserPreferenceEmbedding> pending = new ArrayList<>();
    for (String userId : userIds) {
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

      // 3. Save through the selected database adapter
      if (aggregatedFavs.length > 0 || aggregatedHist.length > 0) {
        UserPreferenceEmbedding embedding =
            new UserPreferenceEmbedding(userId, aggregatedFavs, aggregatedHist);
        pending.add(embedding);
        if (pending.size() == 500) {
          userPreferenceVectorRepository.saveAll(List.copyOf(pending));
          pending.clear();
        }
      }
    }
    if (!pending.isEmpty()) {
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
