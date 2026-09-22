package com.gen3.recommenderagent.embedding;

/** Vector operations shared by indexing and similarity search. */
public final class VectorMath {

  private VectorMath() {}

  /** Returns a unit-length copy; rejects empty, zero, or non-finite vectors. */
  public static float[] normalize(float[] vector) {
    if (vector == null || vector.length == 0) {
      throw new IllegalArgumentException("Embedding vector must not be empty");
    }
    double squaredLength = 0;
    for (float value : vector) {
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("Embedding vector must contain finite values");
      }
      squaredLength += (double) value * value;
    }
    if (squaredLength == 0 || !Double.isFinite(squaredLength)) {
      throw new IllegalArgumentException("Embedding vector must have a finite nonzero length");
    }
    double length = Math.sqrt(squaredLength);
    float[] normalized = new float[vector.length];
    for (int index = 0; index < vector.length; index++) {
      normalized[index] = (float) (vector[index] / length);
    }
    return normalized;
  }

  /** Computes cosine similarity when both inputs have been normalized. */
  public static double dotProduct(float[] left, float[] right) {
    if (left == null || right == null || left.length == 0 || left.length != right.length) {
      throw new IllegalArgumentException("Embedding vectors must have equal nonzero dimensions");
    }
    double result = 0;
    for (int index = 0; index < left.length; index++) {
      result += (double) left[index] * right[index];
    }
    return result;
  }
}
