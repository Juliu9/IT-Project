package com.gen3.recommenderagent.domain.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** One unfinished action, with only the context required to answer its current question. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PendingQuestion.Search.class, name = "search"),
  @JsonSubTypes.Type(value = PendingQuestion.Count.class, name = "count"),
  @JsonSubTypes.Type(value = PendingQuestion.Preference.class, name = "preference"),
  @JsonSubTypes.Type(value = PendingQuestion.Explanation.class, name = "explanation"),
  @JsonSubTypes.Type(value = PendingQuestion.SimilarBook.class, name = "similarBook")
})
public abstract class PendingQuestion {
  private ConversationDecision decision;

  public ConversationDecision getDecision() {
    return decision;
  }

  public void setDecision(ConversationDecision value) {
    decision = value;
  }

  @JsonIgnore
  public boolean accepts(ConversationDecision value) {
    return create(value).getClass() == getClass();
  }

  public static PendingQuestion create(ConversationDecision decision) {
    PendingQuestion question =
        switch (decision) {
          case COUNT_CLARIFICATION -> new Count();
          case PREFERENCE_CLARIFICATION, PREFERENCE_UPDATE_CLARIFICATION -> new Preference();
          case EXPLANATION_TARGET_CLARIFICATION -> new Explanation();
          case SIMILAR_BOOK_SELECTION,
              SIMILAR_BOOK_REQUIRED,
              SIMILAR_BOOK_NOT_FOUND,
              SIMILAR_BOOK_UNAVAILABLE ->
              new SimilarBook();
          default -> new Search();
        };
    question.setDecision(decision);
    return question;
  }

  public abstract static class SearchRequest extends PendingQuestion {
    private ConversationTurn request;

    public ConversationTurn getRequest() {
      return request;
    }

    public void setRequest(ConversationTurn value) {
      request = value;
    }
  }

  public static final class Search extends SearchRequest {}

  public static final class Count extends PendingQuestion {
    private SessionRequest request;

    public Count() {
      setDecision(ConversationDecision.COUNT_CLARIFICATION);
    }

    public SessionRequest getRequest() {
      return request;
    }

    public void setRequest(SessionRequest value) {
      request = value;
    }
  }

  public static final class Preference extends SearchRequest {}

  public static final class Explanation extends PendingQuestion {
    private ExplanationSelection selection;

    public Explanation() {
      setDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    }

    public ExplanationSelection getSelection() {
      return selection;
    }

    public void setSelection(ExplanationSelection value) {
      selection = value;
    }
  }

  public static final class SimilarBook extends SearchRequest {}
}
