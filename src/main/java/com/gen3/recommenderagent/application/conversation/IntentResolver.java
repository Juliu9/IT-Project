package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
import org.springframework.stereotype.Component;

/**
 * Converts stated user meaning into a final conversation action while the current session is
 * locked. Parser output is deliberately not authoritative: this is the single guardrail seam for
 * pending questions, page references, and current-search availability.
 */
@Component
public class IntentResolver {
  private final InputParser inputParser;

  public IntentResolver(InputParser inputParser) {
    this.inputParser = inputParser;
  }

  public SessionRequest resolve(Session session, String rawText, UserIntent intent) {
    // Keep interpretation here, not in the AI prompt. Existing conversation rules perform the
    // detailed pending-question and page-reference resolution after this conversion.
    return inputParser.toSessionRequest(rawText, intent);
  }
}
