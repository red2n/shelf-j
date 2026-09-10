package com.shelfj.pricing.repo;

import com.shelfj.pricing.domain.Domain;
import com.shelfj.pricing.domain.Domain.CustomerVatStatus;
import com.shelfj.pricing.domain.Domain.PriceList;
import com.shelfj.pricing.domain.Domain.PriceListItem;
import com.shelfj.pricing.domain.Domain.PriceOverride;
import com.shelfj.pricing.domain.Domain.ProductVatCategory;
import com.shelfj.pricing.domain.Domain.Promotion;
import com.shelfj.pricing.domain.Domain.PromotionItem;
import com.shelfj.pricing.domain.Domain.TaxTransaction;
import com.shelfj.pricing.domain.Domain.VatRate;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC persistence for pricing-svc. Every tenant query filters by tenant_id first. */
@ApplicationScoped
public class PricingRepository extends BaseOutboxRepository {

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  public VatRate createVatRate(VatRate r) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO vat_rates"
                      + " (id,tenant_id,code,name,rate,exempt,description,effective_from,effective_to)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setString(3, r.code());
            ps.setString(4, r.name());
            ps.setBigDecimal(5, r.rate());
            ps.setBoolean(6, r.exempt());
            ps.setString(7, r.description());
            ps.setObject(8, toOdt(r.effectiveFrom()));
            ps.setObject(9, toOdt(r.effectiveTo()));
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "PRICING_VAT_CODE_EXISTS",
                  "VAT code " + r.code() + " already configured for this tenant",
                  List.of(),
                  sqle);
            throw sqle;
          }
          return r;
        },
        "create vat rate");
  }

  public List<VatRate> findVatRates(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,code,name,rate,exempt,description,effective_from,effective_to,created_at"
            + " FROM vat_rates WHERE tenant_id=? ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        this::mapVatRate,
        "list vat rates");
  }

  public Optional<VatRate> findVatRate(UUID tenantId, String code) {
    var list =
        query(
            "SELECT id,tenant_id,code,name,rate,exempt,description,effective_from,effective_to,created_at"
                + " FROM vat_rates WHERE tenant_id=? AND code=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, code);
            },
            this::mapVatRate,
            "find vat rate");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public VatRate updateVatRate(VatRate r) {
    exec(
        "UPDATE vat_rates SET name=?,rate=?,exempt=?,description=?,effective_from=?,effective_to=?"
            + " WHERE tenant_id=? AND code=?",
        ps -> {
          ps.setString(1, r.name());
          ps.setBigDecimal(2, r.rate());
          ps.setBoolean(3, r.exempt());
          ps.setString(4, r.description());
          ps.setObject(5, toOdt(r.effectiveFrom()));
          ps.setObject(6, toOdt(r.effectiveTo()));
          ps.setObject(7, r.tenantId());
          ps.setString(8, r.code());
        },
        "update vat rate");
    return r;
  }

  private VatRate mapVatRate(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime effTo = rs.getObject("effective_to", OffsetDateTime.class);
    return new VatRate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getBigDecimal("rate"),
        rs.getBoolean("exempt"),
        rs.getString("description"),
        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
        effTo != null ? effTo.toInstant() : null,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Product VAT Categories ────────────────────────────────────────────────

  public ProductVatCategory upsertProductVatCategory(ProductVatCategory pvc) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO product_vat_categories"
                      + " (id,tenant_id,variant_id,vat_code,effective_from)"
                      + " VALUES (?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,variant_id)"
                      + " DO UPDATE SET vat_code=EXCLUDED.vat_code,"
                      + "  effective_from=EXCLUDED.effective_from,"
                      + "  effective_to=NULL")) {
            ps.setObject(1, pvc.id());
            ps.setObject(2, pvc.tenantId());
            ps.setObject(3, pvc.variantId());
            ps.setString(4, pvc.vatCode());
            ps.setObject(5, toOdt(pvc.effectiveFrom()));
            ps.executeUpdate();
          }
          return pvc;
        },
        "upsert product vat category");
  }

  public Optional<ProductVatCategory> findProductVatCategory(UUID tenantId, UUID variantId) {
    var list =
        query(
            "SELECT id,tenant_id,variant_id,vat_code,effective_from,effective_to,created_at"
                + " FROM product_vat_categories WHERE tenant_id=? AND variant_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            rs -> {
              OffsetDateTime effTo = rs.getObject("effective_to", OffsetDateTime.class);
              return new ProductVatCategory(
                  rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getString("vat_code"),
                  rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                  effTo != null ? effTo.toInstant() : null,
                  rs.getObject("created_at", OffsetDateTime.class).toInstant());
            },
            "find product vat category");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  // ── Customer VAT Status ───────────────────────────────────────────────────

  public CustomerVatStatus upsertCustomerVatStatus(CustomerVatStatus cvs) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO customer_vat_status"
                      + " (id,tenant_id,customer_id,vat_number,vat_registered,"
                      + "  reverse_charge_eligible,country_code)"
                      + " VALUES (?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,customer_id)"
                      + " DO UPDATE SET vat_number=EXCLUDED.vat_number,"
                      + "  vat_registered=EXCLUDED.vat_registered,"
                      + "  reverse_charge_eligible=EXCLUDED.reverse_charge_eligible,"
                      + "  country_code=EXCLUDED.country_code,"
                      + "  updated_at=now()")) {
            ps.setObject(1, cvs.id());
            ps.setObject(2, cvs.tenantId());
            ps.setObject(3, cvs.customerId());
            ps.setString(4, cvs.vatNumber());
            ps.setBoolean(5, cvs.vatRegistered());
            ps.setBoolean(6, cvs.reverseChargeEligible());
            ps.setString(7, cvs.countryCode() != null ? cvs.countryCode() : "GB");
            ps.executeUpdate();
          }
          return cvs;
        },
        "upsert customer vat status");
  }

  public Optional<CustomerVatStatus> findCustomerVatStatus(UUID tenantId, UUID customerId) {
    var list =
        query(
            "SELECT id,tenant_id,customer_id,vat_number,vat_registered,"
                + "  reverse_charge_eligible,country_code,created_at,updated_at"
                + " FROM customer_vat_status WHERE tenant_id=? AND customer_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            rs ->
                new CustomerVatStatus(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("customer_id", UUID.class),
                    rs.getString("vat_number"),
                    rs.getBoolean("vat_registered"),
                    rs.getBoolean("reverse_charge_eligible"),
                    rs.getString("country_code"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
            "find customer vat status");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  // ── Price Lists ───────────────────────────────────────────────────────────

  public PriceList createPriceList(PriceList pl) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_lists"
                      + " (id,tenant_id,name,channel,currency,effective_from,effective_to,active)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, pl.id());
            ps.setObject(2, pl.tenantId());
            ps.setString(3, pl.name());
            ps.setString(4, pl.channel());
            ps.setString(5, pl.currency());
            ps.setObject(6, toOdt(pl.effectiveFrom()));
            ps.setObject(7, toOdt(pl.effectiveTo()));
            ps.setBoolean(8, pl.active());
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "PRICING_LIST_NAME_EXISTS",
                  "Price list '" + pl.name() + "' already exists",
                  List.of(),
                  sqle);
            throw sqle;
          }
          return pl;
        },
        "create price list");
  }

  /** Keyset page of price lists: rows strictly after the cursor in (created_at, id) order. */
  public List<PriceList> findPriceLists(
      UUID tenantId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id,tenant_id,name,channel,currency,effective_from,effective_to,active,"
                + "created_at FROM price_lists WHERE tenant_id=?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        this::mapPriceList,
        "list price lists");
  }

  public Optional<PriceList> findPriceList(UUID tenantId, UUID id) {
    var list =
        query(
            "SELECT id,tenant_id,name,channel,currency,effective_from,effective_to,active,created_at"
                + " FROM price_lists WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapPriceList,
            "find price list");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private PriceList mapPriceList(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime effTo = rs.getObject("effective_to", OffsetDateTime.class);
    return new PriceList(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("channel"),
        rs.getString("currency"),
        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
        effTo != null ? effTo.toInstant() : null,
        rs.getBoolean("active"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Price List Items ──────────────────────────────────────────────────────

  public PriceListItem upsertPriceListItem(PriceListItem item, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_list_items"
                      + " (id,tenant_id,price_list_id,variant_id,price,min_qty)"
                      + " VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,price_list_id,variant_id,min_qty)"
                      + " DO UPDATE SET price=EXCLUDED.price, updated_at=now()")) {
            ps.setObject(1, item.id());
            ps.setObject(2, item.tenantId());
            ps.setObject(3, item.priceListId());
            ps.setObject(4, item.variantId());
            ps.setBigDecimal(5, item.price());
            ps.setBigDecimal(6, item.minQty());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return item;
        },
        "upsert price list item");
  }

  public List<PriceListItem> findPriceListItems(UUID tenantId, UUID priceListId) {
    return query(
        "SELECT id,tenant_id,price_list_id,variant_id,price,min_qty,created_at,updated_at"
            + " FROM price_list_items WHERE tenant_id=? AND price_list_id=? ORDER BY variant_id,min_qty",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, priceListId);
        },
        rs ->
            new PriceListItem(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("price_list_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("min_qty"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
        "list price list items");
  }

  /** Resolve best price: active price list matching channel + qty-break tier, lowest price wins. */
  public Optional<PriceListItem> resolveBasePrice(
      UUID tenantId, UUID variantId, String channel, BigDecimal qty) {
    var list =
        query(
            "SELECT pli.id, pli.tenant_id, pli.price_list_id, pli.variant_id,"
                + "  pli.price, pli.min_qty, pli.created_at, pli.updated_at"
                + " FROM price_list_items pli"
                + " JOIN price_lists pl ON pl.id = pli.price_list_id"
                + " WHERE pli.tenant_id = ?"
                + "   AND pli.variant_id = ?"
                + "   AND (pl.channel = ? OR pl.channel = 'ALL')"
                + "   AND pl.active = TRUE"
                + "   AND pl.effective_from <= now()"
                + "   AND (pl.effective_to IS NULL OR pl.effective_to > now())"
                + "   AND pli.min_qty <= ?"
                + " ORDER BY pli.min_qty DESC, pli.price ASC"
                + " LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setString(3, channel);
              ps.setBigDecimal(4, qty);
            },
            rs ->
                new PriceListItem(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("price_list_id", UUID.class),
                    rs.getObject("variant_id", UUID.class),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("min_qty"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
            "resolve base price");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Maps a unique-key clash to the 409 it is, rather than letting it surface as a 500.
   *
   * <p>The clash that matters is the coupon code: two promotions sharing one would put the engine
   * back where this rebuild found it, with which offer a customer got decided by an accident of
   * ordering. Without this the caller is told the server broke, which is both wrong and — per SJ-D9
   * — indistinguishable from a real fault to whoever is reading the alerts.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409,
          "PRICING_COUPON_CODE_TAKEN",
          "another promotion in this tenant already uses that coupon code (codes are matched"
              + " case-insensitively)",
          List.of(),
          e);
    return dbError(what, e);
  }

  // ── Promotions ────────────────────────────────────────────────────────────

  public Promotion createPromotion(Promotion p, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO promotions"
                      + " (id,tenant_id,store_id,name,type,value,min_order_amount,"
                      + "  channel,active,starts_at,ends_at,priority,exclusive,coupon_code,"
                      + "  max_redemptions,max_per_customer,buy_qty,get_qty,get_discount_pct)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, p.id());
            ps.setObject(2, p.tenantId());
            ps.setObject(3, p.storeId());
            ps.setString(4, p.name());
            ps.setString(5, p.type());
            ps.setBigDecimal(6, p.value());
            ps.setBigDecimal(7, p.minOrderAmount());
            ps.setString(8, p.channel());
            ps.setBoolean(9, p.active());
            ps.setObject(10, toOdt(p.startsAt()));
            ps.setObject(11, toOdt(p.endsAt()));
            ps.setInt(12, p.priority());
            ps.setBoolean(13, p.exclusive());
            ps.setString(14, p.couponCode());
            setIntOrNull(ps, 15, p.maxRedemptions());
            setIntOrNull(ps, 16, p.maxPerCustomer());
            ps.setBigDecimal(17, p.buyQty());
            ps.setBigDecimal(18, p.getQty());
            ps.setBigDecimal(19, p.getDiscountPct());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return p;
        },
        "create promotion");
  }

  /**
   * Switches a promotion or a price list on or off, and records who did it and why, atomically
   * (SJ-D33).
   *
   * <p>The two halves must not be separable. A promotion that stops running with no record of who
   * stopped it is a discount that vanished from the shop floor with nobody accountable, and a trail
   * row written for a switch that did not throw is worse than no trail at all.
   *
   * <p>The guard sits in the {@code WHERE} clause rather than in a preceding read, on the same
   * reasoning as every other state transition in this codebase: two people stopping the same
   * promotion at once must not both write a trail row claiming they were the one who did it.
   *
   * @param table the physical table — {@code promotions} or {@code price_lists}, chosen by the
   *     caller from a closed set, never from user input
   * @param change the append-only trail row, carrying the state being moved TO
   * @return {@code true} if this call changed the state; {@code false} if it was already there
   */
  public boolean setActive(String table, Domain.StatusChange change) {
    if (!"promotions".equals(table) && !"price_lists".equals(table)) {
      throw new IllegalArgumentException("not a switchable table: " + table);
    }
    return inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE " + table + " SET active=? WHERE tenant_id=? AND id=? AND active<>?")) {
            ps.setBoolean(1, change.active());
            ps.setObject(2, change.tenantId());
            ps.setObject(3, change.subjectId());
            ps.setBoolean(4, change.active());
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO promotion_status_changes"
                      + " (id,tenant_id,subject_type,subject_id,active,reason,changed_by)"
                      + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, change.id());
            ps.setObject(2, change.tenantId());
            ps.setString(3, change.subjectType());
            ps.setObject(4, change.subjectId());
            ps.setBoolean(5, change.active());
            ps.setString(6, change.reason());
            ps.setObject(7, change.changedBy());
            ps.executeUpdate();
          }
          return true;
        },
        "set active");
  }

  /** Whether a promotion or price list exists for this tenant, and whether it is currently live. */
  public Boolean findActive(String table, UUID tenantId, UUID id) {
    if (!"promotions".equals(table) && !"price_lists".equals(table)) {
      throw new IllegalArgumentException("not a switchable table: " + table);
    }
    var rows =
        query(
            "SELECT active FROM " + table + " WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            rs -> rs.getBoolean("active"),
            "read active");
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** The on/off history for one promotion or price list, newest first. Append-only. */
  public List<Domain.StatusChange> findStatusChanges(String subjectType, UUID tenantId, UUID id) {
    return query(
        "SELECT id,tenant_id,subject_type,subject_id,active,reason,changed_by,changed_at"
            + " FROM promotion_status_changes"
            + " WHERE tenant_id=? AND subject_type=? AND subject_id=?"
            + " ORDER BY changed_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, subjectType);
          ps.setObject(3, id);
        },
        rs ->
            new Domain.StatusChange(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getBoolean("active"),
                rs.getString("reason"),
                rs.getObject("changed_by", UUID.class),
                rs.getObject("changed_at", java.time.OffsetDateTime.class).toInstant()),
        "find status changes");
  }

  /**
   * Every promotion live for this tenant, store, channel and instant — the whole candidate list,
   * for {@link com.shelfj.pricing.service.PromotionEngine} to choose between.
   *
   * <p>Replaces a query that ended {@code ORDER BY p.value DESC LIMIT 1}, which decided the winner
   * in SQL by comparing a PERCENT's value (15, meaning 15%) against a FLAT's (20, meaning £20) as
   * though they shared a unit. Which offer a customer got therefore depended on a comparison
   * between a percentage and a sum of money. Ordering is now the engine's job and is done on an
   * explicit {@code priority}.
   *
   * <p><b>The store filter is new and was a live defect.</b> {@code promotions.store_id} has been
   * stored since V1 and filtered nowhere, so a promotion created for one shop ran in every shop of
   * the tenant. A NULL store_id still means "all stores", which is what the column was for.
   *
   * @param storeId the store being priced, or null to consider only tenant-wide promotions
   */
  public List<Promotion> findCandidatePromotions(
      UUID tenantId, UUID storeId, String channel, Instant now) {
    return query(
        "SELECT p.id, p.tenant_id, p.store_id, p.name, p.type, p.value,"
            + "  p.min_order_amount, p.channel, p.active, p.starts_at, p.ends_at, p.created_at,"
            + "  p.priority, p.exclusive, p.coupon_code, p.max_redemptions, p.max_per_customer,"
            + "  p.buy_qty, p.get_qty, p.get_discount_pct"
            + " FROM promotions p"
            + " WHERE p.tenant_id = ?"
            + "   AND p.active = TRUE"
            + "   AND p.starts_at <= ?"
            + "   AND (p.ends_at IS NULL OR p.ends_at > ?)"
            + "   AND (p.channel = ? OR p.channel = 'ALL')"
            + "   AND (p.store_id IS NULL"
            + (storeId != null ? " OR p.store_id = ?)" : ")")
            + " ORDER BY p.priority ASC, p.id ASC",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, toOdt(now));
          ps.setObject(i++, toOdt(now));
          ps.setString(i++, channel);
          if (storeId != null) ps.setObject(i, storeId);
        },
        this::mapPromotion,
        "find candidate promotions");
  }

  /**
   * The variants each of these promotions is scoped to.
   *
   * <p>A promotion with an ALL row, or with no scope rows at all, is absent from the result — the
   * engine reads a missing entry as "everything", so an unscoped promotion cannot accidentally
   * become a scoped-to-nothing one.
   *
   * <p><b>CATEGORY rows are deliberately not resolved here</b> and the service rejects creating
   * them: the variant→category mapping belongs to product-svc, which publishes no catalogue event
   * for pricing-svc to project (golden rule #1 forbids reading its tables). Until it does, a
   * CATEGORY promotion cannot be honoured — and the previous engine's answer to that was to accept
   * one, store it, and never fire it.
   */
  public Map<UUID, Set<UUID>> findPromotionVariantScopes(UUID tenantId, List<UUID> promotionIds) {
    if (promotionIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(promotionIds.size(), "?"));
    Map<UUID, Set<UUID>> out = new java.util.LinkedHashMap<>();
    Set<UUID> unscoped = new java.util.HashSet<>();
    query(
        "SELECT promotion_id, scope_type, scope_id FROM promotion_items"
            + " WHERE tenant_id = ? AND promotion_id IN ("
            + placeholders
            + ")",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          for (UUID id : promotionIds) ps.setObject(i++, id);
        },
        rs -> {
          UUID promo = rs.getObject("promotion_id", UUID.class);
          String scopeType = rs.getString("scope_type");
          UUID scopeId = rs.getObject("scope_id", UUID.class);
          if (PromotionItem.SCOPE_VARIANT.equals(scopeType) && scopeId != null) {
            out.computeIfAbsent(promo, k -> new java.util.LinkedHashSet<>()).add(scopeId);
          } else {
            // ALL (or a malformed row): this promotion is not variant-scoped at all.
            unscoped.add(promo);
          }
          return promo;
        },
        "find promotion scopes");
    // An ALL row beats any VARIANT rows alongside it — "everything" is not narrowed by also
    // naming a few things.
    for (UUID id : unscoped) out.remove(id);
    return out;
  }

  /**
   * Usage already spent per promotion, so the engine can reject an exhausted coupon with a reason
   * rather than skipping it silently.
   *
   * @param customerId the shopper, or null for a guest — a per-customer cap cannot bind on a caller
   *     with no identity, and pretending otherwise would cap every guest collectively
   * @return promotion id to a reason code, for the promotions that may no longer be used
   */
  public Map<UUID, String> findExhaustedPromotions(
      UUID tenantId, List<Promotion> candidates, UUID customerId) {
    Map<UUID, String> out = new java.util.LinkedHashMap<>();
    for (Promotion p : candidates) {
      if (p.maxRedemptions() != null) {
        long used = countRedemptions(tenantId, p.id(), null);
        if (used >= p.maxRedemptions()) {
          out.put(p.id(), "COUPON_EXHAUSTED");
          continue;
        }
      }
      if (p.maxPerCustomer() != null && customerId != null) {
        long mine = countRedemptions(tenantId, p.id(), customerId);
        if (mine >= p.maxPerCustomer()) out.put(p.id(), "COUPON_LIMIT_REACHED");
      }
    }
    return out;
  }

  private long countRedemptions(UUID tenantId, UUID promotionId, UUID customerId) {
    List<Long> n =
        query(
            "SELECT COUNT(*) AS n FROM promotion_redemptions"
                + " WHERE tenant_id = ? AND promotion_id = ?"
                + (customerId != null ? " AND customer_id = ?" : ""),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, promotionId);
              if (customerId != null) ps.setObject(3, customerId);
            },
            rs -> rs.getLong("n"),
            "count promotion redemptions");
    return n.isEmpty() ? 0L : n.get(0);
  }

  /**
   * Records that a promotion was used on an order.
   *
   * <p>Idempotent on {@code (tenant, promotion, order)} via a unique index: a retried checkout, or
   * an offline POS sale replaying its writes, must not burn a second use of a coupon. That is
   * SJ-D15's lesson applied before the defect rather than after it — the question is not whether
   * this code is correct but what a replay of it does.
   *
   * @return true if this call recorded the redemption, false if it had already been recorded
   */
  public boolean recordRedemption(
      UUID tenantId,
      UUID promotionId,
      UUID orderId,
      UUID customerId,
      java.math.BigDecimal amount,
      String currency) {
    try {
      exec(
          "INSERT INTO promotion_redemptions"
              + " (tenant_id, promotion_id, order_id, customer_id, amount, currency)"
              + " VALUES (?,?,?,?,?,?)"
              + " ON CONFLICT (tenant_id, promotion_id, order_id) DO NOTHING",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, promotionId);
            ps.setObject(3, orderId);
            ps.setObject(4, customerId);
            ps.setBigDecimal(5, amount);
            ps.setString(6, currency);
          },
          "record promotion redemption");
      return true;
    } catch (RuntimeException e) {
      // ON CONFLICT already makes this a no-op; the catch is for the race that beats it.
      return false;
    }
  }

  public List<Promotion> findAllActivePromotions(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,store_id,name,type,value,min_order_amount,"
            + "  channel,active,starts_at,ends_at,created_at,priority,exclusive,coupon_code,"
            + "  max_redemptions,max_per_customer,buy_qty,get_qty,get_discount_pct"
            + " FROM promotions WHERE tenant_id=? AND active=TRUE"
            + " ORDER BY priority ASC, starts_at DESC",
        ps -> ps.setObject(1, tenantId),
        this::mapPromotion,
        "list active promotions");
  }

  public PromotionItem addPromotionItem(PromotionItem pi) {
    exec(
        "INSERT INTO promotion_items (id,tenant_id,promotion_id,scope_type,scope_id)"
            + " VALUES (?,?,?,?,?)",
        ps -> {
          ps.setObject(1, pi.id());
          ps.setObject(2, pi.tenantId());
          ps.setObject(3, pi.promotionId());
          ps.setString(4, pi.scopeType());
          ps.setObject(5, pi.scopeId());
        },
        "add promotion item");
    return pi;
  }

  private Promotion mapPromotion(java.sql.ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime endsAt = rs.getObject("ends_at", OffsetDateTime.class);
    return new Promotion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("name"),
        rs.getString("type"),
        rs.getBigDecimal("value"),
        rs.getBigDecimal("min_order_amount"),
        rs.getString("channel"),
        rs.getBoolean("active"),
        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
        endsAt != null ? endsAt.toInstant() : null,
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getInt("priority"),
        rs.getBoolean("exclusive"),
        rs.getString("coupon_code"),
        intOrNull(rs, "max_redemptions"),
        intOrNull(rs, "max_per_customer"),
        rs.getBigDecimal("buy_qty"),
        rs.getBigDecimal("get_qty"),
        rs.getBigDecimal("get_discount_pct"));
  }

  /** getInt returns 0 for SQL NULL, and 0 is a meaningful cap. */
  private static Integer intOrNull(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    int v = rs.getInt(column);
    return rs.wasNull() ? null : v;
  }

  private static void setIntOrNull(java.sql.PreparedStatement ps, int index, Integer v)
      throws java.sql.SQLException {
    if (v == null) ps.setNull(index, java.sql.Types.INTEGER);
    else ps.setInt(index, v);
  }

  // ── Tax Transactions ──────────────────────────────────────────────────────

  public TaxTransaction recordTaxTransaction(TaxTransaction tt) {
    exec(
        "INSERT INTO tax_transactions"
            + " (id,tenant_id,order_id,order_line_id,variant_id,store_id,"
            + "  vat_code,vat_rate,net_amount,vat_amount,gross_amount,"
            + "  exempt,tax_point_date,invoice_ref)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, tt.id());
          ps.setObject(2, tt.tenantId());
          ps.setObject(3, tt.orderId());
          ps.setObject(4, tt.orderLineId());
          ps.setObject(5, tt.variantId());
          ps.setObject(6, tt.storeId());
          ps.setString(7, tt.vatCode());
          ps.setBigDecimal(8, tt.vatRate());
          ps.setBigDecimal(9, tt.netAmount());
          ps.setBigDecimal(10, tt.vatAmount());
          ps.setBigDecimal(11, tt.grossAmount());
          ps.setBoolean(12, tt.exempt());
          ps.setObject(13, toOdt(tt.taxPointDate()));
          ps.setString(14, tt.invoiceRef());
        },
        "record tax transaction");
    return tt;
  }

  public List<TaxTransaction> findTaxTransactionsByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id,tenant_id,order_id,order_line_id,variant_id,store_id,"
            + "  vat_code,vat_rate,net_amount,vat_amount,gross_amount,"
            + "  exempt,tax_point_date,invoice_ref,created_at"
            + " FROM tax_transactions WHERE tenant_id=? AND order_id=? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapTaxTransaction,
        "find tax transactions by order");
  }

  // ── VAT Return (MTD boxes 1–9) ────────────────────────────────────────────

  /**
   * Aggregate output VAT and net sales for MTD boxes. Box 1 = output VAT on taxable supplies. Box 6
   * = total net sales (all supplies). Both exclude nothing — even exempt supplies count for Box 6.
   * Per HMRC VAT Notice 700 s.17.
   */
  public BigDecimal sumOutputVat(UUID tenantId, Instant from, Instant to) {
    var rows =
        query(
            "SELECT COALESCE(SUM(vat_amount), 0) AS total"
                + " FROM tax_transactions"
                + " WHERE tenant_id=? AND NOT exempt"
                + "   AND tax_point_date >= ? AND tax_point_date < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, toOdt(from));
              ps.setObject(3, toOdt(to));
            },
            rs -> rs.getBigDecimal("total"),
            "sum output vat");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  public BigDecimal sumNetSales(UUID tenantId, Instant from, Instant to) {
    var rows =
        query(
            "SELECT COALESCE(SUM(net_amount), 0) AS total"
                + " FROM tax_transactions"
                + " WHERE tenant_id=? AND tax_point_date >= ? AND tax_point_date < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, toOdt(from));
              ps.setObject(3, toOdt(to));
            },
            rs -> rs.getBigDecimal("total"),
            "sum net sales");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
  }

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  public PriceOverride insertPriceOverride(PriceOverride p) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_overrides"
                      + " (id,tenant_id,order_id,variant_id,store_id,original_price,override_price,override_reason,overridden_by)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, p.id());
            ps.setObject(2, p.tenantId());
            ps.setObject(3, p.orderId());
            ps.setObject(4, p.variantId());
            ps.setObject(5, p.storeId());
            ps.setBigDecimal(6, p.originalPrice());
            ps.setBigDecimal(7, p.overridePrice());
            ps.setString(8, p.overrideReason());
            ps.setObject(9, p.overriddenBy());
            ps.executeUpdate();
          }
          return p;
        },
        "insert price override");
  }

  /**
   * Keyset page of price overrides (append-only audit log), newest first. {@code storeId} and
   * {@code variantId} filters can be combined. Replaces three previously separate branches, two of
   * which had no bound at all and one a hardcoded, non-paginated {@code LIMIT 200} that silently
   * dropped rows past it with no signal a further page existed.
   */
  public List<PriceOverride> listPriceOverrides(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, order_id, variant_id, store_id, original_price,"
                + " override_price, override_reason, overridden_by, created_at"
                + " FROM price_overrides WHERE tenant_id=?");
    if (storeId != null) sql.append(" AND store_id=?");
    if (variantId != null) sql.append(" AND variant_id=?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) < (?, ?)");
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        this::mapPriceOverride,
        "list price overrides");
  }

  private PriceOverride mapPriceOverride(java.sql.ResultSet rs) throws java.sql.SQLException {
    var orderId = rs.getObject("order_id", UUID.class);
    var overriddenBy = rs.getObject("overridden_by", UUID.class);
    return new PriceOverride(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        orderId,
        rs.getObject("variant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getBigDecimal("original_price"),
        rs.getBigDecimal("override_price"),
        rs.getString("override_reason"),
        overriddenBy,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOdt(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private TaxTransaction mapTaxTransaction(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new TaxTransaction(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("order_line_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("vat_code"),
        rs.getBigDecimal("vat_rate"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getBoolean("exempt"),
        rs.getObject("tax_point_date", OffsetDateTime.class).toInstant(),
        rs.getString("invoice_ref"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
