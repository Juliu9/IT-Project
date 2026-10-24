package com.gen3.embeddingjob.store;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.StoredAudiobook;
import java.io.IOException;
import java.util.List;

/** Paginates an existing catalogue and updates its vectors without replacing metadata. */
public interface AudiobookEmbeddingStore {
  Page embeddingPage(String cursor, int limit) throws IOException;

  void updateEmbeddings(List<Update> updates) throws IOException;

  record Page(List<StoredAudiobook> books, String nextCursor) {}

  record Update(String storageReference, AudiobookEmbedding embedding) {}
}
