package com.shelfj.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BankFileTest {

  @Test
  @DisplayName("One row per payment under the header, CRLF-terminated, amounts as plain decimals")
  void writesOneRowPerPayment() {
    String csv =
        BankFile.csv(
            List.of(
                new BankFile.Payment(
                    "Acme Ltd",
                    "123456",
                    "31415926",
                    null,
                    null,
                    new BigDecimal("1250.50"),
                    "GBP",
                    "PAY260913-3F9A1C"),
                new BankFile.Payment(
                    "Muster GmbH",
                    null,
                    null,
                    "DE89370400440532013000",
                    "DEUTDEFF",
                    new BigDecimal("1E+2"),
                    "GBP",
                    "PAY260913-3F9A1C")));
    String[] rows = csv.split("\r\n");
    assertThat(rows.length, is(3));
    assertThat(rows[0], is(BankFile.HEADER));
    assertThat(rows[1], is("Acme Ltd,123456,31415926,,,1250.50,GBP,PAY260913-3F9A1C"));
    assertThat(
        rows[2], is("Muster GmbH,,,DE89370400440532013000,DEUTDEFF,100,GBP,PAY260913-3F9A1C"));
    assertThat(csv.endsWith("\r\n"), is(true));
    assertThat(BankFile.csv(List.of()), is(BankFile.HEADER + "\r\n"));
  }

  @Test
  @DisplayName("A payee name cannot inject a formula, a column or a row")
  void cellsAreGuarded() {
    assertThat(BankFile.cell("=HYPERLINK(\"http://x\")"), is("\"'=HYPERLINK(\"\"http://x\"\")\""));
    assertThat(BankFile.cell("+44 Ltd"), is("'+44 Ltd"));
    assertThat(BankFile.cell("-1 Ltd"), is("'-1 Ltd"));
    assertThat(BankFile.cell("@SUM(A1)"), is("'@SUM(A1)"));
    assertThat(BankFile.cell("Smith, Jones & Co"), is("\"Smith, Jones & Co\""));
    assertThat(BankFile.cell("Line\r\nBreak"), is("Line  Break"));
    assertThat(BankFile.cell(null), is(""));
    assertThat(BankFile.fileName("PAY260913-3F9A1C"), is("pay260913-3f9a1c.csv"));
    assertThat(BankFile.fileName("../../etc/passwd"), is("etcpasswd.csv"));
  }
}
