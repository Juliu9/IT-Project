package com.gen3.recommenderagent.ranker.strategy;

import com.gen3.recommenderagent.domain.session.Recommendation;
import org.apache.solr.common.SolrDocument;

import java.util.List;

public interface RankingStrategy {

    List<Recommendation> rank(List<SolrDocument> candidates, int requestedLimit);
}