package com.gen3.recommenderagent.ranker.retrieval;

/** Selects which Qdrant retrieval capability should execute for an intent. */
public enum RetrievalMode {
  SEMANTIC,
  /** Reserved for future intents that need sparse lexical retrieval without dense retrieval. */
  KEYWORD,
  HYBRID,
  FILTER_ONLY,
  NONE
}
