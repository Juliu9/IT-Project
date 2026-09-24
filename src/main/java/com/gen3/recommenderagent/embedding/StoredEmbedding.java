package com.gen3.recommenderagent.embedding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A normalized vector and the text that produced it, stored separately from catalogue data. */
@Entity
@Table(name = "embeddings")
public class StoredEmbedding {

  @Id private String id;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String sourceText;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String vectorJson;

  protected StoredEmbedding() {}

  /** Creates a persisted embedding with a type-prefixed identifier. */
  public StoredEmbedding(String id, String sourceText, String vectorJson) {
    this.id = id;
    this.sourceText = sourceText;
    this.vectorJson = vectorJson;
  }

  /** Returns the type-prefixed audiobook or request identifier. */
  public String getId() {
    return id;
  }

  /** Returns the exact text sent to the embedding model. */
  public String getSourceText() {
    return sourceText;
  }

  /** Returns the normalized vector as a JSON number array. */
  public String getVectorJson() {
    return vectorJson;
  }
}
