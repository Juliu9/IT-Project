package com.gen3.shared.readhistory.port;

import java.io.IOException;
import java.util.Set;

/** Replaceable source of catalogue-normalized read IDs; absence is not a storage failure. */
public interface ReadHistoryRepository {
  /** Returns at most limit distinct IDs, allowing callers to detect oversized histories safely. */
  Set<String> findReadBookIds(String userId, int limit) throws IOException;
}
