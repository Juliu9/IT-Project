package com.gen3.recommenderagent.storage.audiobook.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Validates stored Solr vectors without changing the catalogue values. */
public final class SolrVectors {
  private SolrVectors() {}

  public static float[] read(Object stored, int dimension) throws IOException {
    if (stored == null) {
      return null;
    }
    float[] vector;
    if (stored instanceof float[] values) {
      vector = values.clone();
    } else if (stored instanceof Collection<?> values) {
      vector = new float[values.size()];
      int index = 0;
      for (Object value : values) {
        if (!(value instanceof Number number)) {
          throw new IOException("Stored Solr vector must contain only numbers");
        }
        vector[index++] = number.floatValue();
      }
    } else {
      throw new IOException("Unsupported stored Solr vector representation");
    }
    try {
      validate(vector, dimension);
    } catch (IllegalArgumentException exception) {
      throw new IOException("Invalid stored Solr vector", exception);
    }
    return vector;
  }

  public static List<Float> values(float[] vector, int dimension) {
    if (vector == null || vector.length == 0) {
      return null;
    }
    validate(vector, dimension);
    List<Float> values = new ArrayList<>(vector.length);
    for (float value : vector) {
      values.add(value);
    }
    return values;
  }

  private static void validate(float[] vector, int dimension) {
    if (vector.length != dimension || dimension < 1) {
      throw new IllegalArgumentException("Solr vector dimension mismatch");
    }
    boolean nonzero = false;
    for (float value : vector) {
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("Solr vectors must contain finite values");
      }
      nonzero |= value != 0;
    }
    if (!nonzero) {
      throw new IllegalArgumentException("Solr vectors must be nonzero");
    }
  }
}
