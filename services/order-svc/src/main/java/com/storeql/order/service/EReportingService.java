package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.domain.EInvoiceTransports;
import com.storeql.order.domain.EInvoiceTransports.Settings;
import com.storeql.order.domain.EReporting;
import com.storeql.order.domain.EReporting.Content;
import com.storeql.order.domain.EReporting.Submission;
import com.storeql.order.einvoice.EInvoiceTransport;
import com.storeql.order.einvoice.EInvoiceTransport.Dispatch;
import com.storeql.order.einvoice.EInvoiceTransport.Outbound;
import com.storeql.order.einvoice.EInvoiceTransport.Outcome;
import com.storeql.order.einvoice.Secrets;
import com.storeql.order.einvoice.Transports;
import com.storeql.order.repo.EInvoiceTransportRepository;
import com.storeql.order.repo.EReportingRepository;
import com.storeql.service.Jurisdictions;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * E-reporting: transmitting the transactions an invoice does not cover (18.9, second limb).
 *
 * <p>Four judgements shape this service.
 *
 * <p><b>The calendar is tenant-svc's.</b> It derives the periods from a frequency and an offset
 * (07.14) and holds what a business filed. This service is told a period and reports it; it does
 * not keep a second calendar, because two calendars eventually disagree and nobody can tell which
 * is right. It does refuse a period that has not ended, and one longer than a month, so a mistyped
 * date cannot sweep a year of sales into one filing that looks complete.
 *
 * <p><b>Only a network that carries reports may be sent one.</b> E-reporting is a French duty
 * transmitted by a French partner platform; a Peppol access point does not take it, and pretending
 * otherwise would leave a business believing it had reported.
 *
 * <p><b>An empty period is still reported.</b> Silence is indistinguishable from a platform that
 * stopped working, so a fortnight with no in-scope sales transmits a <em>néant</em> rather than
 * nothing.
 *
 * <p><b>A submission is never edited.</b> A correction is a new submission that supersedes its
 * predecessor, both on the record — the same rule as an invoice and a statutory filing.
 */
@ApplicationScoped
public class EReportingService {

  @Inject EReportingRepository repo;
  @Inject EInvoiceTransportRepository transportRepo;
  @Inject Transports transports;
  @Inject Secrets secrets;
  @Inject TenantProfiles profiles;
  @Inject Jurisdictions jurisdictions;

  /** What a period would report, before anything is sent. */
  public record Preview(
      Content content,
      List<String> currencies,
      int transactionCount,
      java.math.BigDecimal netTotal,
      java.math.BigDecimal vatTotal,
      Submission standing) {}

  /**
   * What a period contains, for the business to look at before it transmits.
   *
   * @param returnCode {@link EReporting#TRANSACTIONS} or {@link EReporting#PAYMENTS}
   * @param currency the currency to report; the business's own when none is given
   */
  public Preview preview(
      UUID tenantId, String returnCode, LocalDate from, LocalDate to, String currency) {
    String code = requireReturn(returnCode);
    requirePeriod(from, to);
    String ccy =
        currency == null || currency.isBlank()
            ? tenantCurrency(tenantId)
            : currency.strip().toUpperCase(Locale.ROOT);
    Content content = content(tenantId, code, from, to, ccy);
    return new Preview(
        content,
        repo.currencies(tenantId, from, to),
        content.transactionCount(),
        content.netTotal(),
        content.vatTotal(),
        repo.standing(tenantId, code, from, ccy).orElse(null));
  }

  /**
   * Reports a period over the network the business sends on.
   *
   * @param corrects the submission this replaces, when correcting one already sent
   * @throws ApiException 400 on an unknown return or an unreportable period; 409 {@code
   *     EREPORTING_NOT_DUE} where the duty does not bind, {@code EREPORTING_TRANSPORT_NOT_SET} with
   *     no network, {@code EREPORTING_NETWORK_CANNOT_REPORT} where the network takes no reports,
   *     {@code EREPORTING_ALREADY_SUBMITTED} for a period that stands
   */
  public Submission submit(
      UUID tenantId,
      String returnCode,
      LocalDate from,
      LocalDate to,
      String currency,
      UUID corrects,
      UUID actorId) {
    String code = requireReturn(returnCode);
    requirePeriod(from, to);
    requireDuty(tenantId);
    String ccy =
        currency == null || currency.isBlank()
            ? tenantCurrency(tenantId)
            : currency.strip().toUpperCase(Locale.ROOT);

    Settings settings =
        transportRepo
            .findSettings(tenantId)
            .filter(s -> !EInvoiceTransports.NETWORK_NONE.equals(s.network()))
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "EREPORTING_TRANSPORT_NOT_SET",
                        "the business sends over no network yet, so there is nowhere to report"));
    EInvoiceTransport transport = transports.forNetwork(settings.network(), settings.provider());
    if (transport == null) {
      throw ApiException.conflict(
          "EREPORTING_TRANSPORT_NOT_SET",
          settings.provider() + " is no longer deployed for " + settings.network());
    }
    // The network first, then the provider: the simulated provider stands in on every network, so
    // asking it alone would let a business on Peppol believe it had reported.
    if (!EInvoiceTransports.REPORTING.contains(settings.network()) || !transport.carriesReports()) {
      throw ApiException.conflict(
          "EREPORTING_NETWORK_CANNOT_REPORT",
          settings.network()
              + " carries invoices and not e-reporting; France's platform (FR_PDP) does");
    }
    if (corrects != null) {
      Submission previous =
          repo.find(tenantId, corrects)
              .orElseThrow(
                  () -> ApiException.notFound("EREPORTING_NOT_FOUND", "No such submission"));
      if (!previous.stands()) {
        throw ApiException.conflict(
            "EREPORTING_NOT_STANDING", "That submission has already been corrected");
      }
    }

    Content content = content(tenantId, code, from, to, ccy);
    String payload =
        EReporting.payload(content, sellerVatId(tenantId), profiles.requireCountry(tenantId));
    Instant now = Instant.now();
    Submission draft =
        new Submission(
            Ids.newId(),
            tenantId,
            code,
            from,
            to,
            ccy,
            content.transactionCount(),
            content.netTotal(),
            content.vatTotal(),
            payload,
            digest(payload),
            settings.network(),
            settings.provider(),
            EReporting.PENDING,
            null,
            null,
            0,
            now,
            actorId,
            null,
            corrects,
            null);
    Submission recorded = repo.record(draft);
    return transmit(recorded, transport, settings);
  }

  /**
   * Hands a recorded submission to the network and settles it with the answer.
   *
   * <p>The submission is written <em>before</em> the network is called, and settled after: a report
   * the network took and this platform forgot would be the worst outcome — the business would
   * report the same period twice and look to be correcting a figure nobody had seen.
   */
  private Submission transmit(Submission s, EInvoiceTransport transport, Settings settings) {
    Instant now = Instant.now();
    Outbound out;
    try {
      out =
          new Outbound(
              s.tenantId(),
              s.id(),
              "EReport",
              s.returnCode() + " " + s.periodStart() + "/" + s.periodEnd(),
              null,
              null,
              s.payload(),
              null,
              sellerVatId(s.tenantId()),
              settings.providerAccount(),
              settings.hasSecret() ? secrets.open(settings.providerSecret()) : null);
    } catch (IllegalStateException e) {
      repo.settle(
          s.tenantId(),
          s.id(),
          EReporting.PENDING,
          "the business's credential could not be opened: " + e.getMessage(),
          null,
          now);
      return reread(s);
    }
    try {
      Dispatch d = transport.sendReport(out);
      Outcome o = d.outcome();
      String status =
          switch (o.state()) {
            case EInvoiceTransports.STATUS_ACCEPTED -> EReporting.ACCEPTED;
            case EInvoiceTransports.STATUS_REJECTED -> EReporting.REJECTED;
            default -> EReporting.PENDING;
          };
      repo.settle(s.tenantId(), s.id(), status, o.detail(), d.providerRef(), now);
    } catch (EInvoiceTransport.TransportException e) {
      // The network could not be reached. The submission stands as PENDING with the reason: it is a
      // filing that has been prepared and not yet acknowledged, which is a state a business must be
      // able to see rather than a failure that loses the work.
      repo.settle(s.tenantId(), s.id(), EReporting.PENDING, e.getMessage(), null, now);
    }
    return reread(s);
  }

  private Submission reread(Submission s) {
    return repo.find(s.tenantId(), s.id()).orElse(s);
  }

  public List<Submission> submissions(UUID tenantId, int limit) {
    return repo.list(tenantId, Math.max(1, Math.min(200, limit)));
  }

  public Submission submission(UUID tenantId, UUID id) {
    return repo.find(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("EREPORTING_NOT_FOUND", "No such submission"));
  }

  // ── the content ─────────────────────────────────────────────────────────────

  private Content content(
      UUID tenantId, String returnCode, LocalDate from, LocalDate to, String currency) {
    String country = profiles.requireCountry(tenantId);
    if (EReporting.PAYMENTS.equals(returnCode)) {
      // Payment data asks when the money for a service arrived. A retail sale is paid as it is made
      // —
      // the till takes the money in the same act — so the day of supply is the day of payment, and
      // the days below are exactly that. A credit sale on account would need the capture date from
      // payment-svc; this platform does not sell on account, and saying so is better than implying
      // the number means more than it does.
      return new Content(
          returnCode, from, to, currency, repo.days(tenantId, currency, from, to), List.of());
    }
    return new Content(
        returnCode,
        from,
        to,
        currency,
        repo.days(tenantId, currency, from, to),
        repo.crossBorder(tenantId, currency, from, to, country));
  }

  private String requireReturn(String returnCode) {
    String code = returnCode == null ? "" : returnCode.strip().toUpperCase(Locale.ROOT);
    if (!EReporting.RETURNS.contains(code)) {
      throw ApiException.badRequest(
          "EREPORTING_RETURN_UNKNOWN",
          "A return is " + EReporting.TRANSACTIONS + " or " + EReporting.PAYMENTS);
    }
    return code;
  }

  private void requirePeriod(LocalDate from, LocalDate to) {
    String problem = EReporting.periodProblem(from, to, LocalDate.now(ZoneOffset.UTC));
    if (problem != null) throw ApiException.badRequest("EREPORTING_PERIOD_INVALID", problem);
  }

  /**
   * Refuses where the duty does not bind.
   *
   * <p>Asked of tenant-svc's obligations rather than of a country list here, and it fails
   * <b>closed</b> unlike a plan limit: reporting a period to a network that never asked for it puts
   * a business's trading data somewhere it does not belong, which is worse than refusing a button.
   */
  private void requireDuty(UUID tenantId) {
    String country = profiles.requireCountry(tenantId);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    // A 503 from the obligations read travels as it is: not knowing whether the duty binds is not
    // the same as knowing it does not, and this refusal fails closed.
    if (!jurisdictions.inForceIn(tenantId, country, "E_REPORTING", today)) {
      throw ApiException.conflict(
          "EREPORTING_NOT_DUE", "e-reporting does not bind a business in " + country + " today");
    }
  }

  /** The business's own currency. Never a literal: {@code currencyOr} refuses rather than guess. */
  private String tenantCurrency(UUID tenantId) {
    return profiles.currencyOr(tenantId, null);
  }

  private String sellerVatId(UUID tenantId) {
    return profiles
        .identity(tenantId)
        .map(i -> i.vatNumber())
        .filter(v -> v != null && !v.isBlank())
        .orElse(null);
  }

  /**
   * SHA-256 of the payload, base64: what a business puts on the filing it records in tenant-svc.
   */
  static String digest(String payload) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Visible for the resource: the standing submission for a period, if there is one. */
  Optional<Submission> standing(
      UUID tenantId, String returnCode, LocalDate periodStart, String currency) {
    return repo.standing(tenantId, returnCode, periodStart, currency);
  }
}
