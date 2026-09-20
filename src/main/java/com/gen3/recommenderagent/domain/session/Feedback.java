package com.gen3.recommenderagent.domain.session;

public class Feedback {

  private String type;
  private String reason;

  public Feedback() {}

  public Feedback(String type, String reason) {
    this.type = type;
    this.reason = reason;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
