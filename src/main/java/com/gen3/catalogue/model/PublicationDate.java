package com.gen3.catalogue.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Valid publication dates; absent or malformed catalogue dates never become guessed years. */
public final class PublicationDate {
  private PublicationDate() {}

  public static String normalize(Object value) {
    if (value == null) {
      return null;
    }
    String text =
        value instanceof java.util.Date date
            ? date.toInstant().toString()
            : value.toString().strip();
    try {
      if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
        LocalDate date = LocalDate.parse(text);
        return date.getYear() >= 1 ? date.toString() : null;
      }
      Instant instant = OffsetDateTime.parse(text).toInstant();
      int year = instant.atOffset(ZoneOffset.UTC).getYear();
      return year >= 1 && year <= 9999 ? instant.toString() : null;
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  public static Integer year(String pubdate) {
    String normalized = normalize(pubdate);
    return normalized == null ? null : Integer.valueOf(normalized.substring(0, 4));
  }

  public static Instant startOfYear(int year) {
    return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
