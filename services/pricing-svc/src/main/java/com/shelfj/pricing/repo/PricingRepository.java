package com.shelfj.pricing.repo;

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
import java.util.Optional;
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

  public List<PriceList> findPriceLists(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,name,channel,currency,effective_from,effective_to,active,created_at"
            + " FROM price_lists WHERE tenant_id=? ORDER BY name",
        ps -> ps.setObject(1, tenantId),
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

  // ── Promotions ────────────────────────────────────────────────────────────

  public Promotion createPromotion(Promotion p, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO promotions"
                      + " (id,tenant_id,store_id,name,type,value,min_order_amount,"
                      + "  channel,active,starts_at,ends_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
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
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return p;
        },
        "create promotion");
  }

  public List<Promotion> findActivePromotions(
      UUID tenantId, UUID variantId, String channel, Instant now) {
    return query(
        "SELECT DISTINCT p.id, p.tenant_id, p.store_id, p.name, p.type, p.value,"
            + "  p.min_order_amount, p.channel, p.active, p.starts_at, p.ends_at, p.created_at"
            + " FROM promotions p"
            + " JOIN promotion_items pi ON pi.promotion_id = p.id"
            + " WHERE p.tenant_id = ?"
            + "   AND p.active = TRUE"
            + "   AND p.starts_at <= ?"
            + "   AND (p.ends_at IS NULL OR p.ends_at > ?)"
            + "   AND (p.channel = ? OR p.channel = 'ALL')"
            + "   AND (pi.scope_type = 'ALL'"
            + "        OR (pi.scope_type = 'VARIANT' AND pi.scope_id = ?))"
            + " ORDER BY p.value DESC"
            + " LIMIT 1",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, toOdt(now));
          ps.setObject(3, toOdt(now));
          ps.setString(4, channel);
          ps.setObject(5, variantId);
        },
        this::mapPromotion,
        "find active promotions");
  }

  public List<Promotion> findAllActivePromotions(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,store_id,name,type,value,min_order_amount,"
            + "  channel,active,starts_at,ends_at,created_at"
            + " FROM promotions WHERE tenant_id=? AND active=TRUE ORDER BY starts_at DESC",
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
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
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

  public List<PriceOverride> listPriceOverrides(UUID tenantId, UUID storeId, UUID variantId) {
    if (storeId != null) {
      return query(
          "SELECT id, tenant_id, order_id, variant_id, store_id, original_price,"
              + " override_price, override_reason, overridden_by, created_at"
              + " FROM price_overrides WHERE tenant_id=? AND store_id=? ORDER BY created_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
          },
          this::mapPriceOverride,
          "list price overrides by store");
    }
    if (variantId != null) {
      return query(
          "SELECT id, tenant_id, order_id, variant_id, store_id, original_price,"
              + " override_price, override_reason, overridden_by, created_at"
              + " FROM price_overrides WHERE tenant_id=? AND variant_id=? ORDER BY created_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
          },
          this::mapPriceOverride,
          "list price overrides by variant");
    }
    return query(
        "SELECT id, tenant_id, order_id, variant_id, store_id, original_price,"
            + " override_price, override_reason, overridden_by, created_at"
            + " FROM price_overrides WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
        ps -> ps.setObject(1, tenantId),
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
