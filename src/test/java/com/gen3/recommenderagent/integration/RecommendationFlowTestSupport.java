package com.gen3.recommenderagent.integration;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.engine.SessionUpdateEvent;
import com.gen3.recommenderagent.storage.sessioncache.RedisSessionObserver;
import com.gen3.recommenderagent.storage.sessioncache.SessionCache;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RecommendationFlowTestSupport {

    private RecommendationFlowTestSupport() {
    }

    static QueryResponse solrResponse(String... bookIds) {
        SolrDocumentList documents = new SolrDocumentList();

        for (int index = 0; index < bookIds.length; index++) {
            SolrDocument document = new SolrDocument();
            document.setField("id", bookIds[index]);
            document.setField("score", 1.0 - (index * 0.1));
            documents.add(document);
        }

        documents.setNumFound(documents.size());

        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(documents);
        return response;
    }

    static ApplicationEventPublisher sessionPublisher(SessionCache sessionCache) {
        RedisSessionObserver observer = new RedisSessionObserver(sessionCache);

        return event -> {
            if (event instanceof SessionUpdateEvent sessionUpdateEvent) {
                observer.onSessionUpdated(sessionUpdateEvent);
            }
        };
    }

    static final class InMemorySessionCache implements SessionCache {

        private final Map<String, Session> sessions = new HashMap<>();

        @Override
        public Session getSession(String sessionId) {
            return sessions.get(sessionId);
        }

        @Override
        public void updateSession(Session session) {
            sessions.put(session.getSessionId(), session);
        }
    }
}
