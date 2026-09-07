package com.gen3.recommenderagent.storage.userprofiledb;

import com.gen3.recommenderagent.domain.userprofile.Preference;
import com.gen3.recommenderagent.domain.userprofile.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class UserProfileDBTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserProfileDB userProfileDB;

    @Test
    void shouldSaveAndRetrieveUserProfile() {
        UserProfile profile = new UserProfile();
        profile.setUserId("user-123");

        userProfileDB.saveAndFlush(profile);

        assertThat(userProfileDB.findById("user-123"))
                .isPresent()
                .hasValueSatisfying(retrieved ->
                        assertThat(retrieved.getUserId()).isEqualTo("user-123")
                );
    }

    @Test
    void shouldSaveAndRetrievePreferences() {
        UserProfile profile = new UserProfile();
        profile.setUserId("user-preferences-123");

        Preference preference = new Preference();
        preference.setAttributeType("genre");
        preference.setAttribute("fantasy");
        preference.setPreferenceScore(0.9);
        preference.setConfidence(0.8);

        profile.addPreference(preference);
        userProfileDB.saveAndFlush(profile);

        assertThat(userProfileDB.findById("user-preferences-123"))
                .isPresent()
                .hasValueSatisfying(retrieved -> {
                    assertThat(retrieved.getPreferences()).hasSize(1);
                    Preference retrievedPreference = retrieved.getPreferences().get(0);
                    assertThat(retrievedPreference.getAttributeType()).isEqualTo("genre");
                    assertThat(retrievedPreference.getAttribute()).isEqualTo("fantasy");
                    assertThat(retrievedPreference.getPreferenceScore()).isEqualTo(0.9);
                    assertThat(retrievedPreference.getConfidence()).isEqualTo(0.8);
                });
    }

    @Test
    void shouldSaveMultiplePreferences() {
        UserProfile profile = new UserProfile();
        profile.setUserId("user-multiple-123");

        Preference fantasy = new Preference();
        fantasy.setAttributeType("genre");
        fantasy.setAttribute("fantasy");

        Preference mystery = new Preference();
        mystery.setAttributeType("genre");
        mystery.setAttribute("mystery");

        profile.addPreference(fantasy);
        profile.addPreference(mystery);
        userProfileDB.saveAndFlush(profile);

        assertThat(userProfileDB.findById("user-multiple-123"))
                .isPresent()
                .hasValueSatisfying(retrieved ->
                        assertThat(retrieved.getPreferences()).hasSize(2)
                );
    }

    @Test
    void shouldUpdatePreference() {
        UserProfile profile = new UserProfile();
        profile.setUserId("user-update-123");

        Preference preference = new Preference();
        preference.setAttributeType("genre");
        preference.setAttribute("fantasy");
        preference.setPreferenceScore(0.9);

        profile.addPreference(preference);
        userProfileDB.saveAndFlush(profile);

        preference.setPreferenceScore(0.5);
        userProfileDB.saveAndFlush(profile);

        assertThat(userProfileDB.findById("user-update-123"))
                .isPresent()
                .hasValueSatisfying(retrieved ->
                        assertThat(retrieved.getPreferences().get(0).getPreferenceScore()).isEqualTo(0.5)
                );
    }

    @Test
    void shouldDeleteProfileAndPreferences() {
        UserProfile profile = new UserProfile();
        profile.setUserId("user-delete-123");

        Preference preference = new Preference();
        preference.setAttributeType("genre");
        preference.setAttribute("fantasy");

        profile.addPreference(preference);
        userProfileDB.saveAndFlush(profile);

        assertThat(userProfileDB.findById("user-delete-123")).isPresent();

        userProfileDB.deleteById("user-delete-123");
        userProfileDB.flush();

        assertThat(userProfileDB.findById("user-delete-123")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnknownUser() {
        assertThat(userProfileDB.findById("does-not-exist")).isEmpty();
    }
}
