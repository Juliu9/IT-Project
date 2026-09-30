package com.gen3.recommenderagent.storage.user.model;

public record UserFavourite(
        String userId,
        String bookId
) {

    public UserFavourite {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID must not be blank");
        }
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("Book ID must not be blank");
        }
    }
}
