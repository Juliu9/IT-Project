package com.gen3.recommenderagent.application.conversation.search;

import static com.gen3.recommenderagent.application.conversation.ConversationRules.hasTerms;

import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Copies and merges search criteria without changing parser input or retained history. */
public final class SearchCriteriaMerger {
  /** Prevents instantiation of this utility class. */
  private SearchCriteriaMerger() {}

  /** Combines inherited and explicit criteria using the applicable field replacement rules. */
  public static void merge(SessionRequest base, ConversationDraft target) {
    if (base instanceof ConversationDraft previous
        && target.getSimilarToTitle() == null
        && !target.isSimilarToClarificationRequired()
        && target.getSearchContext().getSimilarBook() == null) {
      target.getSearchContext().setSimilarBook(previous.getSearchContext().getSimilarBook());
    }
    var inheritedReplacements = base.getReplaceSearchFields();
    boolean personalised = base.isPersonalised();
    if (!target.getReplaceSearchFields().isEmpty()) {
      base =
          PreferenceRequestMapper.toRequest(
              PreferenceRequestMapper.fromRequest(base)
                  .updated(null, null, target.getReplaceSearchFields()));
    }
    target.setPersonalised(personalised || target.isPersonalised());
    target.setPositiveSemanticQuery(
        merge(base.getPositiveSemanticQuery(), target.getPositiveSemanticQuery()));
    target.setNegativeSemanticQuery(
        merge(base.getNegativeSemanticQuery(), target.getNegativeSemanticQuery()));
    ExactCriteria explicitInclude = target.getMustInclude();
    ExactCriteria explicitExclude = target.getMustNotInclude();
    ExactCriteria included = merge(base.getMustInclude(), explicitInclude);
    ExactCriteria excluded = merge(base.getMustNotInclude(), explicitExclude);
    target.setMustInclude(included);
    target.setMustNotInclude(excluded);
    var replacements = new java.util.LinkedHashSet<>(inheritedReplacements);
    replacements.addAll(target.getReplaceSearchFields());
    target.setReplaceSearchFields(new ArrayList<>(replacements));
  }

  /** Selects the applicable author or narrator value while respecting single-person semantics. */
  private static List<String> person(List<String> base, List<String> supplied) {
    return union(hasTerms(supplied) ? null : base, supplied);
  }

  /** Combines inherited and explicit criteria using the applicable field replacement rules. */
  private static SemanticQuery merge(SemanticQuery base, SemanticQuery override) {
    if (base == null && override == null) {
      return null;
    }
    SemanticQuery merged = new SemanticQuery();
    merged.setTopics(
        union(values(base, SemanticQuery::getTopics), values(override, SemanticQuery::getTopics)));
    merged.setGenres(
        union(values(base, SemanticQuery::getGenres), values(override, SemanticQuery::getGenres)));
    merged.setAuthors(
        person(
            values(base, SemanticQuery::getAuthors), values(override, SemanticQuery::getAuthors)));
    merged.setNarrators(
        person(
            values(base, SemanticQuery::getNarrators),
            values(override, SemanticQuery::getNarrators)));
    return merged;
  }

  /** Combines inherited and explicit criteria using the applicable field replacement rules. */
  private static ExactCriteria merge(ExactCriteria base, ExactCriteria override) {
    if (base == null && override == null) {
      return null;
    }
    ExactCriteria merged = new ExactCriteria();
    merged.setAuthors(
        person(
            base == null ? null : base.getAuthors(),
            override == null ? null : override.getAuthors()));
    merged.setNarrators(
        person(
            base == null ? null : base.getNarrators(),
            override == null ? null : override.getNarrators()));
    merged.setLanguage(
        preferred(
            base == null ? null : base.getLanguage(),
            override == null ? null : override.getLanguage()));
    merged.setDuration(
        preferred(
            base == null ? null : base.getDuration(),
            override == null ? null : override.getDuration()));
    merged.setPublicationYear(
        preferred(
            base == null ? null : base.getPublicationYear(),
            override == null ? null : override.getPublicationYear()));
    merged.setSource(
        preferred(
            base == null ? null : base.getSource(),
            override == null ? null : override.getSource()));
    return merged;
  }

  // Resolution must not mutate parser input or the saved criteria used by later requests.
  /** Creates a working conversation draft without modifying the supplied request. */
  public static ConversationDraft copyOf(SessionRequest source) {
    ConversationDraft copy = new ConversationDraft();
    copy.setIntent(UnknownHandler.ACTION);
    if (source == null) {
      return copy;
    }
    copy.setPreferenceUpdates(
        source.getPreferenceUpdates() == null ? null : source.getPreferenceUpdates().copy());
    copy.setPreferenceRemovals(
        source.getPreferenceRemovals() == null ? null : source.getPreferenceRemovals().copy());
    copy.setReplacePreferenceFields(
        source.getReplacePreferenceFields() == null
            ? null
            : new ArrayList<>(source.getReplacePreferenceFields()));
    copy.setReplaceSearchFields(source.getReplaceSearchFields());
    copy.setStartsNewSearch(source.startsNewSearch());
    copy.setInvalidBookCount(source.isInvalidBookCount());
    copy.setPreferenceRemovalTerms(source.getPreferenceRemovalTerms());
    copy.setPositivePreferenceRemovalOnly(source.isPositivePreferenceRemovalOnly());
    copy.setRequestId(source.getRequestId());
    copy.setRawText(source.getRawText());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setIntent(source.getIntent() == null ? UnknownHandler.ACTION : source.getIntent());
    copy.setShorter(source.isShorter());
    copy.setLonger(source.isLonger());
    copy.setPositiveSemanticQuery(copy(source.getPositiveSemanticQuery()));
    copy.setNegativeSemanticQuery(copy(source.getNegativeSemanticQuery()));
    copy.setPersonalised(source.isPersonalised());
    copy.setMustInclude(copy(source.getMustInclude()));
    copy.setMustNotInclude(copy(source.getMustNotInclude()));
    copy.setExplanationSelection(source.getExplanationSelection());
    copy.setReferenceRank(source.getReferenceRank());
    copy.setReferenceTitle(source.getReferenceTitle());
    copy.setSimilarToTitle(source.getSimilarToTitle());
    copy.setSimilarToAuthor(source.getSimilarToAuthor());
    copy.setSimilarToClarificationRequired(source.isSimilarToClarificationRequired());
    if (source instanceof ConversationDraft draft) {
      copy.setOutcome(draft.getOutcome());
      copy.setPendingQuestion(draft.getPendingQuestion());
      copy.setPreferenceAnswer(draft.isPreferenceAnswer());
      copy.setSearchClarificationRequired(draft.isSearchClarificationRequired());
      copy.setSimilarBookChoices(draft.getSimilarBookChoices());
      copy.getSearchContext().setSimilarBook(draft.getSearchContext().getSimilarBook());
      copy.getSearchContext()
          .setSimilarityQueryVector(draft.getSearchContext().getSimilarityQueryVector());
    }
    copy.setBookCount(source.getBookCount());

    return copy;
  }

  /** Copies criteria without sharing mutable preference lists. */
  private static SemanticQuery copy(SemanticQuery source) {
    return merge(null, source);
  }

  /** Copies criteria without sharing mutable preference lists. */
  private static ExactCriteria copy(ExactCriteria source) {
    return merge(null, source);
  }

  /** Returns an empty list for missing terms before merging values. */
  private static List<String> values(
      SemanticQuery query, java.util.function.Function<SemanticQuery, List<String>> getter) {
    return query == null ? null : getter.apply(query);
  }

  /** Combines terms while retaining their order and removing duplicates. */
  static List<String> union(List<String> base, List<String> override) {
    Map<String, String> unique = new LinkedHashMap<>();
    add(unique, base);
    add(unique, override);
    return unique.isEmpty() ? null : new ArrayList<>(unique.values());
  }

  /** Adds nonblank terms to the destination collection. */
  private static void add(Map<String, String> unique, List<String> values) {
    if (values == null) {
      return;
    }
    values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .forEach(value -> unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
  }

  /** Uses the supplied replacement value when present, otherwise keeps the inherited value. */
  private static String preferred(String base, String override) {
    return override == null || override.isBlank() ? base : override;
  }
}
