package com.gen3.recommenderagent.ranker;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.springframework.stereotype.Repository;

@Repository
public class SolrAudiobookRepository {

    private final SolrClient solrClient;

    public SolrAudiobookRepository(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    public QueryResponse search(String query, int limit) throws Exception {

        SolrQuery solrQuery = new SolrQuery();

        solrQuery.setQuery(query);
        solrQuery.setRows(limit);

        return solrClient.query(solrQuery);
    }
}