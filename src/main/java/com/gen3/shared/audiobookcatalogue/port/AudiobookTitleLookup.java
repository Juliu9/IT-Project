package com.gen3.shared.audiobookcatalogue.port;

import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import java.io.IOException;
import java.util.List;

/** Returns bounded exact-title matches when present, otherwise partial candidates. */
public interface AudiobookTitleLookup {
  /** Finds catalogue title matches within the requested result limit. */
  List<AudiobookTitle> findBooksByTitle(String title, String author, int limit) throws IOException;
}
