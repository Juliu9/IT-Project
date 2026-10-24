package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Recommendation;
import java.util.List;

public interface ResponseGenerator {

  String generate(List<Recommendation> recommendations, ConversationTurn currentRequest);
}
