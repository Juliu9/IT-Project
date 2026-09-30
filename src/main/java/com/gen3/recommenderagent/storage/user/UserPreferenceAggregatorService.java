package com.gen3.recommenderagent.storage.user;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.model.UserFavourite;
import com.gen3.recommenderagent.storage.user.model.UserHistory;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserPreferenceAggregatorService {

    private final AudiobookVectorRepository audiobookVectorRepository;
    private final UserPreferenceVectorRepository userPreferenceVectorRepository;

    public UserPreferenceAggregatorService(
            AudiobookVectorRepository audiobookVectorRepository,
            UserPreferenceVectorRepository userPreferenceVectorRepository) {
        this.audiobookVectorRepository = audiobookVectorRepository;
        this.userPreferenceVectorRepository = userPreferenceVectorRepository;
    }

    public void aggregateAndSave(List<UserFavourite> favourites, List<UserHistory> history) throws IOException {
        // Group by User ID
        Map<String, List<UserFavourite>> favsByUser = favourites.stream()
                .collect(Collectors.groupingBy(UserFavourite::userId));
        Map<String, List<UserHistory>> histByUser = history.stream()
                .collect(Collectors.groupingBy(UserHistory::userId));

        Set<String> allUserIds = new HashSet<>();
        allUserIds.addAll(favsByUser.keySet());
        allUserIds.addAll(histByUser.keySet());

        Instant now = Instant.now();

        for (String userId : allUserIds) {
            // 1. Process Favourites (Standard Average)
            List<float[]> favVectors = new ArrayList<>();
            for (UserFavourite fav : favsByUser.getOrDefault(userId, List.of())) {
                audiobookVectorRepository.findEmbeddingByBookId(fav.bookId()).ifPresent(favVectors::add);
            }
            float[] aggregatedFavs = VectorMath.average(favVectors);

            // 2. Process History (Time-Weighted Average)
            List<float[]> histVectors = new ArrayList<>();
            List<Double> histWeights = new ArrayList<>();

            for (UserHistory hist : histByUser.getOrDefault(userId, List.of())) {
                Optional<float[]> vectorOpt = audiobookVectorRepository.findEmbeddingByBookId(hist.bookId());
                if (vectorOpt.isPresent()) {
                    histVectors.add(vectorOpt.get());

                    // Calculate weight based on the LAST interaction
                    long daysAgo = Duration.between(hist.lastInteractionTime(), now).toDays();
                    daysAgo = Math.max(0, daysAgo); // Prevent negative days if clocks are out of sync

                    // Exponential decay: weight halves every 180 days
                    double weight = Math.pow(0.5, daysAgo / 180.0);
                    histWeights.add(weight);
                }
            }
            float[] aggregatedHist = VectorMath.weightedAverage(histVectors, histWeights);

            // 3. Save to Qdrant
            if (aggregatedFavs.length > 0 || aggregatedHist.length > 0) {
                UserPreferenceEmbedding embedding = new UserPreferenceEmbedding(userId, aggregatedFavs, aggregatedHist);
                userPreferenceVectorRepository.save(embedding);
            }
        }
    }
}