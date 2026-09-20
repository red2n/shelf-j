package com.storeql.einvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An element a writer is building. Absent values add nothing and a group that ends up with nothing
 * in it is not written, so a writer can map every business term without testing each for {@code
 * null} — which is also how a written document keeps PEPPOL-EN16931-R008's rule against empty
 * elements. A group the schema requires even when empty is marked {@link #required()}.
 */
final class XmlNode {

  private static final DateTimeFormatter CII_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private final String name;
  private final String text;
  private final Map<String, String> attributes = new LinkedHashMap<>();
  private final List<XmlNode> children = new ArrayList<>();
  private boolean required;

  private XmlNode(String name, String text) {
    this.name = name;
    this.text = text;
  }

  /** The document element, with the namespace declarations it carries. */
  static XmlNode root(String name, Map<String, String> namespaces) {
    XmlNode root = new XmlNode(name, null);
    root.required = true;
    namespaces.forEach(
        (prefix, uri) -> root.attributes.put(prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix, uri));
    return root;
  }

  /** Adds and returns a child group. */
  XmlNode group(String child) {
    XmlNode node = new XmlNode(child, null);
    children.add(node);
    return node;
  }

  /** Writes this group even when nothing is put in it. */
  XmlNode required() {
    required = true;
    return this;
  }

  /** Adds a text child when the value is present; returns this group. */
  XmlNode leaf(String child, String value) {
    if (value != null && !value.isBlank()) children.add(new XmlNode(child, value.strip()));
    return this;
  }

  /** Adds a text child with one attribute, written only when present; returns this group. */
  XmlNode leaf(String child, String value, String attribute, String attributeValue) {
    if (value == null || value.isBlank()) return this;
    XmlNode node = new XmlNode(child, value.strip());
    if (attributeValue != null && !attributeValue.isBlank()) {
      node.attributes.put(attribute, attributeValue.strip());
    }
    children.add(node);
    return this;
  }

  /**
   * Adds a text child and returns it, so more than one attribute can be set; when the value is
   * absent nothing is added and the node returned is detached.
   */
  XmlNode element(String child, String value) {
    XmlNode node = new XmlNode(child, value == null || value.isBlank() ? null : value.strip());
    if (node.text != null) children.add(node);
    return node;
  }

  XmlNode leaf(String child, BigDecimal value) {
    return value == null ? this : leaf(child, value.toPlainString());
  }

  XmlNode leaf(String child, LocalDate value) {
    return value == null ? this : leaf(child, value.toString());
  }

  /** An amount with its currency. */
  XmlNode amount(String child, BigDecimal value, String currency) {
    return value == null ? this : leaf(child, value.toPlainString(), "currencyID", currency);
  }

  /** A quantity with its unit. */
  XmlNode quantity(String child, BigDecimal value, String unit) {
    return value == null ? this : leaf(child, value.toPlainString(), "unitCode", unit);
  }

  /** A CII date: {@code <udt:DateTimeString format="102">yyyyMMdd</…>} inside a named group. */
  XmlNode ciiDate(String child, String stringElement, LocalDate value) {
    if (value != null) {
      group(child).leaf(stringElement, value.format(CII_DATE), "format", "102");
    }
    return this;
  }

  /** Sets an attribute on this element when the value is present. */
  XmlNode attribute(String attribute, String value) {
    if (value != null && !value.isBlank()) attributes.put(attribute, value.strip());
    return this;
  }

  private boolean isEmpty() {
    if (required || text != null) return false;
    for (XmlNode c : children) {
      if (!c.isEmpty()) return false;
    }
    return true;
  }

  /** The document, as UTF-8 XML text. */
  String toXml() {
    StringBuilder out = new StringBuilder(16_384);
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    write(out, 0);
    return out.toString();
  }

  private void write(StringBuilder out, int depth) {
    out.append("  ".repeat(depth)).append('<').append(name);
    attributes.forEach(
        (k, v) -> out.append(' ').append(k).append("=\"").append(escape(v, true)).append('"'));
    if (text != null) {
      out.append('>').append(escape(text, false)).append("</").append(name).append(">\n");
      return;
    }
    List<XmlNode> written = children.stream().filter(c -> !c.isEmpty()).toList();
    if (written.isEmpty()) {
      out.append("/>\n");
      return;
    }
    out.append(">\n");
    for (XmlNode c : written) c.write(out, depth + 1);
    out.append("  ".repeat(depth)).append("</").append(name).append(">\n");
  }

  /**
   * Escapes markup, and replaces what XML 1.0 cannot carry at all — control characters from a
   * pasted product name — with a space rather than writing a document no parser will read.
   */
  static String escape(String s, boolean attribute) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    s.codePoints()
        .forEach(
            cp -> {
              switch (cp) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append(attribute ? "&quot;" : "\"");
                default -> {
                  boolean legal =
                      cp == 0x9
                          || cp == 0xA
                          || cp == 0xD
                          || (cp >= 0x20 && cp <= 0xD7FF)
                          || (cp >= 0xE000 && cp <= 0xFFFD)
                          || (cp >= 0x10000 && cp <= 0x10FFFF);
                  if (legal) b.appendCodePoint(cp);
                  else b.append(' ');
                }
              }
            });
    return b.toString();
  }
}
