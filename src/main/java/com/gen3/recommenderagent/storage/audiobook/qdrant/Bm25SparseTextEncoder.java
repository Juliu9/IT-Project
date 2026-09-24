package com.gen3.recommenderagent.storage.audiobook.qdrant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Creates compact lexical vectors with BM25-style saturated term frequency.
 *
 * <p>Qdrant supplies collection-level inverse document frequency through its IDF modifier. Token
 * hashes are deterministic, so catalogue and request terms always use the same sparse index.
 */
@Component
public class Bm25SparseTextEncoder implements SparseTextEncoder {

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final Set<String> STOP_WORDS =
      Set.of(
          "a", "an", "and", "are", "by", "for", "from", "in", "is", "me", "of", "on",
          "the", "to", "with");
  private static final float K1 = 1.2f;

  /** Tokenizes text, removes common filler words, and weights repeated terms with saturation. */
  @Override
  public SparseVectorData encode(String text) {
    if (text == null || text.isBlank()) {
      return new SparseVectorData(List.of(), List.of());
    }
    Map<Integer, Integer> frequencies = new HashMap<>();
    for (String token : NON_ALPHANUMERIC.split(text.toLowerCase(Locale.ROOT))) {
      if (!token.isBlank() && token.length() > 1 && !STOP_WORDS.contains(token)) {
        frequencies.merge(tokenIndex(token), 1, Integer::sum);
      }
    }
    List<Integer> indices = frequencies.keySet().stream().sorted().toList();
    List<Float> values = new ArrayList<>(indices.size());
    for (Integer index : indices) {
      int frequency = frequencies.get(index);
      values.add(frequency * (K1 + 1.0f) / (frequency + K1));
    }
    return new SparseVectorData(indices, List.copyOf(values));
  }

  /** Maps a token into Qdrant's nonnegative sparse index space. */
  private int tokenIndex(String token) {
    return token.hashCode() & Integer.MAX_VALUE;
  }
}
