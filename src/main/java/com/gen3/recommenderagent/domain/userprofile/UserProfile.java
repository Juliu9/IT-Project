package com.gen3.recommenderagent.domain.userprofile;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

public class UserProfile {

    private String userId;

    private List<Preference> preferences = new ArrayList<>();

    public UserProfile() {
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public List<Preference> getPreferences() { return preferences; }
    public void setPreferences(List<Preference> preferences) { this.preferences = preferences; }

    public void addPreference(Preference preference) {
        preferences.add(preference);
        preference.setUserProfile(this);
    }
}