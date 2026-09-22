package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;

import java.util.List;

public interface ResponseGenerator {

    String generate(List<Recommendation> recommendations, SessionRequest currentRequest);
}