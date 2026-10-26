package com.gen3.catalogue.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative person-name formatting variants; catalogue records remain unchanged. */
public final class PersonNameNormalizer {
  private static final Pattern INITIALS = Pattern.compile("\\p{L}\\.(?: ?\\p{L}\\.)*");
  private static final Pattern SEPARATED_INITIALS = Pattern.compile("\\p{L}\\.?(?: \\p{L}\\.?)+");
  private static final Pattern LEADING_INITIALS = Pattern.compile("^(\\p{L}\\.(?: ?\\p{L}\\.)*) +");
  private static final Pattern SUFFIX =
      Pattern.compile("(?i)(?:^|[\\s,])(?:jr|sr|ii|iii|iv|v|vi)\\.?$");

  private PersonNameNormalizer() {}

  public static List<String> variants(List<String> names) {
    var values = new LinkedHashSet<String>();
    if (names == null) {
      return List.of();
    }
    for (String name : names) {
      if (name == null || name.isBlank()) {
        continue;
      }
      var forms = new LinkedHashSet<String>();
      forms.add(name.strip());
      forms.add(clean(name));
      String base = withoutCataloguePeriod(clean(name));
      forms.add(base);
      String[] parts = commaParts(base);
      boolean ordered = false;
      if (parts != null) {
        addOrders(forms, parts[1], parts[0]);
        ordered = true;
      } else if (!base.contains(",")
          && !SUFFIX.matcher(base).find()
          && !INITIALS.matcher(base).matches()
          && !SEPARATED_INITIALS.matcher(base).matches()) {
        int split = base.lastIndexOf(' ');
        if (split > 0) {
          String given = base.substring(0, split);
          if (!given.contains(" ")
              || INITIALS.matcher(given).matches()
              || SEPARATED_INITIALS.matcher(given).matches()) {
            addOrders(forms, given, base.substring(split + 1));
            ordered = true;
          }
        }
      }
      if (ordered) {
        for (String form : List.copyOf(forms)) {
          if (!form.endsWith(".") && !SUFFIX.matcher(form).find()) {
            forms.add(form + ".");
          }
        }
      }
      values.addAll(forms);
    }
    return List.copyOf(values);
  }

  private static void addOrders(Set<String> forms, String given, String family) {
    forms.add(given + " " + family);
    forms.add(family + ", " + given);
    if (SEPARATED_INITIALS.matcher(given).matches()) {
      given = given.replace(".", "").replaceAll("(\\p{L})", "$1.");
    }
    if (INITIALS.matcher(given).matches()) {
      String compact = given.replace(" ", "");
      String spaced = compact.replaceAll("(?<=\\.)(?=\\p{L})", " ");
      for (String initials : List.of(compact, spaced, spaced.replace(".", ""))) {
        forms.add(initials + " " + family);
        forms.add(family + ", " + initials);
      }
    }
  }

  public static String comparisonKey(String name) {
    String base = withoutCataloguePeriod(clean(name));
    String[] parts = commaParts(base);
    String forward = parts == null ? base : parts[1] + " " + parts[0];
    int surname = forward.lastIndexOf(' ');
    if (surname > 0 && SEPARATED_INITIALS.matcher(forward.substring(0, surname)).matches()) {
      forward =
          forward.substring(0, surname).replace(".", "").replaceAll("(\\p{L})", "$1.")
              + forward.substring(surname);
    }
    var initials = LEADING_INITIALS.matcher(forward);
    if (initials.find()) {
      forward = initials.group(1).replace(" ", "") + " " + forward.substring(initials.end());
    }
    return forward.toLowerCase(Locale.ROOT);
  }

  private static String clean(String name) {
    return name == null ? "" : name.strip().replaceAll("(?U)\\s+", " ");
  }

  private static String withoutCataloguePeriod(String name) {
    if (!name.endsWith(".") || SUFFIX.matcher(name).find()) {
      return name;
    }
    String last = name.substring(Math.max(name.lastIndexOf(' '), name.lastIndexOf(',')) + 1);
    return last.matches("\\p{L}{2,}\\.") && !INITIALS.matcher(last).matches()
        ? name.substring(0, name.length() - 1)
        : name;
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
