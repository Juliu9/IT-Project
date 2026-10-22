package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;

/** Builds a ranked batch; the session transition consumes it in API-sized pages. */
public interface RecommendationWorkflow {

  List<Recommendation> recommend(SessionRequest request, Session session);
}
