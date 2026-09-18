package com.shelfj.gs1;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads whatever a scanner sent into one {@link Gs1Scan}.
 *
 * <p>Three forms reach a till and they look nothing like each other:
 *
 * <ul>
 *   <li>digits alone, from the linear barcode that has been on packets for fifty years;
 *   <li>an element string, from GS1 DataMatrix, GS1-128 or GS1 QR — {@code
 *       010950600013435210ABC123} with the AIs run together, or the same thing with brackets when a
 *       human wrote it down;
 *   <li>a Digital Link URI, from the QR code GS1 Sunrise 2027 is about — {@code
 *       https://id.gs1.org/01/09506000134352/10/ABC123?17=261231}.
 * </ul>
 *
 * <p><b>Nothing here estimates.</b> A code this cannot read exactly comes back empty rather than
 * half-read, for the same reason the labelling-scale reader refuses a label whose check digit does
 * not match: a partial reading of a food label is a wrong batch or a wrong expiry, and both are
 * worse than no reading at all. The caller then treats the scan as the opaque string it already
 * handles.
 */
public final class Gs1Reader {

  private Gs1Reader() {}

  /**
   * The GS1 separator, ASCII 29. A variable-length AI's data runs to the next one of these or to
   * the end of the string.
   */
  private static final char GROUP_SEPARATOR = 0x1D;

  /**
   * What a scanner may send in place of ASCII 29.
   *
   * <p>Keyboard-wedge scanners cannot type a control character, so they are configured to send
   * something else. These are the conventional stand-ins. They count as separators only
   * <em>inside</em> an element string, never in a Digital Link, where they would be ordinary
   * characters.
   */
  private static final char[] SEPARATOR_ALIASES = {GROUP_SEPARATOR, 0x1E, 0x1F};

  /** The AI a Digital Link's primary key must be for a retail scan. */
  private static final String GTIN_AI = "01";

  /**
   * Reads a scanned string.
   *
   * @return the reading, or empty when the string is not something this understands — which is an
   *     ordinary outcome and not a fault: loyalty cards, coupons and staff badges all scan
   */
  public static Optional<Gs1Scan> read(String scanned) {
    if (scanned == null) return Optional.empty();
    String raw = scanned.strip();
    if (raw.isEmpty()) return Optional.empty();

    if (looksLikeUri(raw)) return digitalLink(raw);

    String plain = Gtin.normalise(raw);
    if (plain != null) {
      return Optional.of(new Gs1Scan(raw, Gs1Scan.Format.PLAIN_GTIN, plain, Map.of()));
    }
    return elementString(raw);
  }

  private static boolean looksLikeUri(String raw) {
    String lower = raw.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  // ── a Digital Link URI ──────────────────────────────────────────────────────

  /**
   * {@code https://example.com/01/09506000134352/10/ABC123?17=261231&3103=001250}.
   *
   * <p>The domain is deliberately not checked. GS1 Digital Link is resolver-agnostic by design: a
   * brand puts its <em>own</em> domain on the pack and the path carries the identity, so refusing
   * anything that is not id.gs1.org would refuse most real packaging. What matters is that the
   * path's pairs are AIs and that the primary one is a GTIN.
   */
  private static Optional<Gs1Scan> digitalLink(String raw) {
    URI uri;
    try {
      uri = URI.create(raw);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String path = uri.getRawPath();
    if (path == null) return Optional.empty();

    List<String> segments = List.of(path.split("/"));
    // A Digital Link may carry any path prefix before its keys — /gtin/01/... on one brand's site
    // and
    // /products/01/... on another's — so the keys are found rather than assumed to start at the
    // root.
    // Breaking at the first segment that is not an AI would read only the URIs whose prefix is
    // empty,
    // which is the minority of real packaging.
    int start = indexOfPrimaryKey(segments);
    if (start < 0) return Optional.empty();

    Map<String, String> elements = new LinkedHashMap<>();
    // From the primary key on, the path is pairs: /01/<gtin>/10/<batch>. A trailing segment that is
    // not an AI is a resolver's own route and ends the identity rather than spoiling it.
    for (int i = start; i + 1 < segments.size(); i += 2) {
      if (ApplicationIdentifier.of(segments.get(i)) == null) break;
      elements.put(segments.get(i), decode(segments.get(i + 1)));
    }
    if (!elements.containsKey(GTIN_AI)) return Optional.empty();

    // The query carries the rest — 17=261231&3103=001250 — and an unknown one here is ignorable
    // rather than fatal: unlike an element string, each pair is delimited, so skipping one loses
    // nothing and misplaces nothing.
    String query = uri.getRawQuery();
    if (query != null) {
      for (String pair : query.split("&")) {
        int eq = pair.indexOf('=');
        if (eq <= 0) continue;
        String ai = decode(pair.substring(0, eq));
        if (ApplicationIdentifier.of(ai) != null) {
          elements.putIfAbsent(ai, decode(pair.substring(eq + 1)));
        }
      }
    }

    String gtin = Gtin.normalise(elements.get(GTIN_AI));
    if (gtin == null) return Optional.empty();
    elements.put(GTIN_AI, gtin);
    return Optional.of(new Gs1Scan(raw, Gs1Scan.Format.DIGITAL_LINK, gtin, elements));
  }

  /**
   * Where the key pairs begin: the first {@code /01/} with something after it.
   *
   * <p>GTIN only. A retail till is being asked "what item is this?", and a Digital Link whose
   * primary key is a party or a location (AI 417, 414) does not answer that question — treating one
   * as a reading would hand the caller an identifier it would then look up as a product and not
   * find.
   */
  private static int indexOfPrimaryKey(List<String> segments) {
    for (int i = 0; i + 1 < segments.size(); i++) {
      if (GTIN_AI.equals(segments.get(i)) && !segments.get(i + 1).isEmpty()) return i;
    }
    return -1;
  }

  private static String decode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  // ── an element string ───────────────────────────────────────────────────────

  /**
   * {@code 010950600013435210ABC123}, or the human form {@code (01)09506000134352(10)ABC123}.
   *
   * <p>A fixed-length AI's data ends where the standard says; a variable-length one's runs to a
   * separator or to the end of the string. That is the whole risk of this format: an unknown AI is
   * refused rather than skipped, because skipping it means guessing where its data stopped, and a
   * wrong guess reads the remainder of a food label as different fields entirely — quietly, with
   * the scan still appearing to work.
   */
  private static Optional<Gs1Scan> elementString(String raw) {
    String data = raw.indexOf('(') >= 0 ? withoutBrackets(raw) : raw;
    if (data == null || data.isEmpty()) return Optional.empty();

    Map<String, String> elements = new LinkedHashMap<>();
    int at = 0;
    while (at < data.length()) {
      if (isSeparator(data.charAt(at))) {
        at++;
        continue;
      }
      ApplicationIdentifier.Spec spec = ApplicationIdentifier.beginningAt(data, at);
      if (spec == null) return Optional.empty();
      at += spec.ai().length();
      String value = spec.fixed() ? fixedValue(data, at, spec) : variableValue(data, at, spec);
      if (value == null) return Optional.empty();
      at += value.length();
      // The same AI twice is a misread, not two facts: a code cannot name two batches.
      if (elements.putIfAbsent(spec.ai(), value) != null) return Optional.empty();
    }
    if (elements.isEmpty()) return Optional.empty();

    boolean carriesGtin = elements.containsKey(GTIN_AI);
    String gtin = carriesGtin ? Gtin.normalise(elements.get(GTIN_AI)) : null;
    // A GTIN present but failing its own check digit is a misread, not a code about something else,
    // so
    // the whole reading fails rather than coming back as a scan that identifies no item.
    if (carriesGtin && gtin == null) return Optional.empty();
    if (gtin != null) elements.put(GTIN_AI, gtin);
    return Optional.of(new Gs1Scan(raw, Gs1Scan.Format.ELEMENT_STRING, gtin, elements));
  }

  private static String fixedValue(String data, int at, ApplicationIdentifier.Spec spec) {
    if (at + spec.length() > data.length()) return null;
    String value = data.substring(at, at + spec.length());
    // Every fixed-length AI this platform reads is all digits, so anything else — a separator most
    // of
    // all — means the length was misread, and everything after it would shift.
    return Gtin.isDigits(value) ? value : null;
  }

  private static String variableValue(String data, int at, ApplicationIdentifier.Spec spec) {
    int end = at;
    while (end < data.length() && !isSeparator(data.charAt(end))) end++;
    // Where no separator arrived the field runs to the end — but never past what the standard
    // allows,
    // so a 25-character "batch" is a misread rather than a batch.
    if (end - at > spec.length()) return null;
    return end == at ? null : data.substring(at, end);
  }

  private static boolean isSeparator(char c) {
    for (char alias : SEPARATOR_ALIASES) {
      if (c == alias) return true;
    }
    return false;
  }

  /**
   * {@code (01)09506000134352(10)ABC123} as a person writes it, turned into the machine form.
   *
   * <p>Accepted because it is what appears in a supplier's e-mail, a specification and a support
   * ticket, and because pasting one should find the same item the scan does. Every AI after the
   * first gets a separator in front of it, which is what lets a variable-length field written this
   * way end without one.
   */
  private static String withoutBrackets(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    int at = 0;
    while (at < raw.length()) {
      char c = raw.charAt(at);
      if (c == '(') {
        int close = raw.indexOf(')', at);
        if (close < 0) return null;
        String ai = raw.substring(at + 1, close);
        if (!Gtin.isDigits(ai)) return null;
        if (out.length() > 0) out.append(GROUP_SEPARATOR);
        out.append(ai);
        at = close + 1;
      } else {
        out.append(c);
        at++;
      }
    }
    return out.toString();
  }
}
