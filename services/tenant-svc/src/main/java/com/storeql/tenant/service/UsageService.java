package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.domain.Meters.Alert;
import com.storeql.tenant.domain.Meters.Charge;
import com.storeql.tenant.domain.Meters.Meter;
import com.storeql.tenant.domain.Meters.PlanMeter;
import com.storeql.tenant.domain.Meters.Reading;
import com.storeql.tenant.domain.Meters.UsagePeriod;
import com.storeql.tenant.domain.Subscriptions;
import com.storeql.tenant.domain.Subscriptions.InvoiceLine;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.repo.BillingRepository;
import com.storeql.tenant.repo.PlanRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.tenant.repo.UsageRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Usage metering and quotas (21.10): counting what a business does, reading it against its plan,
 * answering whether one more may be done, and closing a period onto the invoice that bills it.
 *
 * <p>The period is the subscription's own — the one it is billed by — so what the console shows as
 * this period's usage is exactly what the next invoice will bill. A business with no subscription
 * is read by the calendar month, and nothing is billed for it.
 */
@ApplicationScoped
public class UsageService {

  private static final System.Logger LOG = System.getLogger(UsageService.class.getName());

  /** How many closed periods a business is shown. Two years of monthly periods, both meters. */
  static final int HISTORY = 48;

  @Inject UsageRepository repo;
  @Inject PlanRepository plans;
  @Inject BillingRepository subscriptions;
  @Inject TenantRepository tenants;

  /**
   * One period, and whether it is a trial.
   *
   * @param end exclusive: the next period starts here
   */
  public record Period(LocalDate start, LocalDate end, boolean trial) {}

  /**
   * What a business has used this period, and before.
   *
   * @param planId null when it is on no plan
   * @param currency what an overage is charged in
   */
  public record Summary(
      Period period,
      UUID planId,
      String currency,
      List<Reading> readings,
      List<Alert> alerts,
      List<UsagePeriod> history) {
    public Summary {
      readings = List.copyOf(readings);
      alerts = List.copyOf(alerts);
      history = List.copyOf(history);
    }
  }

  /**
   * Whether a business may do {@code quantity} more of something now.
   *
   * @param included null when there is no ceiling
   * @param allowed false only on a hard ceiling this would pass
   */
  public record Allowance(
      String meter, long used, Long included, boolean hard, long quantity, boolean allowed) {}

  /**
   * What closing a period puts on an invoice.
   *
   * @param periods what each meter used and billed, to be written with the invoice
   * @param lines the invoice lines for what was charged; empty when nothing was
   */
  public record Closing(List<UsagePeriod> periods, List<InvoiceLine> lines) {
    public Closing {
      periods = List.copyOf(periods);
      lines = List.copyOf(lines);
    }
  }

  // ── counting ────────────────────────────────────────────────────────────────

  /**
   * Counts one thing a business did, once: the same source again is the same thing.
   *
   * @param sourceRef what was counted, as the counting service names it — an order id, a sent
   *     text's event id
   */
  public UsageRepository.Recorded record(
      UUID tenantId, String meterKey, long quantity, String sourceRef) {
    Meter meter =
        Meters.meter(meterKey)
            .orElseThrow(() -> new IllegalArgumentException("no such meter: " + meterKey));
    if (quantity <= 0) throw new IllegalArgumentException("usage is counted in whole units");
    Instant now = Instant.now();
    Optional<Subscription> sub = subscriptions.ofTenant(tenantId);
    Period period = periodOf(sub, now);
    Long included =
        planMeter(planOf(tenantId, sub), meter.key()).map(PlanMeter::included).orElse(null);
    UsageRepository.Recorded recorded =
        repo.record(tenantId, meter.key(), quantity, sourceRef, now, period.start(), included);
    for (int threshold : recorded.raised()) {
      LOG.log(
          System.Logger.Level.INFO,
          "{0} has used {1}% of the {2} its plan includes this period ({3} of {4})",
          tenantId,
          threshold,
          meter.label().toLowerCase(java.util.Locale.ROOT),
          recorded.used(),
          included);
    }
    return recorded;
  }

  // ── reading ─────────────────────────────────────────────────────────────────

  /** What a business has used this period, against its plan, and what it used before. */
  public Summary summary(UUID tenantId) {
    Tenant tenant = requireTenant(tenantId);
    Optional<Subscription> sub = subscriptions.ofTenant(tenantId);
    Period period = periodOf(sub, Instant.now());
    UUID planId = planOf(tenantId, sub);
    String currency = sub.map(Subscription::currency).orElse(tenant.currency());
    List<Reading> readings = new ArrayList<>();
    for (Meter meter : Meters.CATALOGUE) {
      readings.add(reading(tenantId, meter, period, planId, currency));
    }
    return new Summary(
        period,
        planId,
        currency,
        readings,
        repo.alerts(tenantId, period.start()),
        repo.periods(tenantId, HISTORY));
  }

  /**
   * Whether a business may do {@code quantity} more of a meter now. Asked by the service about to
   * do it; only a hard ceiling ever answers no.
   *
   * @throws ApiException 400 {@code USAGE_METER_UNKNOWN}, {@code USAGE_QUANTITY_INVALID}
   */
  public Allowance allowance(UUID tenantId, String meterKey, long quantity) {
    Meter meter =
        Meters.meter(meterKey)
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "USAGE_METER_UNKNOWN",
                        "The platform counts "
                            + Meters.CATALOGUE.stream().map(Meter::key).toList()
                            + ", not "
                            + meterKey));
    if (quantity <= 0 || quantity > 1_000_000) {
      throw ApiException.badRequest(
          "USAGE_QUANTITY_INVALID", "quantity is a whole number from 1 to 1,000,000");
    }
    Optional<Subscription> sub = subscriptions.ofTenant(tenantId);
    Period period = periodOf(sub, Instant.now());
    Optional<PlanMeter> pm = planMeter(planOf(tenantId, sub), meter.key());
    Long included = pm.map(PlanMeter::included).orElse(null);
    boolean hard = pm.map(PlanMeter::hard).orElse(false) && meter.refusable();
    long used = usedSince(tenantId, meter.key(), period.start());
    boolean allowed = !hard || included == null || used + quantity <= included;
    return new Allowance(meter.key(), used, included, hard, quantity, allowed);
  }

  /** Every threshold reached across businesses, newest first: who is outgrowing a plan. */
  public List<Alert> recentAlerts(Integer limit) {
    int n = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
    return repo.recentAlerts(n);
  }

  // ── billing ─────────────────────────────────────────────────────────────────

  /**
   * Closes one period of a subscription: what each meter used in it, and the invoice lines for what
   * it owes beyond the plan.
   *
   * <p>Used is everything recorded since the subscription started and before the period ended, less
   * what earlier periods already billed. So a record that committed a moment after the period
   * before was billed is billed now — late, never lost, never twice.
   *
   * @param s the subscription as it stood through the period: its plan is the one the period was
   *     lived on, before any downgrade waiting for the period to end
   * @param firstLine the number the first usage line takes on its invoice
   */
  public Closing close(Subscription s, LocalDate start, LocalDate end, int firstLine) {
    boolean trial = Subscriptions.TRIALING.equals(s.status());
    Instant until = UsageRepository.startOf(end);
    Instant now = Instant.now();
    List<UsagePeriod> periods = new ArrayList<>();
    List<InvoiceLine> lines = new ArrayList<>();
    int lineNo = firstLine;
    // A period closed already — an end that failed after its usage was billed, and is being run
    // again — is not closed twice: the retry goes on to what failed instead of stopping here.
    java.util.Set<String> done = repo.closed(s.id(), start);
    for (Meter meter : Meters.CATALOGUE) {
      if (done.contains(meter.key())) continue;
      long used =
          Math.max(
              0,
              repo.used(s.tenantId(), meter.key(), s.startedAt(), until)
                  - repo.billed(s.id(), meter.key()));
      Optional<PlanMeter> pm = planMeter(s.planId(), meter.key());
      if (used == 0 && pm.isEmpty()) continue;
      Long included = pm.map(PlanMeter::included).orElse(null);
      Charge charge =
          Meters.charge(
              used,
              included,
              plans.meterPriceOn(s.planId(), meter.key(), s.currency(), start),
              trial);
      periods.add(
          new UsagePeriod(
              Ids.newId(),
              s.tenantId(),
              s.id(),
              meter.key(),
              start,
              end,
              used,
              included,
              charge.over(),
              s.currency(),
              charge.unitAmount(),
              charge.amount(),
              charge.notCharged(),
              null,
              now));
      if (charge.amount().signum() > 0) {
        lines.add(
            new InvoiceLine(
                Ids.newId(),
                lineNo++,
                Subscriptions.LINE_USAGE,
                meter.label()
                    + ", "
                    + start
                    + " to "
                    + end
                    + ": beyond the "
                    + included
                    + " included",
                BigDecimal.valueOf(charge.over()),
                charge.unitAmount(),
                charge.amount()));
      }
    }
    return new Closing(periods, lines);
  }

  /** Writes what a period used when no invoice carries it: nothing was charged. */
  public void closeWithoutInvoice(Closing closing) {
    repo.close(closing.periods());
  }

  // ── small things ────────────────────────────────────────────────────────────

  private Reading reading(UUID tenantId, Meter meter, Period period, UUID planId, String currency) {
    long used = usedSince(tenantId, meter.key(), period.start());
    Optional<PlanMeter> pm = planMeter(planId, meter.key());
    Long included = pm.map(PlanMeter::included).orElse(null);
    Optional<BigDecimal> price =
        planId == null
            ? Optional.empty()
            : plans.meterPriceOn(planId, meter.key(), currency, period.start());
    Charge charge = Meters.charge(used, included, price, period.trial());
    return new Reading(
        meter,
        used,
        included,
        pm.map(PlanMeter::hard).orElse(false),
        charge.over(),
        currency,
        price.orElse(null),
        charge.amount());
  }

  private long usedSince(UUID tenantId, String meter, LocalDate start) {
    return repo.used(tenantId, meter, UsageRepository.startOf(start), Instant.MAX);
  }

  /**
   * The period a business is in: its subscription's while it has one, otherwise the calendar month.
   * A period whose end has passed is still the period until the billing run renews it — which is
   * what the next invoice will bill.
   */
  static Period periodOf(Optional<Subscription> sub, Instant now) {
    if (sub.isPresent() && !Subscriptions.CANCELLED.equals(sub.get().status())) {
      Subscription s = sub.get();
      return new Period(s.periodStart(), s.periodEnd(), Subscriptions.TRIALING.equals(s.status()));
    }
    LocalDate first = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    return new Period(first, first.plusMonths(1), false);
  }

  /** The plan a business is on now: the tenant's own, which the subscription follows. */
  private UUID planOf(UUID tenantId, Optional<Subscription> sub) {
    return tenants
        .findTenant(tenantId)
        .map(Tenant::planId)
        .orElseGet(() -> sub.map(Subscription::planId).orElse(null));
  }

  private Optional<PlanMeter> planMeter(UUID planId, String meter) {
    if (planId == null) return Optional.empty();
    return plans.meters(planId).stream().filter(m -> m.meter().equals(meter)).findFirst();
  }

  private Tenant requireTenant(UUID tenantId) {
    return tenants
        .findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such business"));
  }
}
