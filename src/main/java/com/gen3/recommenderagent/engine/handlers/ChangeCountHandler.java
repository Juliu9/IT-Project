package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChangeCountHandler implements IntentHandler {

    @Override
    public Intent supportedIntent() {
        return Intent.CHANGE_COUNT;
    }

    @Override
    public List<Recommendation> handle(SessionRequest request, Session session) {

        // TODO:
        // Re-rank or retrieve enough candidates
        // to satisfy the requested count.

        return List.of();
    }
}