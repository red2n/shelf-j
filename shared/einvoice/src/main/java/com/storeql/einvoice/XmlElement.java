package com.storeql.einvoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One element of a document {@link SafeXml} has read: its namespace, local name, attributes by
 * local name, text and children. Readers navigate it by local name, which is unambiguous in both
 * UBL and CII once the root's namespace has been checked.
 */
final class XmlElement {

  private final String namespace;
  private final String name;
  private final Map<String, String> attributes;
  private String text = "";
  private final List<XmlElement> children = new ArrayList<>();

  XmlElement(String namespace, String name, Map<String, String> attributes) {
    this.namespace = namespace == null ? "" : namespace;
    this.name = name;
    this.attributes = Map.copyOf(attributes);
  }

  String namespace() {
    return namespace;
  }

  String name() {
    return name;
  }

  /** An attribute's value by local name, or {@code null}; blank reads as absent. */
  String attribute(String local) {
    String value = attributes.get(local);
    return value == null || value.isBlank() ? null : value.strip();
  }

  /** The element's own text, trimmed, or {@code null} when it has none. */
  String value() {
    String v = text.strip();
    return v.isEmpty() ? null : v;
  }

  List<XmlElement> children() {
    return Collections.unmodifiableList(children);
  }

  /** The first child with a local name, or {@code null}. */
  XmlElement child(String local) {
    for (XmlElement c : children) {
      if (c.name.equals(local)) return c;
    }
    return null;
  }

  /** Every child with a local name, in document order. */
  List<XmlElement> all(String local) {
    List<XmlElement> out = new ArrayList<>();
    for (XmlElement c : children) {
      if (c.name.equals(local)) out.add(c);
    }
    return out;
  }

  /** The element at a path of local names below this one, or {@code null}. */
  XmlElement at(String... path) {
    XmlElement e = this;
    for (String step : path) {
      e = e.child(step);
      if (e == null) return null;
    }
    return e;
  }

  /** The text at a path below this one, or {@code null}. */
  String value(String... path) {
    XmlElement e = at(path);
    return e == null ? null : e.value();
  }

  /** Whether the element has neither children nor text: what PEPPOL-EN16931-R008 forbids. */
  boolean isEmptyLeaf() {
    return children.isEmpty() && text.isBlank();
  }

  /**
   * Adds text. Whitespace between child elements is layout, not content, and is dropped, so a
   * parent of many children does not rebuild a growing string for each.
   */
  void append(String characters) {
    if (!children.isEmpty() && characters.isBlank()) return;
    text = text.isEmpty() ? characters : text + characters;
  }

  void add(XmlElement child) {
    children.add(child);
  }
}
