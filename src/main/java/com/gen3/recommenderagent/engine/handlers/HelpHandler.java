package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HelpHandler implements IntentHandler {

    @Override
    public Intent supportedIntent() {
        return Intent.HELP;
    }

    @Override
    public List<Recommendation> handle(SessionRequest request, Session session) {

        // TODO:
        // Return information about available functionality.

        return List.of();
    }
}