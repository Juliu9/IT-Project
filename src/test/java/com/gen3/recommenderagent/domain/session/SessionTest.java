package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SessionTest {

  @Test
  void defaultsAndCapsBookCountAtTheApplicationMaximum() {
    Session session = new Session();

    assertEquals(MAX_BOOK_COUNT, session.getBookCount());

    session.setBookCount(MAX_BOOK_COUNT + 3);
    assertEquals(MAX_BOOK_COUNT, session.getBookCount());

    session.setBookCount(3);
    assertEquals(3, session.getBookCount());

    session.setBookCount(null);
    assertEquals(MAX_BOOK_COUNT, session.getBookCount());
  }
}
