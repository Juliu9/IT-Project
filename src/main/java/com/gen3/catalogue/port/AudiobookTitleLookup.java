package com.gen3.catalogue.port;

import com.gen3.catalogue.model.AudiobookTitle;
import java.io.IOException;
import java.util.List;

/** Returns bounded title candidates; the caller must verify exactness and ambiguity. */
public interface AudiobookTitleLookup {
  List<AudiobookTitle> findBooksByTitle(String title, String author, int limit) throws IOException;
}
