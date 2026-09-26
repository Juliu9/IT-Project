package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;

/** Executes one application-level operation resolved from a parsed intent. */
public interface IntentAction {

  SessionRequest execute(SessionRequest request, SessionContext context);
}
