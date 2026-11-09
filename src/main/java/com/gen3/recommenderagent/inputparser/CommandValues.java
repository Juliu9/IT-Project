package com.gen3.recommenderagent.inputparser;

import static com.gen3.recommenderagent.inputparser.ConversationText.selectionReply;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import java.math.BigInteger;

/** Shared value conversion used by handler-owned command recognizers. */
public final class CommandValues {
  private CommandValues() {}

  public static final String COUNT = "[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten";

  /** Converts recognized count wording into the parser's numeric representation. */
  public static BigInteger countValue(String count) {
    return Character.isDigit(count.charAt(0))
        ? new BigInteger(count)
        : BigInteger.valueOf(selectionReply(count));
  }

  /** Creates parsed command output for the supplied conversation action. */
  public static UserIntent intent(ConversationAction action) {
    var parsed = new UserIntent();
    parsed.setIntent(action);
    return parsed;
  }
}
