package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class IntentHandlerFactory {

  private final Map<Intent, IntentHandler> handlers;

  public IntentHandlerFactory(List<IntentHandler> intentHandlers) {
    this.handlers =
        intentHandlers.stream().collect(Collectors.toMap(IntentHandler::supportedIntent, h -> h));
  }

  public IntentHandler getHandler(Intent intent) {
    return handlers.get(intent);
  }
}
