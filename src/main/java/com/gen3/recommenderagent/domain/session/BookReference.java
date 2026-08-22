package com.gen3.recommenderagent.domain.session;

public class BookReference {
    private String bookId;
    private String context;

    public BookReference() {}

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
}
