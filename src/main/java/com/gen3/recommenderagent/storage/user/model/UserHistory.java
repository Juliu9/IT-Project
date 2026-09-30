package com.gen3.recommenderagent.storage.user.model;

import java.time.Instant;

public record UserHistory(
        String userId,
        String bookId,
        Instant lastInteractionTime) { // Changed field name

    public UserHistory {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID must not be blank");
        }
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("Book ID must not be blank");
        }
        if (lastInteractionTime == null) {
            throw new IllegalArgumentException("Interaction timestamp must not be null");
        }
    }
}