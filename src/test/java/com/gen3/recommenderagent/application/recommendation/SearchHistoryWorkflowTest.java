package com.gen3.recommenderagent.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.search.SimilarBookReference;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.storage.user.model.SearchHistoryProfile;
import com.gen3.recommenderagent.storage.user.port.SearchHistoryRepository;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.recommenderagent.testsupport.TestHandlers;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchHistoryWorkflowTest {

  private static final float[] HORROR = {1, 0};

  private final CandidateRetriever retriever = mock(CandidateRetriever.class);
  private final Ranker ranker = mock(Ranker.class);
  private final SemanticQueryVectorService semantic = mock(SemanticQueryVectorService.class);
  private final UserPreferenceVectorRepository storedTaste =
      mock(UserPreferenceVectorRepository.class);
  private final InMemoryRepository history = new InMemoryRepository();
  private final Session session = new Session();
  private AudiobookRecommendationWorkflow workflow;

  @BeforeEach
  void setUp() {
    when(retriever.getCandidates(any(), any(), eq(50), any())).thenReturn(List.of());
    when(ranker.rank(anyList(), eq(20), any(RankingContext.class))).thenReturn(List.of());
    session.setUserId("user");
    workflow = workflowWith(history);
  }

  @Test
  void explicitSearchesBuildTheTaste() {
    searchForHorror(3);

    assertThat(history.profiles.get("user").searchCount()).isEqualTo(3);
  }

  @Test
  void tasteRanksMatchingBooksAfterThreeSearches() {
    searchForHorror(2);
    assertThat(lastRankingContext().searchHistoryTaste()).isNull();

    searchForHorror(2);
    assertThat(lastRankingContext().searchHistoryTaste()).containsExactly(HORROR, within(1e-6f));
  }

  @Test
  void vagueRequestFindsBooksNearTheTasteWithoutAsking() {
    searchForHorror(3);
    when(semantic.create(any())).thenReturn(SemanticQueryVectors.empty());
    var vague = request(RecommendationHandler.ACTION);
    vague.setPersonalised(true);

    workflow.recommend(vague, session);

    assertThat(vague.isPreferenceClarificationRequired()).isFalse();
    var vectors = ArgumentCaptor.forClass(SemanticQueryVectors.class);
    verify(retriever, org.mockito.Mockito.atLeastOnce())
        .getCandidates(eq(vague), vectors.capture(), eq(50), any());
    assertThat(vectors.getValue().positive().getFirst()).containsExactly(HORROR, within(1e-6f));
  }

  @Test
  void newUserIsStillAskedWhatTheyLike() {
    when(semantic.create(any())).thenReturn(SemanticQueryVectors.empty());
    var vague = request(RecommendationHandler.ACTION);
    vague.setPersonalised(true);

    workflow.recommend(vague, session);

    assertThat(vague.isPreferenceClarificationRequired()).isTrue();
  }

  @Test
  void moreResultsKeepsTheFrozenTasteAfterReloadingTheSessionAndRecordsNothing() {
    searchForHorror(3);
    var search = request(RecommendationHandler.ACTION);
    workflow.recommend(search, session);
    session.getActiveSearch().setSearch(ConversationDraft.toStoredTurn(search));
    var mapper = new tools.jackson.databind.ObjectMapper();
    var reloaded = mapper.readValue(mapper.writeValueAsString(session), Session.class);
    history.profiles.clear();

    var more =
        TestHandlers.registry()
            .handler(MoreResultsHandler.ACTION)
            .resolve(reloaded, request(MoreResultsHandler.ACTION));
    workflow.recommend(more, reloaded);

    assertThat(lastRankingContext().searchHistoryTaste()).containsExactly(HORROR, within(1e-6f));
    assertThat(history.profiles).isEmpty();
  }

  @Test
  void similarBookRequestsNeitherUseNorTeachTheTaste() {
    searchForHorror(3);
    var likeThisBook = request(RecommendationHandler.ACTION);
    likeThisBook
        .getSearchContext()
        .setSimilarBook(new SimilarBookReference("b", "A Book", List.of(), new float[] {0, 1}));

    workflow.recommend(likeThisBook, session);

    assertThat(lastRankingContext().searchHistoryTaste()).isNull();
    assertThat(history.profiles.get("user").searchCount()).isEqualTo(3);
  }

  @Test
  void switchedOffChangesNothing() {
    workflow =
        new AudiobookRecommendationWorkflow(
            retriever, ranker, semantic, storedTaste, TestHandlers.registry());

    searchForHorror(3);

    assertThat(lastRankingContext().searchHistoryTaste()).isNull();
    assertThat(history.profiles).isEmpty();
  }

  @Test
  void switchedOffIgnoresPreviouslyFrozenTaste() {
    var more = request(MoreResultsHandler.ACTION);
    more.setUserPreferencesLoaded(true);
    more.getSearchContext().setSearchHistorySnapshot(HORROR);
    when(semantic.create(any())).thenReturn(SemanticQueryVectors.empty());
    workflow =
        new AudiobookRecommendationWorkflow(
            retriever, ranker, semantic, storedTaste, TestHandlers.registry());

    workflow.recommend(more, session);

    assertThat(lastRankingContext().searchHistoryTaste()).isNull();
    assertThat(more.getSearchContext().getSearchHistorySnapshot()).isNull();
  }

  @Test
  void storeOutageStillRecommends() throws Exception {
    var broken = mock(SearchHistoryRepository.class);
    when(broken.findByUserId(any())).thenThrow(new IOException("down"));
    workflow = workflowWith(broken);

    searchForHorror(1);

    verify(ranker).rank(anyList(), eq(20), any(RankingContext.class));
  }

  private AudiobookRecommendationWorkflow workflowWith(SearchHistoryRepository repository) {
    return new AudiobookRecommendationWorkflow(
        retriever,
        ranker,
        semantic,
        storedTaste,
        TestHandlers.registry(),
        new SearchHistoryPreferences(Optional.of(repository), 0.2, 3));
  }

  private void searchForHorror(int times) {
    when(semantic.create(any())).thenReturn(new SemanticQueryVectors(List.of(HORROR), List.of()));
    for (int i = 0; i < times; i++) {
      workflow.recommend(request(RecommendationHandler.ACTION), session);
    }
  }

  private ConversationDraft request(ConversationAction intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    request.setRequestId("request");
    return request;
  }

  private RankingContext lastRankingContext() {
    var context = ArgumentCaptor.forClass(RankingContext.class);
    verify(ranker, org.mockito.Mockito.atLeastOnce()).rank(anyList(), eq(20), context.capture());
    return context.getValue();
  }

  private static final class InMemoryRepository implements SearchHistoryRepository {
    private final Map<String, SearchHistoryProfile> profiles = new HashMap<>();

    @Override
    public Optional<SearchHistoryProfile> findByUserId(String userId) {
      return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public void save(SearchHistoryProfile profile) {
      profiles.put(profile.userId(), profile);
    }

    @Override
    public void delete(String userId) {
      profiles.remove(userId);
    }
  }
}
