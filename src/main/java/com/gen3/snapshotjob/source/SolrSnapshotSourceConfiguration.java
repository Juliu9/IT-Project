package com.gen3.snapshotjob.source;

import com.gen3.shared.audiobookcatalogue.solr.SolrAudiobookRepository;
import com.gen3.shared.audiobookcatalogue.solr.SolrConfig;
import com.gen3.snapshotjob.CatalogueSnapshotSource;
import com.gen3.snapshotjob.SnapshotIdentity;
import com.gen3.snapshotjob.SnapshotSources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers the Solr snapshot adapter and its client dependencies. */
@Configuration(proxyBeanMethods = false)
@Import({SolrConfig.class, SolrAudiobookRepository.class})
class SolrSnapshotSourceConfiguration {
  /** Registers the Solr source for exporting existing catalogue vectors. */
  @Bean
  @ConditionalOnProperty(name = "audiobook.database", havingValue = "solr")
  CatalogueSnapshotSource solrSnapshotSource(
      SolrAudiobookRepository repository, SnapshotIdentity identity) {
    return SnapshotSources.solr(repository, identity.collection());
  }
}
