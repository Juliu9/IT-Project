-- Run explicitly against the history database as a schema owner, before importing or serving.
-- The primary key supports per-user lookups and deduplicates repeated CSV imports.
CREATE TABLE IF NOT EXISTS read_history (
    user_id varchar(256) NOT NULL CHECK (length(btrim(user_id)) > 0),
    book_id text NOT NULL CHECK (length(btrim(book_id)) > 0),
    PRIMARY KEY (user_id, book_id)
);
