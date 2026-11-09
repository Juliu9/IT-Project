package com.gen3.recommenderagent.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.user.model.SearchHistoryProfile;
import com.gen3.recommenderagent.storage.user.port.SearchHistoryRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SearchHistoryPreferencesTest {

  private final InMemoryRepository repository = new InMemoryRepository();
  private final SearchHistoryPreferences preferences =
      new SearchHistoryPreferences(Optional.of(repository), 0.2, 3);

  @Test
  void switchedOffStoresAndLoadsNothing() {
    var switchedOff = new SearchHistoryPreferences(Optional.empty(), 0.2, 3);

    switchedOff.record("u", "r", new float[] {1, 0});

    assertThat(switchedOff.load("u", "r")).isEmpty();
  }

  @Test
  void anonymousUsersAreNotTracked() {
    preferences.record(null, "r", new float[] {1, 0});
    preferences.record(" ", "r", new float[] {1, 0});

    assertThat(repository.profiles).isEmpty();
  }

  @Test
  void firstSearchStartsTheTaste() {
    preferences.record("u", "r", new float[] {3, 4});

    var profile = repository.profiles.get("u");
    assertThat(profile.searchCount()).isEqualTo(1);
    assertThat(profile.tasteVector()).containsExactly(new float[] {0.6f, 0.8f}, within(1e-6f));
  }

  @Test
  void tasteOnlyCountsAfterMinimumSearches() {
    preferences.record("u", "r", new float[] {1, 0});
    preferences.record("u", "r", new float[] {1, 0});
    assertThat(preferences.load("u", "r")).isEmpty();

    preferences.record("u", "r", new float[] {1, 0});
    assertThat(preferences.load("u", "r")).isPresent();
  }

  @Test
  void newSearchTakesItsShareAndOlderSearchesFade() {
    preferences.record("u", "r", new float[] {1, 0});
    preferences.record("u", "r", new float[] {0, 1});

    // 80% old taste + 20% new search, then scaled back to length 1.
    float length = (float) Math.sqrt(0.8 * 0.8 + 0.2 * 0.2);
    assertThat(repository.profiles.get("u").tasteVector())
        .containsExactly(new float[] {0.8f / length, 0.2f / length}, within(1e-6f));
  }

  @Test
  void changedVectorSizeStartsTheTasteAgain() {
    preferences.record("u", "r", new float[] {1, 0});
    preferences.record("u", "r", new float[] {1, 0, 0});

    var profile = repository.profiles.get("u");
    assertThat(profile.searchCount()).isEqualTo(1);
    assertThat(profile.tasteVector()).hasSize(3);
  }

  @Test
  void storeOutageFailsOpenWithoutLoggingDetails(CapturedOutput output) throws Exception {
    var broken = mock(SearchHistoryRepository.class);
    when(broken.findByUserId(any())).thenThrow(new IOException("secret-password"));
    var service = new SearchHistoryPreferences(Optional.of(broken), 0.2, 3);

    service.record("u", "request-123", new float[] {1, 0});

    assertThat(service.load("u", "request-456")).isEmpty();
    assertThat(output)
        .contains("requestId=request-123", "requestId=request-456", "reason=STORE_UNAVAILABLE")
        .doesNotContain("secret-password");
  }

  @Test
  void invalidSettingsStopStartup() {
    assertThatThrownBy(() -> new SearchHistoryPreferences(Optional.empty(), 0, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SearchHistoryPreferences(Optional.empty(), 1.5, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SearchHistoryPreferences(Optional.empty(), 0.2, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // A pretend drawer that keeps profiles in memory, so these tests don't need Redis.
  private static final class InMemoryRepository implements SearchHistoryRepository {
    private final Map<String, SearchHistoryProfile> profiles = new HashMap<>();

    @Override
    public Optional<SearchHistoryProfile> findByUserId(String userId) {
      return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public void save(SearchHistoryProfile profile) {
      profiles.put(profile.userId(), profile);
    }

    @Override
    public void delete(String userId) {
      profiles.remove(userId);
    }
  }
}
