package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendations;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.CandidateRetriever;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.storage.sessioncache.SessionCache;
import com.gen3.recommenderagent.storage.userprofiledb.UserProfileDB;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RecommendationEngine {

    private static final int CANDIDATE_LIMIT = 50;
    private static final int DEFAULT_RESULT_LIMIT = 5;
    private static final int MAX_RESULT_LIMIT = 5;

    private final SessionCache sessionCache;
    private final ApplicationEventPublisher eventPublisher;
    private final CandidateRetriever candidateRetriever;
    private final RankingService rankingService;
    private final UserProfileDB userProfileDB;

    public RecommendationEngine(
            SessionCache sessionCache,
            UserProfileDB userProfileDB,
            ApplicationEventPublisher eventPublisher,
            CandidateRetriever candidateRetriever,
            RankingService rankingService
    ) {
        this.sessionCache = sessionCache;
        this.userProfileDB = userProfileDB;
        this.eventPublisher = eventPublisher;
        this.candidateRetriever = candidateRetriever;
        this.rankingService = rankingService;
    }

    public Recommendations process(
            String sessionId,
            String userId,
            SessionRequest currentRequest
    ) {

        // ==========================================
        // 1. Read current session from Redis
        // ==========================================

        Session session = sessionCache.getSession(sessionId);

        if (session == null) {
            session = new Session();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setCreatedAt(Instant.now());
        }

        // ==========================================
        // 2. Resolve context
        // ==========================================

        Intent intent = currentRequest.getIntent();
        if (intent == null) {
            intent = Intent.UNKNOWN;
            currentRequest.setIntent(intent);
        }

        // TODO:
        // Use session history + currentRequest to resolve
        // additional context required by the intent.

        /*
        USE PREFERENCES TO BIAS WEIGHTS WHEN RANKING RECOMMENDATIONS

        List<Preference> userPreferences;
        if currentRequest.isPersonalised() {
            userPreferences = userProfileDB.getReferenceById(userId).getPreferences();
        }
         */

        // ==========================================
        // 3. Handle request based on intent
        // ==========================================

        Recommendations recommendations;

        switch (intent) {

            // ==========================================
            // Core Recommendation Requests
            // ==========================================

            case NEW_RECOMMENDATION:
                recommendations = handleNewRecommendation(currentRequest);
                break;

            case SIMILAR_TO_BOOK:
                recommendations = handleSimilarToBook(currentRequest);
                break;

            case SIMILAR_TO_AUTHOR:
                recommendations = handleSimilarToAuthor(currentRequest);
                break;

            case SIMILAR_TO_NARRATOR:
                recommendations = handleSimilarToNarrator(currentRequest);
                break;


            // ==========================================
            // Refinement & Filtering
            // ==========================================

            case REFINE_RECOMMENDATION:
                recommendations = handleRefineRecommendation(
                        currentRequest,
                        session
                );
                break;

            case FILTER_BY_LENGTH:
                recommendations = handleFilterByLength(
                        currentRequest,
                        session
                );
                break;

            case FILTER_BY_NARRATOR:
                recommendations = handleFilterByNarrator(
                        currentRequest,
                        session
                );
                break;

            // ==========================================
            // Pagination & Quantity
            // ==========================================

            case MORE_RESULTS:
                recommendations = handleMoreResults(
                        currentRequest,
                        session
                );
                break;

            case CHANGE_COUNT:
                recommendations = handleChangeCount(
                        currentRequest,
                        session
                );
                break;


            // ==========================================
            // User Feedback
            // ==========================================

            case REJECT_RECOMMENDATIONS:
                recommendations = handleRejectRecommendations(
                        currentRequest,
                        session
                );
                break;

            case ALREADY_READ:
                recommendations = handleAlreadyRead(
                        currentRequest,
                        session
                );
                break;

            case LIKE_RECOMMENDATION:
                recommendations = handleLikeRecommendation(
                        currentRequest,
                        session
                );
                break;


            // ==========================================
            // Information & Actions
            // ==========================================

            case BOOK_DETAILS:
                recommendations = handleBookDetails(
                        currentRequest,
                        session
                );
                break;


            // ==========================================
            // Profile & Session Management
            // ==========================================

            case UPDATE_PREFERENCES:
                recommendations = handleUpdatePreferences(
                        currentRequest,
                        session
                );
                break;

            case CLEAR_HISTORY:
                recommendations = handleClearHistory(
                        currentRequest,
                        session
                );
                break;


            // ==========================================
            // Conversational & System
            // ==========================================
            case HELP:
                recommendations = handleHelp(currentRequest);
                break;

            case UNKNOWN:
            default:
                recommendations = handleUnknown(currentRequest);
                break;
        }


        // ==========================================
        // 4. Attach results to request + update session
        // ==========================================

        currentRequest.setRequestId(UUID.randomUUID().toString());
        currentRequest.setRecommendations(recommendations);
        currentRequest.setCreatedAt(Instant.now());

        session.addRequest(currentRequest);


        // ==========================================
        // 5. Notify observers
        // ==========================================

        eventPublisher.publishEvent(
                new SessionUpdateEvent(session)
        );

        return recommendations;
    }


    // ==================================================
    // Core Recommendation Handlers
    // ==================================================

    private Recommendations handleNewRecommendation(
            SessionRequest request
    ) {

        // Example:
        // User: "Recommend me a sci-fi audiobook"

        String query = buildRecommendationQuery(request);

        // Candidate retrieval
        var candidates = candidateRetriever.getCandidates(
                query,
                CANDIDATE_LIMIT
        );

        return rankingService.rank(
                candidates,
                resolveResultLimit(request)
        );
    }


    private Recommendations handleSimilarToBook(
            SessionRequest request
    ) {

        // TODO:
        // Extract the referenced book.
        // Build a MoreLikeThis Solr query.
        // Retrieve candidates.
        // Rank candidates.

        return new Recommendations();
    }


    private Recommendations handleSimilarToAuthor(
            SessionRequest request
    ) {

        // TODO:
        // Extract author.
        // Build author query.
        // Retrieve candidates.
        // Rank candidates.

        return new Recommendations();
    }


    private Recommendations handleSimilarToNarrator(
            SessionRequest request
    ) {

        // TODO:
        // Extract narrator.
        // Build narrator query.
        // Retrieve candidates.
        // Rank candidates.

        return new Recommendations();
    }


    // ==================================================
    // Refinement & Filtering Handlers
    // ==================================================

    private Recommendations handleRefineRecommendation(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Use previous recommendations from session.
        // Apply the new refinement.
        // Re-rank/filter.

        return new Recommendations();
    }


    private Recommendations handleFilterByLength(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Filter candidates by audiobook duration.

        return new Recommendations();
    }


    private Recommendations handleFilterByNarrator(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Filter by narrator / recording type.

        return new Recommendations();
    }


    // ==================================================
    // Pagination & Quantity Handlers
    // ==================================================

    private Recommendations handleMoreResults(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Retrieve additional candidates
        // excluding previously shown results.

        return new Recommendations();
    }


    private Recommendations handleChangeCount(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Re-rank or retrieve enough candidates
        // to satisfy the requested count.

        return new Recommendations();
    }


    // ==================================================
    // User Feedback Handlers
    // ==================================================

    private Recommendations handleRejectRecommendations(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Record rejected recommendations.
        // Generate a new candidate set.

        return new Recommendations();
    }


    private Recommendations handleAlreadyRead(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Remove audiobook from future candidates.
        // Generate replacement.

        return new Recommendations();
    }


    private Recommendations handleLikeRecommendation(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Record liked recommendation.
        // Potentially update user profile.

        return new Recommendations();
    }


    // ==================================================
    // Information & Actions
    // ==================================================

    private Recommendations handleBookDetails(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Find requested book and return its details.

        return new Recommendations();
    }


    // ==================================================
    // Profile & Session Management
    // ==================================================

    private Recommendations handleUpdatePreferences(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Update user preferences.

        return new Recommendations();
    }


    private Recommendations handleClearHistory(
            SessionRequest request,
            Session session
    ) {

        // TODO:
        // Clear session history.

        return new Recommendations();
    }


    // ==================================================
    // Conversational & System
    // ==================================================

    private Recommendations handleGreeting(
            SessionRequest request
    ) {

        // TODO:
        // Return conversational response.

        return new Recommendations();
    }


    private Recommendations handleHelp(
            SessionRequest request
    ) {

        // TODO:
        // Return information about available functionality.

        return new Recommendations();
    }


    private Recommendations handleUnknown(
            SessionRequest request
    ) {

        // TODO:
        // Return a fallback response.

        return new Recommendations();
    }


    // ==================================================
    // Query Construction
    // ==================================================

    private String buildRecommendationQuery(
            SessionRequest request
    ) {
        Set<String> generalTerms = new LinkedHashSet<>();
        Set<String> authors = new LinkedHashSet<>();
        Set<String> excludedTerms = new LinkedHashSet<>();

        if (request.getQuery() != null) {
            addTerms(generalTerms, request.getQuery().getTopics());
            addTerms(generalTerms, request.getQuery().getGenres());
            addTerms(generalTerms, request.getQuery().getKeywords());
            addTerms(authors, request.getQuery().getAuthors());
        }

        if (request.getPreferences() != null) {
            addTerms(generalTerms, request.getPreferences().getInclude());
            addTerms(excludedTerms, request.getPreferences().getExclude());
        }

        List<String> clauses = new ArrayList<>();

        if (!generalTerms.isEmpty()) {
            clauses.add(buildFieldClause("all", generalTerms));
        }

        if (!authors.isEmpty()) {
            clauses.add(buildFieldClause("authors", authors));
        }

        String positiveQuery = clauses.isEmpty()
                ? "*:*"
                : String.join(" AND ", clauses);

        if (!excludedTerms.isEmpty()) {
            positiveQuery += " AND -" + buildFieldClause("all", excludedTerms);
        }

        return positiveQuery;
    }

    private void addTerms(Set<String> destination, List<String> terms) {
        if (terms == null) {
            return;
        }

        terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(String::trim)
                .forEach(destination::add);
    }

    private String buildFieldClause(String field, Set<String> terms) {
        String values = terms.stream()
                .map(ClientUtils::escapeQueryChars)
                .map(term -> "\"" + term + "\"")
                .reduce((left, right) -> left + " OR " + right)
                .orElseThrow();

        return field + ":(" + values + ")";
    }

    private int resolveResultLimit(SessionRequest request) {
        if (request.getConstraints() == null
                || request.getConstraints().getCount() == null) {
            return DEFAULT_RESULT_LIMIT;
        }

        return Math.min(
                Math.max(request.getConstraints().getCount(), 1),
                MAX_RESULT_LIMIT
        );
    }
}
