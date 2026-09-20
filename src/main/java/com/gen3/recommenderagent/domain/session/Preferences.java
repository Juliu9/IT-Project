package com.gen3.recommenderagent.domain.session;

import java.util.List;

public class Preferences {

  private List<String> include;
  private List<String> exclude;

  public Preferences() {}

  public List<String> getInclude() {
    return include;
  }

  public void setInclude(List<String> include) {
    this.include = include;
  }

  public List<String> getExclude() {
    return exclude;
  }

  public void setExclude(List<String> exclude) {
    this.exclude = exclude;
  }
}
