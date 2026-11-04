package com.gen3.recommenderagent.domain.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.conversation.ExplanationSelection;

/** One unfinished action, with only the context required to answer its current question. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PendingQuestion.Search.class, name = "search"),
  @JsonSubTypes.Type(value = PendingQuestion.Preference.class, name = "preference"),
  @JsonSubTypes.Type(value = PendingQuestion.Explanation.class, name = "explanation"),
  @JsonSubTypes.Type(value = PendingQuestion.SimilarBook.class, name = "similarBook")
})
public abstract class PendingQuestion {
  private ConversationQuestion kind;

  public ConversationQuestion getKind() {
    return kind;
  }

  public void setKind(ConversationQuestion value) {
    kind = value;
  }

  /** Checks whether the question kind belongs to this persisted question subtype. */
  @JsonIgnore
  public boolean accepts(ConversationQuestion value) {
    return create(value).getClass() == getClass();
  }

  /** Creates the persisted question subtype appropriate for the supplied clarification kind. */
  public static PendingQuestion create(ConversationQuestion kind) {
    PendingQuestion question =
        switch (kind) {
          case PREFERENCE_CLARIFICATION, PREFERENCE_UPDATE_CLARIFICATION -> new Preference();
          case EXPLANATION_TARGET_CLARIFICATION -> new Explanation();
          case SIMILAR_BOOK_SELECTION,
              SIMILAR_BOOK_REQUIRED,
              SIMILAR_BOOK_NOT_FOUND,
              SIMILAR_BOOK_UNAVAILABLE ->
              new SimilarBook();
          default -> new Search();
        };
    question.setKind(kind);
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

  public static final class Preference extends SearchRequest {}

  public static final class Explanation extends PendingQuestion {
    private ExplanationSelection selection;

    /** Creates an explanation question with the explanation-target clarification kind. */
    public Explanation() {
      setKind(ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION);
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
