package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.PaymentProposal.Document;
import com.storeql.purchase.domain.PaymentProposal.Payee;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentProposalTest {

  private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
  private static final UUID ACME = Ids.newId();
  private static final UUID BOLT = Ids.newId();
  private static final UUID CRATE = Ids.newId();

  private static Document invoice(UUID supplier, String ref, String amount, String due) {
    return new Document(
        PaymentProposal.INVOICE,
        Ids.newId(),
        supplier,
        null,
        ref,
        LocalDate.parse(due).minusDays(30),
        LocalDate.parse(due),
        new BigDecimal(amount));
  }

  private static Document credit(UUID supplier, String ref, String amount) {
    return new Document(
        PaymentProposal.CREDIT_NOTE,
        Ids.newId(),
        supplier,
        null,
        ref,
        LocalDate.parse("2026-09-01"),
        null,
        new BigDecimal(amount));
  }

  private static Payee payee(UUID id, String name, boolean bank, Instant changed) {
    return new Payee(id, name, bank, changed);
  }

  @Test
  @DisplayName("A supplier is paid its invoices less its credit notes, invoices first by due date")
  void netsCreditsAgainstInvoices() {
    var result =
        PaymentProposal.build(
            List.of(
                invoice(ACME, "INV-2", "80.00", "2026-09-10"),
                credit(ACME, "CN-1", "30.00"),
                invoice(ACME, "INV-1", "150.00", "2026-09-01")),
            Map.of(ACME, payee(ACME, "Acme", true, null)),
            NOW);
    assertThat(result.payments(), hasSize(1));
    var acme = result.payments().get(0);
    assertThat(acme.net(), comparesEqualTo(new BigDecimal("200.00")));
    assertThat(
        acme.documents().stream().map(Document::reference).toList(),
        contains("INV-1", "INV-2", "CN-1"));
    assertThat(acme.warnings(), is(empty()));
    assertThat(result.excluded(), is(empty()));
    assertThat(result.total(), comparesEqualTo(new BigDecimal("200.00")));
  }

  @Test
  @DisplayName("No bank details, or credits covering the invoices: excluded, with the reason")
  void exclusions() {
    var result =
        PaymentProposal.build(
            List.of(
                invoice(ACME, "INV-A", "100.00", "2026-09-01"),
                invoice(BOLT, "INV-B", "50.00", "2026-09-01"),
                credit(BOLT, "CN-B", "50.00"),
                invoice(CRATE, "INV-C", "70.00", "2026-09-01")),
            Map.of(
                ACME, payee(ACME, "Acme", false, null),
                BOLT, payee(BOLT, "Bolt", true, null),
                CRATE, payee(CRATE, "Crate", true, null)),
            NOW);
    assertThat(result.payments().stream().map(p -> p.name()).toList(), contains("Crate"));
    assertThat(result.excluded(), hasSize(2));
    assertThat(result.excluded().get(0).name(), is("Acme"));
    assertThat(result.excluded().get(0).reason(), is(PaymentProposal.NO_BANK_DETAILS));
    assertThat(result.excluded().get(1).reason(), is(PaymentProposal.NET_NOT_POSITIVE));
    assertThat(result.excluded().get(1).net(), comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("Credit notes alone are left for the run that next pays the supplier")
  void creditsWithoutInvoicesAreLeftAlone() {
    var result =
        PaymentProposal.build(
            List.of(credit(ACME, "CN-1", "20.00")),
            Map.of(ACME, payee(ACME, "Acme", true, null)),
            NOW);
    assertThat(result.payments(), is(empty()));
    assertThat(result.excluded(), is(empty()));
  }

  @Test
  @DisplayName("Bank details changed within fourteen days: paid, but flagged for the approver")
  void recentChangeIsFlagged() {
    Instant yesterday = NOW.minus(Duration.ofDays(1));
    Instant longAgo = NOW.minus(Duration.ofDays(15));
    var result =
        PaymentProposal.build(
            List.of(
                invoice(ACME, "INV-A", "10.00", "2026-09-01"),
                invoice(BOLT, "INV-B", "10.00", "2026-09-01")),
            Map.of(
                ACME,
                payee(ACME, "Acme", true, yesterday),
                BOLT,
                payee(BOLT, "Bolt", true, longAgo)),
            NOW);
    assertThat(
        result.payments().get(0).warnings(),
        contains(PaymentProposal.BANK_DETAILS_CHANGED_RECENTLY));
    assertThat(result.payments().get(1).warnings(), is(empty()));
  }

  @Test
  @DisplayName("A supplier the run knows nothing about is not paid")
  void unknownSupplierIsExcluded() {
    var result =
        PaymentProposal.build(
            List.of(invoice(ACME, "INV-A", "10.00", "2026-09-01")), Map.of(), NOW);
    assertThat(result.payments(), is(empty()));
    assertThat(result.excluded().get(0).reason(), is(PaymentProposal.NO_BANK_DETAILS));
  }

  @Test
  @DisplayName("Nothing due: an empty proposal, not an error here")
  void nothingDue() {
    var result = PaymentProposal.build(List.of(), Map.of(), NOW);
    assertThat(result.payments(), is(empty()));
    assertThat(result.total(), comparesEqualTo(BigDecimal.ZERO));
  }
}
