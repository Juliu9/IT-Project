package com.gen3.recommenderagent.application.recommendation;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.catalogue.model.PersonNameNormalizer;
import com.gen3.catalogue.port.AudiobookTitleLookup;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.catalogue.vector.VectorMath;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.search.SimilarBookReference;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Resolves reference titles before a new search can replace the active page or cached batch. */
@Service
public class SimilarBookResolver {
  private static final int MAX_CHOICES = 5;
  private static final Logger LOGGER = LoggerFactory.getLogger(SimilarBookResolver.class);
  private final AudiobookTitleLookup titles;
  private final AudiobookVectorRepository vectors;

  public SimilarBookResolver(AudiobookTitleLookup titles, AudiobookVectorRepository vectors) {
    this.titles = titles;
    this.vectors = vectors;
  }

  public void resolve(Session session, ConversationDraft request) {
    if (request.getIntent() != ConversationAction.RECOMMENDATION
        && request.getIntent() != ConversationAction.REFINE
        && request.getIntent() != ConversationAction.MORE_RESULTS) {
      return;
    }
    if (request.getDecision() == ConversationDecision.SIMILAR_BOOK_SELECTION
        && request.isSearchClarificationRequired()) {
      return;
    }
    if (request.getDecision() == ConversationDecision.SIMILAR_BOOK_UNAVAILABLE) {
      request.setSearchClarificationRequired(true);
      return;
    }
    if (request.isSimilarToClarificationRequired()) {
      clarify(session, request, ConversationDecision.SIMILAR_BOOK_REQUIRED, List.of());
      return;
    }
    var reference = request.getSearchContext().getSimilarBook();
    if (reference != null) {
      if (reference.vector() == null) {
        loadVector(
            session,
            request,
            new AudiobookTitle(reference.bookId(), reference.title(), reference.authors()));
      }
      if (request.getDecision() != ConversationDecision.SIMILAR_BOOK_UNAVAILABLE) {
        session.setPendingQuestion(null);
      }
      return;
    }
    String title = request.getSimilarToTitle();
    if (title == null || title.isBlank()) {
      if (request.getIntent() == ConversationAction.RECOMMENDATION) {
        session.setPendingQuestion(null);
      }
      return;
    }
    try {
      var matches = titles.findBooksByTitle(title, request.getSimilarToAuthor(), MAX_CHOICES + 1);
      resolveTitleMatches(session, request, title, matches);
    } catch (IOException exception) {
      throw new CandidateRetrievalException("catalogue title lookup", exception);
    }
  }

  private void resolveTitleMatches(
      Session session, ConversationDraft request, String title, List<AudiobookTitle> matches) {
    boolean truncated = matches.size() > MAX_CHOICES;
    String author = PersonNameNormalizer.comparisonKey(request.getSimilarToAuthor());
    if (!author.isBlank()) {
      matches =
          matches.stream()
              .filter(
                  book ->
                      book.authors().stream()
                          .anyMatch(
                              value -> PersonNameNormalizer.comparisonKey(value).equals(author)))
              .toList();
    }
    // Prefer exact-title results, but keep fuzzy matches when no exact title exists.
    var exact =
        matches.stream()
            .filter(
                book ->
                    AudiobookTitle.normalize(book.title()).equals(AudiobookTitle.normalize(title)))
            .toList();
    var candidates =
        java.util.stream.Stream.concat(exact.stream(), matches.stream()).distinct().toList();
    var unique = distinctTitleAndAuthors(candidates);

    if (matches.isEmpty()) {
      clarify(session, request, ConversationDecision.SIMILAR_BOOK_NOT_FOUND, List.of());
    } else if (!truncated && unique.size() == 1) {
      loadVector(session, request, unique.getFirst());
      if (request.getDecision() == ConversationDecision.SIMILAR_BOOK_UNAVAILABLE) {
        request.setSearchClarificationRequired(true);
      } else {
        session.setPendingQuestion(null);
      }
    } else {
      clarify(
          session,
          request,
          ConversationDecision.SIMILAR_BOOK_SELECTION,
          unique.stream().limit(MAX_CHOICES).toList());
    }
  }

  /** Keep the first catalogue record for each normalised title + author-set identity. */
  private static List<AudiobookTitle> distinctTitleAndAuthors(List<AudiobookTitle> candidates) {
    var unique = new LinkedHashMap<String, AudiobookTitle>();
    for (var book : candidates) {
      String authors =
          book.authors().stream()
              .map(PersonNameNormalizer::comparisonKey)
              .distinct()
              .sorted()
              .collect(Collectors.joining("\u0000"));
      String key = AudiobookTitle.normalize(book.title()) + "\u0000" + authors;
      unique.putIfAbsent(key, book);
    }
    return List.copyOf(unique.values());
  }

  private void loadVector(Session session, ConversationDraft request, AudiobookTitle book) {
    try {
      var vector = vectors.findEmbeddingByBookId(book.bookId()).orElse(null);
      if (vector == null || vector.length == 0) {
        unavailable(session, request, book);
        return;
      }
      try {
        vector = VectorMath.normalize(vector);
      } catch (IllegalArgumentException exception) {
        unavailable(session, request, book);
        return;
      }
      request
          .getSearchContext()
          .setSimilarBook(
              new SimilarBookReference(book.bookId(), book.title(), book.authors(), vector));
    } catch (IOException exception) {
      throw new CandidateRetrievalException("catalogue reference vector", exception);
    }
  }

  private void unavailable(Session session, ConversationDraft request, AudiobookTitle book) {
    LOGGER.warn("similar.book.vector.unavailable bookId={}", book.bookId());
    clarify(session, request, ConversationDecision.SIMILAR_BOOK_UNAVAILABLE, List.of());
  }

  private void clarify(
      Session session,
      ConversationDraft request,
      ConversationDecision decision,
      List<AudiobookTitle> choices) {
    request.setDecision(decision);
    request.setSearchClarificationRequired(true);
    request.setSimilarBookChoices(choices);
    var pendingRequest = ConversationDraft.toStoredTurn(request);
    var pending = new PendingQuestion.SimilarBook();
    pending.setDecision(decision);
    pending.setRequest(pendingRequest);
    session.setPendingQuestion(pending);
  }
}
