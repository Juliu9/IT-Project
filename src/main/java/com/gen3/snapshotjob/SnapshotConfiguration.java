package com.gen3.snapshotjob;

import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Snapshot publishing machinery, shared by the standalone rebuild and embedding workflow. */
@Configuration(proxyBeanMethods = false)
public class SnapshotConfiguration {
  public static BookVectorSnapshot snapshot(Environment environment) {
    return snapshot(environment, SnapshotIdentity.from(environment));
  }

  private static BookVectorSnapshot snapshot(Environment environment, SnapshotIdentity identity) {
    String backend = identity.backend();
    String collection = identity.collection();
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
  SnapshotIdentity snapshotIdentity(Environment environment) {
    return SnapshotIdentity.from(environment);
  }

  @Bean
  BookVectorSnapshot bookVectorSnapshot(
      Environment environment, SnapshotIdentity identity, CatalogueSnapshotSource source) {
    if (!identity.equals(new SnapshotIdentity(source.backend(), source.collection()))) {
      throw new IllegalArgumentException("Snapshot source identity does not match configuration");
    }
    return snapshot(environment, identity);
  }

  @Bean
  @ConditionalOnProperty(name = "audiobook.database", havingValue = "qdrant", matchIfMissing = true)
  CatalogueSnapshotSource qdrantSnapshotSource(
      io.qdrant.client.QdrantClient client, SnapshotIdentity identity) {
    return SnapshotSources.qdrant(client, identity.collection());
  }

  @Bean
  @ConditionalOnProperty(name = "audiobook.database", havingValue = "solr")
  CatalogueSnapshotSource solrSnapshotSource(
      com.gen3.shared.audiobookcatalogue.solr.SolrAudiobookRepository repository,
      SnapshotIdentity identity) {
    return SnapshotSources.solr(repository, identity.collection());
  }
}
