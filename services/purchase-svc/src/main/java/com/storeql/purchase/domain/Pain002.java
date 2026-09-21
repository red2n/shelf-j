package com.storeql.purchase.domain;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The bank's answer to a payment file, ISO 20022 pain.002 (17.12): the customer payment status
 * report, read back so a payment the bank will not make, or a payee it cannot match, is held rather
 * than posted as paid.
 *
 * <p>Each payment is named by the end-to-end id the file gave it. Its status is ISO's — accepted,
 * pending, rejected — with the reason code the bank gives. Since 9 October 2025 the Instant
 * Payments Regulation (EU) 2024/886 has a bank verify the payee's name against the IBAN before a
 * euro transfer, and the EPC Verification of Payee scheme answers MTCH, CMTC (a close match, with
 * the name the bank holds), NMTC or NOAP. Banks return that answer for a bulk file in their own
 * way; this reads it where banks commonly put it in the status report — as the proprietary status
 * reason, with the name the bank holds in the additional information.
 *
 * <p>The report arrives from outside, so it is read defensively: no DTD, no external entity, a size
 * and a transaction limit, and the root must be a pain.002 document.
 */
public final class Pain002 {

  public static final String NAMESPACE_PREFIX = "urn:iso:std:iso:20022:tech:xsd:pain.002.001.";

  /** The most a report may hold, in characters. */
  public static final int MAX_CHARS = 2_000_000;

  public static final int MAX_TRANSACTIONS = 10_000;

  public static final String REJECTED = "RJCT";
  public static final String MATCH = "MTCH";
  public static final String CLOSE_MATCH = "CMTC";
  public static final String NO_MATCH = "NMTC";
  public static final String NOT_APPLICABLE = "NOAP";

  static final Set<String> STATUSES =
      Set.of("RCVD", "ACTC", "ACCP", "ACSP", "ACSC", "ACCC", "ACWC", "PART", "PDNG", REJECTED);
  static final Set<String> PAYEE_MATCHES = Set.of(MATCH, CLOSE_MATCH, NO_MATCH, NOT_APPLICABLE);

  private Pain002() {}

  /**
   * One payment's status.
   *
   * @param reasonCode the ISO reason code, e.g. AC04 for a closed account, or null
   * @param payeeMatch MTCH, CMTC, NMTC, NOAP, or null when the bank gave none
   * @param matchedName on a close match, the name the bank holds for the account
   */
  public record TransactionStatus(
      String endToEndId, String status, String reasonCode, String payeeMatch, String matchedName) {

    /** Held: rejected, or a payee the bank could not match, or matched only closely. */
    public boolean held() {
      return REJECTED.equals(status)
          || NO_MATCH.equals(payeeMatch)
          || CLOSE_MATCH.equals(payeeMatch);
    }

    /** A close match on a payment not rejected is a person's call; everything else held is not. */
    public boolean releasable() {
      return !REJECTED.equals(status) && CLOSE_MATCH.equals(payeeMatch);
    }
  }

  /**
   * The report.
   *
   * @param originalMessageId the message id of the file it answers
   * @param groupStatus the status of the whole file, when the bank gave one
   */
  public record Report(
      String messageId,
      String originalMessageId,
      String groupStatus,
      String groupReasonCode,
      List<TransactionStatus> transactions) {

    /**
     * A payment's status: its own, or the whole file's when the bank reported only that.
     *
     * @return null when the report says nothing about the payment
     */
    public TransactionStatus statusOf(String endToEndId) {
      for (TransactionStatus t : transactions) {
        if (t.endToEndId().equals(endToEndId)) return t;
      }
      return groupStatus == null
          ? null
          : new TransactionStatus(endToEndId, groupStatus, groupReasonCode, null, null);
    }
  }

  /**
   * Reads a report.
   *
   * @throws IllegalArgumentException when it is not a well-formed pain.002 this can trust
   */
  public static Report read(String xml) {
    if (xml == null || xml.isBlank()) {
      throw new IllegalArgumentException("the status report is empty");
    }
    if (xml.length() > MAX_CHARS) {
      throw new IllegalArgumentException("the status report is larger than 2 MB");
    }
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    Parse p = new Parse();
    try {
      XMLStreamReader r = factory.createXMLStreamReader(new StringReader(xml));
      try {
        while (r.hasNext()) {
          int event = r.next();
          if (event == XMLStreamConstants.DTD) {
            throw new IllegalArgumentException("a status report may not declare a DTD");
          } else if (event == XMLStreamConstants.START_ELEMENT) {
            p.start(r);
          } else if (event == XMLStreamConstants.END_ELEMENT) {
            p.end();
          }
        }
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("the status report is not well-formed XML", e);
    }
    return p.report();
  }

  /** The state of one read. */
  private static final class Parse {
    private final Deque<String> path = new ArrayDeque<>();
    private final List<TransactionStatus> transactions = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private String messageId;
    private String originalMessageId;
    private String groupStatus;
    private String groupReason;
    private boolean inTransaction;
    private String e2e;
    private String status;
    private String reason;
    private String match;
    private String info;

    void start(XMLStreamReader r) throws XMLStreamException {
      String name = r.getLocalName();
      if (path.isEmpty()
          && (!"Document".equals(name)
              || r.getNamespaceURI() == null
              || !r.getNamespaceURI().startsWith(NAMESPACE_PREFIX))) {
        throw new IllegalArgumentException("this is not a pain.002 customer payment status report");
      }
      String parent = path.peek();
      switch (name) {
        case "MsgId" -> {
          if ("GrpHdr".equals(parent)) messageId = r.getElementText().trim();
          else path.push(name);
        }
        case "OrgnlMsgId" -> {
          if ("OrgnlGrpInfAndSts".equals(parent)) originalMessageId = r.getElementText().trim();
          else path.push(name);
        }
        case "GrpSts" -> groupStatus = r.getElementText().trim();
        case "OrgnlEndToEndId" -> e2e = r.getElementText().trim();
        case "TxSts" -> status = r.getElementText().trim();
        case "Cd" -> {
          if ("Rsn".equals(parent)) {
            String code = r.getElementText().trim();
            if (inTransaction) {
              if (reason == null) reason = code;
            } else if (groupReason == null) {
              groupReason = code;
            }
          } else {
            path.push(name);
          }
        }
        case "Prtry" -> {
          if ("Rsn".equals(parent) && inTransaction) {
            String code = r.getElementText().trim();
            if (PAYEE_MATCHES.contains(code)) match = code;
          } else {
            path.push(name);
          }
        }
        case "AddtlInf" -> {
          if (inTransaction && info == null) info = r.getElementText().trim();
          else path.push(name);
        }
        case "TxInfAndSts" -> {
          if (transactions.size() >= MAX_TRANSACTIONS) {
            throw new IllegalArgumentException("a status report holds at most 10,000 payments");
          }
          inTransaction = true;
          e2e = null;
          status = null;
          reason = null;
          match = null;
          info = null;
          path.push(name);
        }
        default -> path.push(name);
      }
    }

    void end() {
      String closed = path.pop();
      if (!"TxInfAndSts".equals(closed)) return;
      inTransaction = false;
      if (e2e == null || e2e.isEmpty() || e2e.length() > 35) {
        throw new IllegalArgumentException("a payment in the status report has no end-to-end id");
      }
      if (status == null || !STATUSES.contains(status)) {
        throw new IllegalArgumentException("payment " + e2e + " has no status this knows");
      }
      if (!seen.add(e2e)) {
        throw new IllegalArgumentException("payment " + e2e + " is reported twice");
      }
      String name = CLOSE_MATCH.equals(match) && info != null ? cut(info) : null;
      transactions.add(new TransactionStatus(e2e, status, reason, match, name));
    }

    Report report() {
      if (messageId == null || messageId.isEmpty() || messageId.length() > 35) {
        throw new IllegalArgumentException("the status report has no message id");
      }
      if (originalMessageId == null || originalMessageId.isEmpty()) {
        throw new IllegalArgumentException("the status report does not say which file it answers");
      }
      if (groupStatus != null && !STATUSES.contains(groupStatus)) {
        throw new IllegalArgumentException("the file's status is not one this knows");
      }
      if (transactions.isEmpty() && groupStatus == null) {
        throw new IllegalArgumentException("the status report states no status");
      }
      return new Report(messageId, originalMessageId, groupStatus, groupReason, transactions);
    }

    private static String cut(String s) {
      return s.length() > 140 ? s.substring(0, 140) : s;
    }
  }
}
