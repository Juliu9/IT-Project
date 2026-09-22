package com.gen3.recommenderagent.storage.userprofiledb;

import com.gen3.recommenderagent.domain.userprofile.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, String> {
  // Spring provides:
  // findById(userId)
  // save(userProfile)
  // deleteById(userId)
  // existsById(userId)
  // etc.
}
