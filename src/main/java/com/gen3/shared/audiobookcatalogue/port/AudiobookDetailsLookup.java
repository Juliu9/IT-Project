package com.gen3.shared.audiobookcatalogue.port;

import com.gen3.shared.audiobookcatalogue.model.AudiobookRecord;
import java.io.IOException;
import java.util.List;

/** Reads stored catalogue metadata by book ID, without retrieving vectors. */
public interface AudiobookDetailsLookup {
  List<AudiobookRecord> findBooksByIds(List<String> bookIds) throws IOException;
}
