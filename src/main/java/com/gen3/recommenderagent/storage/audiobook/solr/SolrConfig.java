package com.gen3.recommenderagent.storage.audiobook.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression(
    "'${audiobook.qdrant.migration.enabled:false}' == 'true' or ('${audiobook.embedding-index.enabled:false}' == 'true' and '${audiobook.database:qdrant}' == 'solr') or ('${audiobook.short-description.batch.mode:disabled}' != 'disabled' and '${audiobook.database:qdrant}' == 'solr')")
public class SolrConfig {

  @Bean
  public SolrClient solrClient(
      @Value("${solr.url}") String solrUrl,
      @Value("${solr.username}") String username,
      @Value("${solr.password}") String password) {
    return new HttpJdkSolrClient.Builder(solrUrl)
        .withBasicAuthCredentials(username, password)
        .build();
  }
}
