package com.storeql.payment.settlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a delimited text file as RFC 4180 has it — quoted fields, doubled quotes inside them, line
 * breaks inside quotes, CRLF or LF — with the two liberties every acquirer's export takes: a byte
 * order mark at the start, and a semicolon or a tab where the comma should be. A row with nothing
 * in it is skipped, so the first row with anything in it names the columns.
 */
final class Csv {

  /** A field longer than this is not a settlement field. */
  static final int LONGEST_FIELD = 2_000;

  private Csv() {}

  /** A problem with the text itself, with the row it was found on (the header is row 1). */
  static final class Malformed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Malformed(String message) {
      super(message);
    }
  }

  /** The rows of a file under its header. */
  static final class Table {
    private final Map<String, Integer> columns;
    private final List<List<String>> rows;

    Table(Map<String, Integer> columns, List<List<String>> rows) {
      this.columns = Map.copyOf(columns);
      this.rows = List.copyOf(rows);
    }

    int size() {
      return rows.size();
    }

    boolean has(String column) {
      return columns.containsKey(key(column));
    }

    /**
     * The first of the named columns that this row fills in, trimmed; null when none does. A file's
     * row number is {@code row + 2}: rows count from nought and the header is the first line.
     */
    String get(int row, String... names) {
      List<String> cells = rows.get(row);
      for (String name : names) {
        Integer at = columns.get(key(name));
        if (at != null && at < cells.size()) {
          String value = cells.get(at).strip();
          if (!value.isEmpty()) return value;
        }
      }
      return null;
    }
  }

  /**
   * @param maxRows the most data rows a file may have
   * @throws Malformed when the text is not a table: nothing in it, a quote left open, too many rows
   */
  static Table read(String text, int maxRows) {
    String body = text == null ? "" : text;
    if (!body.isEmpty() && body.charAt(0) == '﻿') body = body.substring(1);
    char delimiter = delimiterOf(body);
    List<List<String>> all = rows(body, delimiter, maxRows + 1);
    if (all.isEmpty()) throw new Malformed("The file is empty");
    Map<String, Integer> columns = new HashMap<>();
    List<String> header = all.get(0);
    for (int i = 0; i < header.size(); i++) {
      String name = key(header.get(i));
      if (!name.isEmpty()) columns.putIfAbsent(name, i);
    }
    return new Table(columns, all.subList(1, all.size()));
  }

  private static String key(String column) {
    return column == null ? "" : column.strip().toLowerCase(Locale.ROOT);
  }

  /** The delimiter the header uses: whichever of comma, semicolon and tab it has most of. */
  private static char delimiterOf(String body) {
    int commas = 0;
    int semicolons = 0;
    int tabs = 0;
    boolean quoted = false;
    for (int i = 0; i < body.length(); i++) {
      char ch = body.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
      } else if (!quoted) {
        if (ch == '\n' || ch == '\r') break;
        if (ch == ',') commas++;
        if (ch == ';') semicolons++;
        if (ch == '\t') tabs++;
      }
    }
    if (semicolons > commas && semicolons >= tabs) return ';';
    if (tabs > commas) return '\t';
    return ',';
  }

  private static List<List<String>> rows(String body, char delimiter, int maxRows) {
    List<List<String>> out = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    boolean wasQuoted = false;
    int n = body.length();
    int i = 0;
    while (i < n) {
      char ch = body.charAt(i);
      if (quoted) {
        if (ch == '"' && i + 1 < n && body.charAt(i + 1) == '"') {
          field.append('"');
          i += 2;
          continue;
        }
        if (ch == '"') {
          quoted = false;
        } else {
          append(field, ch, out.size());
        }
        i++;
        continue;
      }
      if (ch == '"' && field.length() == 0 && !wasQuoted) {
        quoted = true;
        wasQuoted = true;
      } else if (ch == delimiter) {
        row.add(field.toString());
        field.setLength(0);
        wasQuoted = false;
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < n && body.charAt(i + 1) == '\n') i++;
        row.add(field.toString());
        field.setLength(0);
        wasQuoted = false;
        keep(out, row, maxRows);
        row = new ArrayList<>();
      } else {
        append(field, ch, out.size());
      }
      i++;
    }
    if (quoted) throw new Malformed("Row " + (out.size() + 1) + ": a quoted field is never closed");
    if (field.length() > 0 || wasQuoted || !row.isEmpty()) {
      row.add(field.toString());
      keep(out, row, maxRows);
    }
    return out;
  }

  private static void append(StringBuilder field, char ch, int rowsSoFar) {
    if (field.length() >= LONGEST_FIELD) {
      throw new Malformed("Row " + (rowsSoFar + 1) + ": a field is longer than any settlement has");
    }
    field.append(ch);
  }

  private static void keep(List<List<String>> out, List<String> row, int maxRows) {
    boolean blank = true;
    for (String cell : row) {
      if (!cell.isBlank()) {
        blank = false;
        break;
      }
    }
    if (blank) return;
    if (out.size() >= maxRows) {
      throw new Malformed("The file has more than " + (maxRows - 1) + " lines");
    }
    out.add(row);
  }
}
