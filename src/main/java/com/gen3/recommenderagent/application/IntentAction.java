package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;

/** Executes one application-level operation resolved from a parsed intent. */
public interface IntentAction {

  SessionRequest execute(SessionRequest request, Session session);
}
