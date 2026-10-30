package com.gen3.snapshotjob;

import com.gen3.catalogue.snapshot.BookVectorSnapshot;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Snapshot publishing machinery, shared by the standalone rebuild and embedding workflow. */
@Configuration(proxyBeanMethods = false)
public class SnapshotConfiguration {
  public static BookVectorSnapshot snapshot(Environment environment) {
    String backend = environment.getProperty("audiobook.database", "qdrant");
    if (!backend.equals("qdrant") && !backend.equals("solr")) {
      throw new IllegalArgumentException("Snapshot backend must be qdrant or solr");
    }
    String collection =
        environment.getProperty(
            backend + ".collection", backend.equals("solr") ? "audiobooks" : "audiobooks_hybrid");
    Path path =
        Path.of(
            environment.getProperty(
                "book.vector-snapshot.directory", "./data/book-vector-snapshot"),
            backend);
    // Different catalogue names must not overwrite one another's last valid snapshot.
    String name =
        java.util
            .UUID
            .nameUUIDFromBytes(collection.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    return new BookVectorSnapshot(
        path.resolve(name + ".bin"),
        backend,
        collection,
        environment.getProperty(
            "spring.ai.openai.embedding.options.model", "text-embedding-3-small"),
        environment.getProperty("embedding.dimensions", Integer.class, 1536));
  }

  @Bean
  BookVectorSnapshot bookVectorSnapshot(Environment environment) {
    return snapshot(environment);
  }

  @Bean
  BookVectorSnapshot.Source snapshotSource(
      Environment environment,
      ObjectProvider<com.gen3.catalogue.solr.SolrAudiobookRepository> solr,
      ObjectProvider<io.qdrant.client.QdrantClient> qdrant) {
    return environment.getProperty("audiobook.database", "qdrant").equals("solr")
        ? SnapshotSources.solr(solr.getObject())
        : SnapshotSources.qdrant(
            qdrant.getObject(), environment.getProperty("qdrant.collection", "audiobooks_hybrid"));
  }
}
