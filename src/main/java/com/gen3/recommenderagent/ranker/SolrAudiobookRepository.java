package com.gen3.recommenderagent.ranker;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class SolrAudiobookRepository {

    private final SolrClient solrClient;
    private final String collection;

    public SolrAudiobookRepository(
            SolrClient solrClient,
            @Value("${solr.collection}") String collection
    ) {
        this.solrClient = solrClient;
        this.collection = collection;
    }

    public QueryResponse search(String query, int limit) throws Exception {

        SolrQuery solrQuery = new SolrQuery();

        solrQuery.setQuery(query);
        solrQuery.set("defType", "edismax");
        solrQuery.set(
                "qf",
                "title rt_title authors rt_authors all"
        );
        solrQuery.setRows(limit);
        solrQuery.setFields(
                "id",
                "source",
                "title",
                "authors",
                "description",
                "score"
        );

        return solrClient.query(collection, solrQuery);
    }
}
