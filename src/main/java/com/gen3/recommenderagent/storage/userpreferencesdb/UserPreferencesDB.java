package com.gen3.recommenderagent.storage.userpreferencesdb;

import com.gen3.recommenderagent.domain.userprofile.Preference;
import com.gen3.recommenderagent.domain.userprofile.UserProfile;

public interface UserPreferencesDB {

    UserProfile getUserProfile(String userId);

    void updatePreference(String userId, Preference preference);

    // void saveRecommendation(String userId, RecommendationRecord recommendation);

    // void saveFeedback(String userId, FeedbackRecord feedback);
}

