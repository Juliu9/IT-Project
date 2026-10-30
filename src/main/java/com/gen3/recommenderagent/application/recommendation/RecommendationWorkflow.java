package com.gen3.recommenderagent.application.recommendation;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;

/** Builds a ranked batch; the session transition consumes it in API-sized pages. */
public interface RecommendationWorkflow {

  List<Recommendation> recommend(ConversationDraft request, Session session);
}
