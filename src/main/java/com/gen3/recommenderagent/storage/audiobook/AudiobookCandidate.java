package com.gen3.recommenderagent.storage.audiobook;

/**
 * A database-independent audiobook search result.
 *
 * @param audiobook catalogue data used by the application and response pipeline
 * @param score similarity or relevance score supplied by the search database; may be null
 */
public record AudiobookCandidate(AudiobookRecord audiobook, Double score) {}
