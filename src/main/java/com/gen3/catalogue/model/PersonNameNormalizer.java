package com.gen3.catalogue.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative name-order variants; catalogue values and person identities remain unchanged. */
public final class PersonNameNormalizer {
  private static final Pattern INITIALS = Pattern.compile("\\p{L}\\.(?: ?\\p{L}\\.)*");
  private static final Pattern SUFFIX =
      Pattern.compile("(?i)(?:^|[\\s,])(?:jr|sr|ii|iii|iv|v|vi)\\.?$");

  private PersonNameNormalizer() {}

  public static List<String> variants(List<String> names) {
    var values = new LinkedHashSet<String>();
    if (names != null) {
      for (String name : names) {
        if (name == null || name.isBlank()) {
          continue;
        }
        values.add(name.strip());
        String clean = clean(name);
        values.add(clean);
        String[] parts = commaParts(clean);
        if (parts != null) {
          values.add(parts[0] + ", " + parts[1]);
          values.add(parts[1] + " " + parts[0]);
        } else if (!clean.contains(",") && !SUFFIX.matcher(clean).find()) {
          int surnameStart = clean.lastIndexOf(' ');
          if (surnameStart > 0) {
            String givenNames = clean.substring(0, surnameStart);
            if (!givenNames.contains(" ") || INITIALS.matcher(givenNames).matches()) {
              values.add(clean.substring(surnameStart + 1) + ", " + givenNames);
            }
          }
        }
      }
    }
    return List.copyOf(values);
  }

  public static String comparisonKey(String name) {
    String clean = clean(name);
    String[] parts = commaParts(clean);
    return (parts == null ? clean : parts[1] + " " + parts[0]).toLowerCase(Locale.ROOT);
  }

  private static String clean(String name) {
    return name == null ? "" : name.strip().replaceAll("(?U)\\s+", " ");
  }

  private static String[] commaParts(String name) {
    String[] parts = name.split(",", -1);
    if (parts.length != 2) {
      return null;
    }
    parts[0] = parts[0].strip();
    parts[1] = parts[1].strip();
    return parts[0].isEmpty()
            || parts[1].isEmpty()
            || SUFFIX.matcher(parts[0]).find()
            || (SUFFIX.matcher(parts[1]).find() && !INITIALS.matcher(parts[1]).matches())
        ? null
        : parts;
  }
}
