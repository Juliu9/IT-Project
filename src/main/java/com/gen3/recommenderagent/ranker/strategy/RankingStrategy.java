package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.domain.session.Recommendation;
import java.util.List;
import org.apache.solr.common.SolrDocument;

public interface RankingStrategy {

  List<Recommendation> rank(List<SolrDocument> candidates, int requestedLimit);
}
