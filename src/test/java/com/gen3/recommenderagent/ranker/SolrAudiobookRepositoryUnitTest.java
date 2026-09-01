package com.gen3.recommenderagent.ranker;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolrAudiobookRepositoryUnitTest {

    @Test
    void shouldQueryConfiguredCollectionAndOnlyRequestApiFields() throws Exception {
        SolrClient client = mock(SolrClient.class);
        QueryResponse expected = new QueryResponse();
        when(client.query(org.mockito.ArgumentMatchers.eq("combinedbooks"),
                org.mockito.ArgumentMatchers.any(SolrQuery.class)))
                .thenReturn(expected);

        SolrAudiobookRepository repository =
                new SolrAudiobookRepository(client, "combinedbooks");

        QueryResponse actual = repository.search("mystery", 5);

        ArgumentCaptor<SolrQuery> queryCaptor =
                ArgumentCaptor.forClass(SolrQuery.class);
        verify(client).query(
                org.mockito.ArgumentMatchers.eq("combinedbooks"),
                queryCaptor.capture()
        );

        SolrQuery query = queryCaptor.getValue();
        assertSame(expected, actual);
        assertEquals("mystery", query.getQuery());
        assertEquals("edismax", query.get("defType"));
        assertEquals(
                "title rt_title authors rt_authors all",
                query.get("qf")
        );
        assertEquals(5, query.getRows());
        assertEquals(
                "id,source,title,authors,description,score",
                query.getFields()
        );
    }
}
