package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationRules.acceptsSemanticInformation;
import static com.gen3.recommenderagent.domain.conversation.ConversationRules.hasTerms;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Copies and merges search criteria without changing parser input or retained history. */
public final class SearchCriteriaMerger {
  private SearchCriteriaMerger() {}

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
          SessionPreferences.fromRequest(base)
              .updated(null, null, target.getReplaceSearchFields())
              .asRequest();
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
    if (explicitInclude != null) {
      if (!target.isAddAuthors() && hasTerms(explicitInclude.getAuthors())) {
        included.setAuthors(union(null, explicitInclude.getAuthors()));
      }
      if (!target.isAddNarrators() && hasTerms(explicitInclude.getNarrators())) {
        included.setNarrators(union(null, explicitInclude.getNarrators()));
      }
    }
    if (explicitExclude != null) {
      if (!target.isAddAuthors() && hasTerms(explicitExclude.getAuthors())) {
        excluded.setAuthors(union(null, explicitExclude.getAuthors()));
      }
      if (!target.isAddNarrators() && hasTerms(explicitExclude.getNarrators())) {
        excluded.setNarrators(union(null, explicitExclude.getNarrators()));
      }
    }
    target.setMustInclude(included);
    target.setMustNotInclude(excluded);
    var replacements = new java.util.LinkedHashSet<>(inheritedReplacements);
    replacements.addAll(target.getReplaceSearchFields());
    target.setReplaceSearchFields(new ArrayList<>(replacements));
  }

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
        union(
            values(base, SemanticQuery::getAuthors), values(override, SemanticQuery::getAuthors)));
    merged.setNarrators(
        union(
            values(base, SemanticQuery::getNarrators),
            values(override, SemanticQuery::getNarrators)));
    merged.setKeywords(
        union(
            values(base, SemanticQuery::getKeywords),
            values(override, SemanticQuery::getKeywords)));
    return merged;
  }

  private static ExactCriteria merge(ExactCriteria base, ExactCriteria override) {
    if (base == null && override == null) {
      return null;
    }
    ExactCriteria merged = new ExactCriteria();
    merged.setAuthors(
        union(
            base == null ? null : base.getAuthors(),
            override == null ? null : override.getAuthors()));
    merged.setNarrators(
        union(
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
  public static ConversationDraft copyOf(SessionRequest source) {
    ConversationDraft copy = new ConversationDraft();
    copy.setIntent(Intent.UNKNOWN);
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
    copy.setExplicitSearch(source.isExplicitSearch());
    copy.setAddAuthors(source.isAddAuthors());
    copy.setAddNarrators(source.isAddNarrators());
    copy.setRequestId(source.getRequestId());
    copy.setRawText(source.getRawText());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setIntent(source.getIntent() == null ? Intent.UNKNOWN : source.getIntent());
    if (acceptsSemanticInformation(copy.getIntent())) {
      copy.setShorter(source.isShorter());
      copy.setPositiveSemanticQuery(copy(source.getPositiveSemanticQuery()));
      copy.setNegativeSemanticQuery(copy(source.getNegativeSemanticQuery()));
      copy.setPersonalised(source.isPersonalised());
    }
    copy.setMustInclude(copy(source.getMustInclude()));
    copy.setMustNotInclude(copy(source.getMustNotInclude()));
    if (copy.getIntent() == Intent.EXPLAIN_RECOMMENDATION) {
      copy.setReferenceRank(source.getReferenceRank());
      copy.setReferenceTitle(source.getReferenceTitle());
    }
    if (acceptsSemanticInformation(copy.getIntent())) {
      copy.setSimilarToTitle(source.getSimilarToTitle());
      copy.setSimilarToAuthor(source.getSimilarToAuthor());
      copy.setSimilarToClarificationRequired(source.isSimilarToClarificationRequired());
    }
    if (source instanceof ConversationDraft draft) {
      copy.setDecision(draft.getDecision());
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

  private static SemanticQuery copy(SemanticQuery source) {
    return merge(null, source);
  }

  private static ExactCriteria copy(ExactCriteria source) {
    return merge(null, source);
  }

  private static List<String> values(
      SemanticQuery query, java.util.function.Function<SemanticQuery, List<String>> getter) {
    return query == null ? null : getter.apply(query);
  }

  static List<String> union(List<String> base, List<String> override) {
    Map<String, String> unique = new LinkedHashMap<>();
    add(unique, base);
    add(unique, override);
    return unique.isEmpty() ? null : new ArrayList<>(unique.values());
  }

  private static void add(Map<String, String> unique, List<String> values) {
    if (values == null) {
      return;
    }
    values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .forEach(value -> unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
  }

  private static String preferred(String base, String override) {
    return override == null || override.isBlank() ? base : override;
  }
}
