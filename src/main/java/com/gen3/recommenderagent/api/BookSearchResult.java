package com.gen3.recommenderagent.api;

import java.util.List;

public record BookSearchResult(
    String id, String source, String title, List<String> authors, String description) {}
