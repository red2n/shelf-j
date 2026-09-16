package com.shelfj.order.service;

import com.shelfj.einvoice.EInvoices;
import com.shelfj.einvoice.Invoice;
import com.shelfj.ids.Ids;
import com.shelfj.order.domain.EInvoiceTransports;
import com.shelfj.order.domain.EInvoiceTransports.Settings;
import com.shelfj.order.domain.EInvoiceTransports.Transmission;
import com.shelfj.order.domain.SalesInvoices.SalesInvoice;
import com.shelfj.order.einvoice.EInvoiceTransport;
import com.shelfj.order.einvoice.EInvoiceTransport.Dispatch;
import com.shelfj.order.einvoice.EInvoiceTransport.Outbound;
import com.shelfj.order.einvoice.EInvoiceTransport.Outcome;
import com.shelfj.order.einvoice.EInvoiceTransport.TransportException;
import com.shelfj.order.einvoice.Secrets;
import com.shelfj.order.einvoice.Transports;
import com.shelfj.order.repo.EInvoiceTransportRepository;
import com.shelfj.order.repo.SalesInvoiceRepository;
import com.shelfj.service.Jurisdictions;
import com.shelfj.service.TenantProfiles;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Sends a business's e-invoices over the network it chose (the transport seam behind 07.13 and
 * 18.9). A document is queued when it is issued and sent by the worker; what the network says is
 * kept, attempt by attempt. A network that could not be reached is tried again, later each time; a
 * network that refused is not, until someone sends the document again by hand.
 */
@ApplicationScoped
public class EInvoiceTransportService {

  private static final System.Logger LOG =
      System.getLogger(EInvoiceTransportService.class.getName());

  @Inject EInvoiceTransportRepository repo;
  @Inject SalesInvoiceRepository invoices;
  @Inject Transports transports;
  @Inject TenantProfiles profiles;
  @Inject Jurisdictions jurisdictions;
  @Inject Secrets secrets;

  @Inject
  @ConfigProperty(name = "shelfj.order.einvoice-transport.retry-base-seconds", defaultValue = "30")
  long retryBaseSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.order.einvoice-transport.max-attempts", defaultValue = "8")
  int maxAttempts;

  @Inject
  @ConfigProperty(name = "shelfj.order.einvoice-transport.poll-seconds", defaultValue = "60")
  long pollSeconds;

  /** The settings with what this deployment offers, and what the business's country asks for. */
  public record SettingsView(
      Settings settings,
      List<String> networks,
      Map<String, List<String>> providers,
      Map<String, List<String>> available,
      Map<String, List<String>> needingSecret,
      String suggested,
      String senderAddress) {}

  /**
   * What a manager asks for.
   *
   * @param providerSecret the business's credential at the provider; null keeps the one stored,
   *     blank removes it
   */
  public record SettingsChange(
      String network, String provider, String providerAccount, String providerSecret) {}

  // ── settings ─────────────────────────────────────────────────────────────────

  public SettingsView settings(UUID tenantId) {
    return view(repo.findSettings(tenantId).orElse(Settings.none(tenantId)));
  }

  /**
   * Chooses the network documents leave on, and the provider behind it.
   *
   * @throws ApiException 400 {@code EINVOICE_NETWORK_UNKNOWN}, {@code EINVOICE_PROVIDER_REQUIRED},
   *     {@code EINVOICE_PROVIDER_UNKNOWN}; 409 {@code EINVOICE_PROVIDER_NOT_CONFIGURED} when the
   *     deployment lacks the provider's credentials, {@code EINVOICE_SENDER_ADDRESS_MISSING} when
   *     Peppol is chosen and the business has no electronic address to send from
   */
  public SettingsView setSettings(UUID tenantId, SettingsChange c, UUID userId) {
    String network = c.network() == null ? "" : c.network().strip().toUpperCase(Locale.ROOT);
    if (EInvoiceTransports.NETWORK_NONE.equals(network)) {
      Settings none = new Settings(tenantId, network, null, null, null, null, userId);
      repo.upsertSettings(none);
      return settings(tenantId);
    }
    if (!EInvoiceTransports.NETWORKS.contains(network)) {
      throw ApiException.badRequest(
          "EINVOICE_NETWORK_UNKNOWN",
          "network is NONE or one of " + EInvoiceTransports.NETWORKS + " — got: " + c.network());
    }
    String provider = c.provider() == null ? "" : c.provider().strip().toUpperCase(Locale.ROOT);
    if (provider.isBlank()) {
      throw ApiException.badRequest(
          "EINVOICE_PROVIDER_REQUIRED",
          "provider is one of " + transports.providers().get(network) + " for " + network);
    }
    EInvoiceTransport transport = transports.forNetwork(network, provider);
    if (transport == null) {
      throw ApiException.badRequest(
          "EINVOICE_PROVIDER_UNKNOWN",
          "provider must be one of "
              + transports.providers().get(network)
              + " for "
              + network
              + " — got: "
              + c.provider());
    }
    if (!transport.isConfigured()) {
      throw ApiException.conflict(
          "EINVOICE_PROVIDER_NOT_CONFIGURED",
          provider + " needs " + transport.configuration() + " on this deployment");
    }
    if (EInvoiceTransports.NETWORK_PEPPOL.equals(network) && senderAddress(tenantId) == null) {
      throw ApiException.conflict(
          "EINVOICE_SENDER_ADDRESS_MISSING",
          "Peppol needs the business's own electronic address; set it on the tenant first");
    }
    String account = c.providerAccount() == null ? null : c.providerAccount().strip();
    if (account != null && account.length() > 120) {
      throw ApiException.badRequest(
          "EINVOICE_PROVIDER_ACCOUNT_INVALID", "providerAccount is at most 120 characters");
    }
    // The credential: kept when not mentioned, removed when blank, sealed when given.
    String sealed = repo.findSettings(tenantId).map(Settings::providerSecret).orElse(null);
    if (c.providerSecret() != null) {
      String secret = c.providerSecret().strip();
      if (secret.isEmpty()) {
        sealed = null;
      } else {
        if (!secrets.isConfigured()) {
          throw ApiException.conflict(
              "EINVOICE_SECRETS_KEY_MISSING",
              "this deployment has no shelfj.einvoice.secrets-key, so a provider credential"
                  + " cannot be kept");
        }
        sealed = secrets.seal(secret);
      }
    }
    if (transport.needsSecret() && sealed == null) {
      throw ApiException.conflict(
          "EINVOICE_PROVIDER_SECRET_REQUIRED",
          provider + " signs in with the business's own credential: give providerSecret");
    }
    repo.upsertSettings(
        new Settings(
            tenantId,
            network,
            provider,
            account == null || account.isBlank() ? null : account,
            sealed,
            null,
            userId));
    return settings(tenantId);
  }

  private SettingsView view(Settings s) {
    return new SettingsView(
        s,
        EInvoiceTransports.NETWORKS,
        transports.providers(),
        transports.available(),
        transports.needingSecret(),
        suggested(s.tenantId()),
        senderAddress(s.tenantId()));
  }

  /** The business's VAT number — its GSTIN in India — or null. */
  String sellerVatId(UUID tenantId) {
    return profiles.identity(tenantId).map(i -> i.vatNumber()).filter(v -> present(v)).orElse(null);
  }

  /** The business's own electronic address, {@code scheme:identifier}, or null. */
  String senderAddress(UUID tenantId) {
    return profiles
        .identity(tenantId)
        .filter(i -> present(i.einvoiceScheme()) && present(i.einvoiceId()))
        .map(i -> i.einvoiceScheme() + ":" + i.einvoiceId())
        .orElse(null);
  }

  /** The network the business's country asks for today, from tenant-svc's obligations. */
  String suggested(UUID tenantId) {
    try {
      String country = profiles.requireCountry(tenantId);
      LocalDate today = LocalDate.now(ZoneOffset.UTC);
      List<String> codes =
          jurisdictions.obligations(tenantId, country).stream()
              .filter(o -> o.inForceOn(today))
              .map(Jurisdictions.Obligation::code)
              .toList();
      if (codes.contains("E_INVOICING_KSEF")) return EInvoiceTransports.NETWORK_KSEF;
      if (codes.contains("GST_E_INVOICING")) return EInvoiceTransports.NETWORK_IRP;
      if ("FR".equals(country) && codes.contains("E_INVOICING_ISSUE")) {
        return EInvoiceTransports.NETWORK_FR_PDP;
      }
      if (codes.contains("E_INVOICING_B2B") || codes.contains("E_INVOICING_ISSUE")) {
        return EInvoiceTransports.NETWORK_PEPPOL;
      }
      return null;
    } catch (RuntimeException e) {
      LOG.log(Level.DEBUG, () -> "no suggestion for " + tenantId + ": " + e.getMessage());
      return null;
    }
  }

  // ── sending ──────────────────────────────────────────────────────────────────

  /**
   * Queues a document just issued, when the business sends and the document can go: the worker
   * sends it. Anything that stops it is logged, never thrown; the document stands, and can be sent
   * by hand.
   */
  public void enqueueQuietly(SalesInvoice s) {
    try {
      Settings st = repo.findSettings(s.tenantId()).orElse(Settings.none(s.tenantId()));
      if (!st.sending() || !repo.byInvoice(s.tenantId(), s.id()).isEmpty()) return;
      String receiver = receiverOf(s, st.network());
      if (EInvoiceTransports.ADDRESSED.contains(st.network()) && receiver == null) {
        LOG.log(
            Level.INFO,
            "{0} not sent over {1}: the buyer has no electronic address",
            s.fullNumber(),
            st.network());
        return;
      }
      repo.enqueue(
          transmission(s, st, receiver, EInvoiceTransports.STATUS_QUEUED, 0, Instant.now(), null));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> s.fullNumber() + " not queued: " + e.getMessage(), e);
    }
  }

  /**
   * Sends a document now, by hand: the first time, or again after the network refused or could not
   * be reached.
   *
   * @throws ApiException 404 no such document; 409 {@code EINVOICE_TRANSPORT_NOT_SET}, {@code
   *     EINVOICE_ALREADY_SENT} (taken, on its way or delivered), {@code
   *     EINVOICE_RECEIVER_ADDRESS_MISSING}, {@code EINVOICE_PROVIDER_UNKNOWN} (the chosen provider
   *     is no longer deployed)
   */
  public Transmission send(UUID tenantId, UUID invoiceId, UUID userId) {
    SalesInvoice s =
        invoices
            .find(tenantId, invoiceId)
            .orElseThrow(
                () -> ApiException.notFound("ORDER_INVOICE_NOT_FOUND", "invoice not found"));
    Settings st = repo.findSettings(tenantId).orElse(Settings.none(tenantId));
    if (!st.sending()) {
      throw ApiException.conflict(
          "EINVOICE_TRANSPORT_NOT_SET", "the business sends its e-invoices over no network yet");
    }
    Optional<Transmission> latest = repo.byInvoice(tenantId, invoiceId).stream().findFirst();
    if (latest.isPresent()
        && (latest.get().open()
            || EInvoiceTransports.STATUS_ACCEPTED.equals(latest.get().status()))) {
      Transmission t = latest.get();
      throw ApiException.conflict(
          "EINVOICE_ALREADY_SENT",
          s.fullNumber()
              + " is "
              + t.status().toLowerCase(Locale.ROOT)
              + (t.providerRef() == null ? "" : " as " + t.providerRef())
              + "; a document is sent again only after the network refused it");
    }
    String receiver = receiverOf(s, st.network());
    if (EInvoiceTransports.ADDRESSED.contains(st.network()) && receiver == null) {
      throw ApiException.conflict(
          "EINVOICE_RECEIVER_ADDRESS_MISSING",
          "the buyer has no electronic address to send to; record one on the customer's VAT"
              + " registration and issue the next document");
    }
    if (transports.forNetwork(st.network(), st.provider()) == null) {
      throw ApiException.conflict(
          "EINVOICE_PROVIDER_UNKNOWN",
          st.provider() + " is no longer deployed for " + st.network() + "; choose another");
    }
    // Taken here, not queued: the caller is told what the network said.
    Transmission t =
        transmission(s, st, receiver, EInvoiceTransports.STATUS_SENDING, 1, Instant.now(), userId);
    try {
      repo.enqueue(t);
    } catch (ApiException e) {
      // The database holds one open or delivered attempt per document: eight asking at once
      // send once, and the other seven are told so.
      if ("DUPLICATE".equals(e.code())) {
        throw new ApiException(
            409, "EINVOICE_ALREADY_SENT", s.fullNumber() + " is already being sent", List.of(), e);
      }
      throw e;
    }
    attempt(t, s, st);
    return repo.find(tenantId, t.id()).orElseThrow();
  }

  /** Sends what is due, once each; how many were tried. */
  public int deliverDue(int limit) {
    List<Transmission> due = repo.claimDue(limit);
    for (Transmission t : due) {
      Optional<SalesInvoice> s = invoices.find(t.tenantId(), t.invoiceId());
      if (s.isEmpty()) {
        Instant now = Instant.now();
        repo.settle(
            t.tenantId(),
            t.id(),
            EInvoiceTransports.STATUS_FAILED,
            now,
            null,
            "the document is gone",
            null,
            null,
            now);
        continue;
      }
      Settings st = repo.findSettings(t.tenantId()).orElse(Settings.none(t.tenantId()));
      attempt(t, s.get(), st);
    }
    return due.size();
  }

  public Map<UUID, Transmission> latest(UUID tenantId, List<UUID> invoiceIds) {
    return repo.latestByInvoices(tenantId, invoiceIds);
  }

  public List<Transmission> of(UUID tenantId, UUID invoiceId) {
    invoices
        .find(tenantId, invoiceId)
        .orElseThrow(() -> ApiException.notFound("ORDER_INVOICE_NOT_FOUND", "invoice not found"));
    return repo.byInvoice(tenantId, invoiceId);
  }

  public List<Transmission> list(UUID tenantId, String status, UUID after, int limit) {
    String st = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
    if (st != null && !EInvoiceTransports.STATUSES.contains(st)) {
      throw ApiException.badRequest(
          "EINVOICE_TRANSMISSION_STATUS_UNKNOWN",
          "status is one of " + EInvoiceTransports.STATUSES);
    }
    return repo.list(tenantId, st, after, limit);
  }

  // ── one attempt ──────────────────────────────────────────────────────────────

  /** One try at the network: sent, or asked after when it already took the document. */
  void attempt(Transmission t, SalesInvoice s, Settings st) {
    Instant now = Instant.now();
    EInvoiceTransport transport = transports.forNetwork(t.network(), t.provider());
    if (transport == null) {
      repo.settle(
          t.tenantId(),
          t.id(),
          EInvoiceTransports.STATUS_FAILED,
          now,
          null,
          t.provider() + " is no longer deployed for " + t.network(),
          null,
          null,
          now);
      return;
    }
    Outbound out;
    try {
      out =
          new Outbound(
              s.tenantId(),
              s.id(),
              s.creditNote() ? "CreditNote" : "Invoice",
              s.fullNumber(),
              senderAddress(s.tenantId()),
              t.receiver(),
              s.document(),
              s.irpPayload(),
              sellerVatId(s.tenantId()),
              st.providerAccount(),
              st.hasSecret() ? secrets.open(st.providerSecret()) : null);
    } catch (IllegalStateException e) {
      // The credential cannot be opened: the deployment's key changed, or the row was altered.
      repo.settle(
          t.tenantId(),
          t.id(),
          EInvoiceTransports.STATUS_FAILED,
          now,
          null,
          "the business's credential could not be opened: " + e.getMessage(),
          null,
          null,
          now);
      return;
    }
    try {
      String ref = t.providerRef();
      Outcome o;
      if (ref != null && t.sentAt() != null) {
        o = transport.status(out, ref);
      } else {
        Dispatch d = transport.send(out);
        ref = d.providerRef();
        o = d.outcome();
      }
      if (o.reference() != null && !o.reference().isBlank()) ref = o.reference();
      boolean taken = !EInvoiceTransports.STATUS_REJECTED.equals(o.state()) || ref != null;
      Instant sentAt = taken ? now : null;
      switch (o.state()) {
        case EInvoiceTransports.STATUS_ACCEPTED ->
            repo.settle(
                t.tenantId(), t.id(), o.state(), now, ref, o.detail(), o.response(), sentAt, now);
        case EInvoiceTransports.STATUS_REJECTED ->
            repo.settle(
                t.tenantId(), t.id(), o.state(), now, ref, o.detail(), o.response(), sentAt, now);
        default ->
            repo.settle(
                t.tenantId(),
                t.id(),
                EInvoiceTransports.STATUS_PENDING,
                now.plusSeconds(pollSeconds),
                ref,
                o.detail(),
                o.response(),
                sentAt,
                null);
      }
    } catch (TransportException e) {
      Optional<Duration> wait =
          EInvoiceTransports.backoff(
              t.attempts(), Duration.ofSeconds(retryBaseSeconds), maxAttempts);
      if (wait.isPresent()) {
        repo.settle(
            t.tenantId(),
            t.id(),
            t.sentAt() != null
                ? EInvoiceTransports.STATUS_PENDING
                : EInvoiceTransports.STATUS_QUEUED,
            now.plus(wait.get()),
            null,
            "attempt "
                + t.attempts()
                + ": "
                + e.getMessage()
                + "; trying again in "
                + wait.get().toSeconds()
                + "s",
            null,
            null,
            null);
      } else {
        repo.settle(
            t.tenantId(),
            t.id(),
            EInvoiceTransports.STATUS_FAILED,
            now,
            null,
            "gave up after " + t.attempts() + " attempts: " + e.getMessage(),
            null,
            null,
            now);
      }
    }
  }

  private Transmission transmission(
      SalesInvoice s,
      Settings st,
      String receiver,
      String status,
      int attempts,
      Instant at,
      UUID by) {
    return new Transmission(
        Ids.newId(),
        s.tenantId(),
        s.id(),
        st.network(),
        st.provider(),
        status,
        attempts,
        at,
        receiver,
        null,
        null,
        null,
        at,
        at,
        null,
        null,
        by);
  }

  /** The buyer's electronic address as the document names it, for a network that addresses. */
  static String receiverOf(SalesInvoice s, String network) {
    if (!EInvoiceTransports.ADDRESSED.contains(network)) return null;
    Invoice inv = EInvoices.read(s.document().getBytes(StandardCharsets.UTF_8)).invoice();
    Invoice.Identifier endpoint = inv.buyer() == null ? null : inv.buyer().electronicAddress();
    return endpoint == null || !present(endpoint.id())
        ? null
        : endpoint.scheme() + ":" + endpoint.id();
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }
}
