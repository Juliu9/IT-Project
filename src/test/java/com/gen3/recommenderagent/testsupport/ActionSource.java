package com.gen3.recommenderagent.testsupport;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.support.AnnotationConsumer;

/**
 * Supplies built-in action cases without requiring the extensible action identifier to be an enum.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ArgumentsSource(ActionSource.Provider.class)
public @interface ActionSource {
  Class<?> value() default ConversationAction.class;

  String[] names() default {};

  Mode mode() default Mode.INCLUDE;

  enum Mode {
    INCLUDE,
    EXCLUDE
  }

  final class Provider implements ArgumentsProvider, AnnotationConsumer<ActionSource> {
    private ActionSource source;

    public void accept(ActionSource source) {
      this.source = source;
    }

    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
      var names = Set.of(source.names());
      var registry = com.gen3.recommenderagent.testsupport.TestHandlers.registry();
      Stream<?> identifiers =
          source.value() == ConversationQuestion.class
              ? registry.questions().stream()
              : registry.actions().stream();
      return identifiers
          .filter(
              action ->
                  names.isEmpty()
                      || (names.contains(action.toString()) == (source.mode() == Mode.INCLUDE)))
          .map(Arguments::of);
    }
  }
}
