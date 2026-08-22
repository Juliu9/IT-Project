package com.gen3.recommenderagent.ranker;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SolrAudiobookRepositoryTest {

    @Test
    public void testSearch() throws Exception {

        String solrUrl = System.getenv("SOLR_URL");
        String username = System.getenv("SOLR_USERNAME");
        String password = System.getenv("SOLR_PASSWORD");

        SolrClient solrClient =
                new HttpJdkSolrClient.Builder(solrUrl)
                        .build();

        SolrQuery query = new SolrQuery("*:*");
        query.setRows(1);

        QueryRequest request = new QueryRequest(query);

        request.setBasicAuthCredentials(
                username,
                password
        );

        try {
            QueryResponse response =
                    request.process(solrClient, "combinedbooks");

            System.out.println("SUCCESS!");
            System.out.println(
                    "Status Code: " + response.getStatus()
            );
            System.out.println(
                    "Query Time: " + response.getQTime() + "ms"
            );
            System.out.println(
                    "Documents Found: " +
                            response.getResults().getNumFound()
            );

            assertNotNull(response);

        } finally {
            solrClient.close();
        }
    }
}