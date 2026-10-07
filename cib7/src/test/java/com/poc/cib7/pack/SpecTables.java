package com.poc.cib7.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the tables of a service spec (markdown) for the pack checks that run a spec's examples.
 * Only the shape the service-builder's spec template uses: a {@code ## Heading} (or {@code ###}),
 * then a pipe table whose first row is the header and second the separator.
 */
final class SpecTables {

  private SpecTables() {}

  /** A table's rows, each a map from header to cell text (backticks kept). */
  record Table(List<String> header, List<Map<String, String>> rows) {}

  /** The first table under the heading whose text equals {@code heading}, if there is one. */
  static Optional<Table> under(String markdown, String heading) {
    String[] lines = markdown.replace("\r\n", "\n").split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (isHeading(lines[i]) && headingText(lines[i]).equals(heading)) {
        return firstTable(lines, i + 1);
      }
    }
    return Optional.empty();
  }

  /** Every heading text that starts with {@code prefix}, in document order. */
  static List<String> headingsStartingWith(String markdown, String prefix) {
    List<String> out = new ArrayList<>();
    for (String line : markdown.replace("\r\n", "\n").split("\n")) {
      if (isHeading(line) && headingText(line).startsWith(prefix)) {
        out.add(headingText(line));
      }
    }
    return out;
  }

  /** The backticked value of a {@code **Label:** `value`} line. */
  static Optional<String> field(String markdown, String label) {
    Matcher m =
        Pattern.compile("\\*\\*" + Pattern.quote(label) + ":\\*\\*\\s*`([^`]+)`").matcher(markdown);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  /** A cell without its surrounding backticks. */
  static String unquote(String cell) {
    String s = cell.strip();
    return s.length() >= 2 && s.startsWith("`") && s.endsWith("`")
        ? s.substring(1, s.length() - 1)
        : s;
  }

  private static boolean isHeading(String line) {
    return line.startsWith("## ") || line.startsWith("### ");
  }

  private static String headingText(String line) {
    return line.replaceFirst("^#+\\s+", "").strip();
  }

  private static Optional<Table> firstTable(String[] lines, int from) {
    int i = from;
    while (i < lines.length && !lines[i].strip().startsWith("|")) {
      if (isHeading(lines[i])) {
        return Optional.empty();
      }
      i++;
    }
    if (i + 1 >= lines.length) {
      return Optional.empty();
    }
    List<String> header = cells(lines[i]);
    List<Map<String, String>> rows = new ArrayList<>();
    for (int r = i + 2; r < lines.length && lines[r].strip().startsWith("|"); r++) {
      List<String> cells = cells(lines[r]);
      Map<String, String> row = new LinkedHashMap<>();
      for (int c = 0; c < header.size(); c++) {
        row.put(header.get(c), c < cells.size() ? cells.get(c) : "");
      }
      rows.add(row);
    }
    return Optional.of(new Table(header, rows));
  }

  /** Splits a table row on unescaped pipes. */
  private static List<String> cells(String line) {
    String s = line.strip();
    s = s.substring(1, s.endsWith("|") ? s.length() - 1 : s.length());
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
        cell.append('|');
        i++;
      } else if (ch == '|') {
        out.add(cell.toString().strip());
        cell.setLength(0);
      } else {
        cell.append(ch);
      }
    }
    out.add(cell.toString().strip());
    return out;
  }
}
