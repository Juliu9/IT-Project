package com.gen3.snapshotjob.source;

import com.gen3.shared.audiobookcatalogue.qdrant.QdrantConfig;
import com.gen3.snapshotjob.CatalogueSnapshotSource;
import com.gen3.snapshotjob.SnapshotIdentity;
import com.gen3.snapshotjob.SnapshotSources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers the Qdrant snapshot adapter and its client dependencies. */
@Configuration(proxyBeanMethods = false)
@Import(QdrantConfig.class)
class QdrantSnapshotSourceConfiguration {
  /** Registers the Qdrant source for exporting existing catalogue vectors. */
  @Bean
  @ConditionalOnProperty(name = "audiobook.database", havingValue = "qdrant", matchIfMissing = true)
  CatalogueSnapshotSource qdrantSnapshotSource(
      io.qdrant.client.QdrantClient client, SnapshotIdentity identity) {
    return SnapshotSources.qdrant(client, identity.collection());
  }
}
