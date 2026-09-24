package com.gen3.recommenderagent.ranker.retrieval;

/** Selects which Qdrant retrieval capability should execute for an intent. */
public enum RetrievalMode {
  SEMANTIC,
  KEYWORD,
  HYBRID,
  FILTER_ONLY,
  NONE
}
