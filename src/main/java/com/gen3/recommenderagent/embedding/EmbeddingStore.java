package com.gen3.recommenderagent.embedding;

import org.springframework.data.jpa.repository.JpaRepository;

/** Stores embeddings independently of the audiobook search database. */
public interface EmbeddingStore extends JpaRepository<StoredEmbedding, String> {}
