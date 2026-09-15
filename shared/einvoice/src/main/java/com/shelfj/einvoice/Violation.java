package com.shelfj.einvoice;

/**
 * One business rule an invoice breaks, named as the rule set that states it names it ({@code
 * BR-CO-15}, {@code PEPPOL-EN16931-R003}) so a supplier can look it up.
 *
 * @param rule the rule's identifier
 * @param severity FATAL means the document is not a compliant invoice; WARNING means it is, but
 *     something in it should not be there
 * @param message what the rule requires, and where this invoice departs from it
 */
public record Violation(String rule, Severity severity, String message) {

  /** How far a broken rule takes the document from compliance. */
  public enum Severity {
    FATAL,
    WARNING
  }

  static Violation fatal(String rule, String message) {
    return new Violation(rule, Severity.FATAL, message);
  }

  static Violation warning(String rule, String message) {
    return new Violation(rule, Severity.WARNING, message);
  }

  /** Whether the document fails on this rule. */
  public boolean isFatal() {
    return severity == Severity.FATAL;
  }
}
