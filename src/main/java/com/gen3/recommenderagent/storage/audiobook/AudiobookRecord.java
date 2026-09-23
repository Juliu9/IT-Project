package com.gen3.recommenderagent.storage.audiobook;

import java.util.List;

/** Catalogue fields needed by search, candidate ranking, and embedding generation. */
public record AudiobookRecord(
    String id, String source, String title, List<String> authors, String description) {}
