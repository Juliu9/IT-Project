package com.gen3.recommenderagent.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
