package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import java.util.List;

public interface ResponseGenerator {

  /** Builds user-facing text from the selected recommendations and completed conversation turn. */
  String generate(List<Recommendation> recommendations, ConversationTurn currentRequest);
}
