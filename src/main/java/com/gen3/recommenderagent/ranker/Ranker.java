package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Recommendation;
import java.util.List;
import org.apache.solr.common.SolrDocument;

public interface Ranker {

  List<Recommendation> rank(List<SolrDocument> candidates, int requestedLimit);

  List<Recommendation> rank(
      List<SolrDocument> candidates, int requestedLimit, boolean personalised);
}
