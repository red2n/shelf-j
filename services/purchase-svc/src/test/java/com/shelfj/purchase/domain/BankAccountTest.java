package com.shelfj.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BankAccountTest {

  @Test
  @DisplayName("A sort code is six digits however it is keyed; anything else is refused")
  void sortCodes() {
    assertThat(BankAccount.sortCode("12-34-56"), is("123456"));
    assertThat(BankAccount.sortCode("12 34 56"), is("123456"));
    assertThat(BankAccount.sortCode("123456"), is("123456"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.sortCode("12345"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.sortCode("1234567"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.sortCode("12-3A-56"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.sortCode(null));
  }

  @Test
  @DisplayName("A UK account number is eight digits")
  void accountNumbers() {
    assertThat(BankAccount.accountNumber("3141 5926"), is("31415926"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.accountNumber("1234567"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.accountNumber("123456789"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.accountNumber("1234567O"));
  }

  @Test
  @DisplayName("A valid IBAN passes its mod-97 check in electronic form; one wrong digit fails it")
  void ibans() {
    // The published examples: a UK and a German IBAN.
    assertThat(BankAccount.iban("GB82 WEST 1234 5698 7654 32"), is("GB82WEST12345698765432"));
    assertThat(BankAccount.iban("de89370400440532013000"), is("DE89370400440532013000"));
    IllegalArgumentException typo =
        assertThrows(
            IllegalArgumentException.class, () -> BankAccount.iban("GB82WEST12345698765433"));
    assertThat(typo.getMessage().contains("check digits"), is(true));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.iban("GB82"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.iban("82GBWEST12345698765432"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.iban(""));
  }

  @Test
  @DisplayName("A BIC is eight or eleven characters")
  void bics() {
    assertThat(BankAccount.bic("deutdeff"), is("DEUTDEFF"));
    assertThat(BankAccount.bic("DEUTDEFF500"), is("DEUTDEFF500"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.bic("DEUTDE"));
    assertThrows(IllegalArgumentException.class, () -> BankAccount.bic("DEUTDEFF50"));
  }

  @Test
  @DisplayName("Only the last four characters are ever shown back")
  void masking() {
    assertThat(BankAccount.masked("31415926"), is("****5926"));
    assertThat(BankAccount.masked("GB82WEST12345698765432"), is("****5432"));
    assertThat(BankAccount.masked("12"), is("****12"));
    assertThat(BankAccount.masked(null), is(nullValue()));
    assertThat(BankAccount.masked(" "), is(nullValue()));
  }

  @Test
  @DisplayName("A full set of details validates together; a half set says what is missing")
  void detailsTogether() {
    var uk = BankAccount.details(" Acme Ltd ", "12-34-56", "3141 5926", null, null);
    assertThat(uk.accountName(), is("Acme Ltd"));
    assertThat(uk.sortCode(), is("123456"));
    assertThat(uk.payable(), is(true));
    var intl =
        BankAccount.details("Acme GmbH", null, null, "de89 3704 0044 0532 0130 00", "deutdeff");
    assertThat(intl.iban(), is("DE89370400440532013000"));
    assertThat(intl.bic(), is("DEUTDEFF"));
    assertThat(intl.payable(), is(true));
    assertThat(BankAccount.details(" ", "", null, null, null), is(BankAccount.Details.NONE));
    assertThat(BankAccount.Details.NONE.empty(), is(true));
    assertThat(BankAccount.Details.NONE.payable(), is(false));

    assertThat(
        message(() -> BankAccount.details("Acme", "123456", null, null, null))
            .contains("go together"),
        is(true));
    assertThat(
        message(() -> BankAccount.details("Acme", null, null, null, "DEUTDEFF"))
            .contains("goes with an IBAN"),
        is(true));
    assertThat(
        message(() -> BankAccount.details("Acme", null, null, null, null))
            .contains("sort code and account number, or an IBAN"),
        is(true));
    assertThat(
        message(() -> BankAccount.details(null, "123456", "31415926", null, null))
            .contains("holder's name"),
        is(true));
    assertThat(
        message(() -> BankAccount.details("x".repeat(141), "123456", "31415926", null, null))
            .contains("140"),
        is(true));
    assertThat(
        message(() -> BankAccount.details("Acme", null, null, "GB82WEST12345698765433", null))
            .contains("check digits"),
        is(true));
  }

  private static String message(org.junit.jupiter.api.function.Executable call) {
    return assertThrows(IllegalArgumentException.class, call).getMessage();
  }
}
