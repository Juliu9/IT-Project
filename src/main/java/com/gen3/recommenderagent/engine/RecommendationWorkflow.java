package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;

/** Heavyweight recommendation pipeline used only by recommendation-producing actions. */
public interface RecommendationWorkflow {

  List<Recommendation> recommend(SessionRequest request, SessionContext context);
}
