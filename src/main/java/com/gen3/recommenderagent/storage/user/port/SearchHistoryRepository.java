package com.gen3.recommenderagent.storage.user.port;

import com.gen3.recommenderagent.storage.user.model.SearchHistoryProfile;
import java.io.IOException;
import java.util.Optional;

/** Saves and loads each user's search taste (MAPA-30). */
public interface SearchHistoryRepository {

  Optional<SearchHistoryProfile> findByUserId(String userId) throws IOException;

  void save(SearchHistoryProfile profile) throws IOException;

  // Deleting a missing profile is fine, so callers don't have to check first.
  void delete(String userId) throws IOException;
}
