package com.gen3.recommenderagent.application.recommendation;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.catalogue.model.PersonNameNormalizer;
import com.gen3.catalogue.port.AudiobookTitleLookup;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.catalogue.vector.VectorMath;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.PendingSimilarBook;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SimilarBookReference;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalException;
import java.io.IOException;
import java.util.List;
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
    if (request.getIntent() != Intent.RECOMMENDATION
        && request.getIntent() != Intent.REFINE
        && request.getIntent() != Intent.MORE_RESULTS) {
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
            request,
            new AudiobookTitle(reference.bookId(), reference.title(), reference.authors()));
      }
      if (request.getDecision() != ConversationDecision.SIMILAR_BOOK_UNAVAILABLE) {
        session.setPendingSimilarBook(null);
      }
      return;
    }
    String title = request.getSimilarToTitle();
    if (title == null || title.isBlank()) {
      if (request.getIntent() == Intent.RECOMMENDATION) {
        session.setPendingSimilarBook(null);
      }
      return;
    }
    try {
      var matches = titles.findBooksByTitle(title, request.getSimilarToAuthor(), MAX_CHOICES + 1);
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
      var exact =
          matches.stream()
              .filter(
                  book ->
                      AudiobookTitle.normalize(book.title())
                          .equals(AudiobookTitle.normalize(title)))
              .toList();
      if (!truncated && exact.size() == 1) {
        loadVector(request, exact.getFirst());
        if (request.getDecision() == ConversationDecision.SIMILAR_BOOK_UNAVAILABLE) {
          request.setSearchClarificationRequired(true);
        } else {
          session.setPendingSimilarBook(null);
        }
      } else if (matches.isEmpty()) {
        clarify(session, request, ConversationDecision.SIMILAR_BOOK_NOT_FOUND, List.of());
      } else {
        clarify(
            session,
            request,
            ConversationDecision.SIMILAR_BOOK_SELECTION,
            java.util.stream.Stream.concat(exact.stream(), matches.stream())
                .distinct()
                .limit(MAX_CHOICES)
                .toList());
      }
    } catch (IOException exception) {
      throw new CandidateRetrievalException("catalogue title lookup", exception);
    }
  }

  private void loadVector(ConversationDraft request, AudiobookTitle book) {
    try {
      var vector = vectors.findEmbeddingByBookId(book.bookId()).orElse(null);
      if (vector == null || vector.length == 0) {
        unavailable(request, book);
        return;
      }
      try {
        vector = VectorMath.normalize(vector);
      } catch (IllegalArgumentException exception) {
        unavailable(request, book);
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

  private void unavailable(ConversationDraft request, AudiobookTitle book) {
    LOGGER.warn("similar.book.vector.unavailable bookId={}", book.bookId());
    request.setDecision(ConversationDecision.SIMILAR_BOOK_UNAVAILABLE);
    request.setSearchClarificationRequired(true);
  }

  private void clarify(
      Session session,
      ConversationDraft request,
      ConversationDecision decision,
      List<AudiobookTitle> choices) {
    request.setDecision(decision);
    request.setSearchClarificationRequired(true);
    request.setSimilarBookChoices(choices);
    var pendingRequest = ConversationTurn.fromDraft(request);
    pendingRequest.setRawText(null);
    session.setPendingSimilarBook(new PendingSimilarBook(pendingRequest, decision));
  }
}
