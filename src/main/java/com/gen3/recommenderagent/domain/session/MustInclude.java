package com.gen3.recommenderagent.domain.session;

import java.util.List;

/** Exact catalogue values that every retrieved audiobook must satisfy. */
public class MustInclude {

  private List<String> authors;
  private List<String> narrators;
  private String language;
  private String duration;
  private String source;

  public MustInclude() {}

  public List<String> getAuthors() {
    return authors;
  }

  public void setAuthors(List<String> authors) {
    this.authors = authors;
  }

  public List<String> getNarrators() {
    return narrators;
  }

  public void setNarrators(List<String> narrators) {
    this.narrators = narrators;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getDuration() {
    return duration;
  }

  public void setDuration(String duration) {
    this.duration = duration;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
