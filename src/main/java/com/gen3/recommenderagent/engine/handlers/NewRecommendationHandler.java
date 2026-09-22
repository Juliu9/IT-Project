package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.CandidateRetriever;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RecommendationQueryBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NewRecommendationHandler implements IntentHandler {

    private static final int CANDIDATE_LIMIT = 50;
    private static final int DEFAULT_RESULT_LIMIT = 5;
    private static final int MAX_RESULT_LIMIT = 5;

    private final CandidateRetriever candidateRetriever;
    private final Ranker ranker;
    private final RecommendationQueryBuilder queryBuilder;

    public NewRecommendationHandler(
            CandidateRetriever candidateRetriever,
            Ranker ranker,
            RecommendationQueryBuilder queryBuilder
    ) {
        this.candidateRetriever = candidateRetriever;
        this.ranker = ranker;
        this.queryBuilder = queryBuilder;
    }

    @Override
    public Intent supportedIntent() {
        return Intent.NEW_RECOMMENDATION;
    }

    @Override
    public List<Recommendation> handle(SessionRequest request, Session session) {
        String query = queryBuilder.build(request);

        var candidates = candidateRetriever.getCandidates(
                query,
                CANDIDATE_LIMIT
        );

        return ranker.rank(
                candidates,
                resolveResultLimit(request),
                request.isPersonalised()
        );
    }

    private int resolveResultLimit(SessionRequest request) {
        if (request.getConstraints() == null
                || request.getConstraints().getCount() == null) {
            return DEFAULT_RESULT_LIMIT;
        }

        return Math.clamp(request.getConstraints().getCount(), 1,
                MAX_RESULT_LIMIT
        );
    }
}