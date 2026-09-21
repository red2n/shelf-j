package com.storeql.payment.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The file reader takes what acquirers really export — a byte order mark, CRLF, semicolons, quoted
 * commas and line breaks — and refuses what is not a table rather than reading it wrong.
 */
class CsvTest {

  @Test
  void quotedCommasQuotesAndLineBreaksStayInsideTheirField() {
    Csv.Table t =
        Csv.read(
            "﻿type,reference,note\r\n"
                + "SALE,\"A,1\",\"said \"\"paid\"\"\"\r\n"
                + "REFUND,B2,\"two\nlines\"\r\n",
            10);

    assertEquals(2, t.size());
    assertEquals("A,1", t.get(0, "reference"));
    assertEquals("said \"paid\"", t.get(0, "note"));
    assertEquals("two\nlines", t.get(1, "note"));
  }

  @Test
  void theHeaderIsReadWhateverItsCaseAndTheFirstNamedColumnThatIsFilledWins() {
    Csv.Table t = Csv.read("Psp Reference , Merchant Reference\n,ORDER-9\n", 10);

    assertTrue(t.has("psp reference"));
    assertFalse(t.has("reference"));
    assertEquals("ORDER-9", t.get(0, "psp reference", "merchant reference"));
    assertNull(t.get(0, "psp reference"));
    assertNull(t.get(0, "no such column"));
  }

  @Test
  void aSemicolonOrATabWhereTheCommaShouldBeIsReadAsOne() {
    assertEquals("12.50", Csv.read("type;gross\nSALE;12.50\n", 10).get(0, "gross"));
    assertEquals("12.50", Csv.read("type\tgross\nSALE\t12.50", 10).get(0, "gross"));
    // A semicolon inside a quoted header cell does not make the file semicolon-separated.
    assertEquals("x", Csv.read("\"a;b;c\",d\n1,x\n", 10).get(0, "d"));
  }

  @Test
  void blankRowsAreSkippedAndAShortRowHasNothingInItsMissingCells() {
    Csv.Table t = Csv.read("type,gross,fee\n\n , ,\nSALE,1.00\n\n", 10);

    assertEquals(1, t.size());
    assertNull(t.get(0, "fee"));
  }

  @Test
  void whatIsNotATableIsRefusedAndSaysWhy() {
    assertEquals("The file is empty", message(""));
    assertEquals("The file is empty", message(null));
    assertEquals("The file is empty", message(" , ,\n\n"));
    assertTrue(message("type,note\nSALE,\"never closed\n").contains("never closed"));
    assertTrue(message("type\n1\n2\n3\n", 2).contains("more than 2 lines"));
    assertTrue(message("type\n" + "x".repeat(Csv.LONGEST_FIELD + 1)).contains("longer than"));
  }

  @Test
  void exactlyTheMostLinesAllowedIsAccepted() {
    assertEquals(2, Csv.read("type\n1\n2\n", 2).size());
  }

  private static String message(String text) {
    return message(text, 10);
  }

  private static String message(String text, int maxRows) {
    return assertThrows(Csv.Malformed.class, () -> Csv.read(text, maxRows)).getMessage();
  }
}
