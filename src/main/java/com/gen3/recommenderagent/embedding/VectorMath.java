package com.gen3.recommenderagent.embedding;

import java.util.List;

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

  /** Averages positive evidence and subtracts averaged negative evidence. */
  public static float[] directionalAverage(List<float[]> positive, List<float[]> negative) {
    List<float[]> positives = positive == null ? List.of() : positive;
    List<float[]> negatives = negative == null ? List.of() : negative;
    float[] reference =
        !positives.isEmpty()
            ? positives.getFirst()
            : (!negatives.isEmpty() ? negatives.getFirst() : new float[0]);
    if (reference.length == 0) {
      return new float[0];
    }

    float[] combined = new float[reference.length];
    addAverage(combined, positives, 1.0f);
    addAverage(combined, negatives, -1.0f);
    if (isZero(combined)) {
      return !positives.isEmpty()
          ? normalize(positives.getFirst())
          : scaleAndNormalize(negatives.getFirst(), -1.0f);
    }
    return normalize(combined);
  }

  private static boolean isZero(float[] vector) {
    for (float value : vector) {
      if (value != 0.0f) {
        return false;
      }
    }
    return true;
  }

  private static float[] scaleAndNormalize(float[] vector, float scale) {
    float[] scaled = new float[vector.length];
    for (int index = 0; index < vector.length; index++) {
      scaled[index] = vector[index] * scale;
    }
    return normalize(scaled);
  }

  private static void addAverage(float[] destination, List<float[]> vectors, float direction) {
    if (vectors.isEmpty()) {
      return;
    }
    for (float[] vector : vectors) {
      if (vector == null || vector.length != destination.length) {
        throw new IllegalArgumentException("Embedding vectors must have equal nonzero dimensions");
      }
      for (int index = 0; index < vector.length; index++) {
        destination[index] += direction * vector[index] / vectors.size();
      }
    }
  }
}
