package com.storeql.pricing.messaging;

import com.storeql.ids.Ids;
import com.storeql.pricing.repo.PricingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Projects the catalogue product-svc announces (03.8) into what a category-scoped promotion needs:
 * which variants belong to which product, and where the product sits in the category tree.
 *
 * <p>{@code ProductCategorised} carries a product's category path and its variant ids; {@code
 * VariantCreated} names the product a new variant belongs to. Both are idempotent on the event id.
 * A malformed payload is logged and skipped, never retried into a loop.
 */
@ApplicationScoped
public class CatalogueEventHandler {

  private static final Logger LOG = System.getLogger(CatalogueEventHandler.class.getName());
  static final String CONSUMER = "pricing-svc/catalogue";
  static final java.util.Set<String> MEASURE_UNITS = java.util.Set.of("KG", "L", "M", "SQM", "EA");

  @Inject PricingRepository repo;
  @Inject com.storeql.pricing.service.AppliedPriceService appliedPrices;

  /**
   * @param json the payload
   * @return {@code true} when something was recorded now
   */
  public boolean handle(String json) {
    JsonObject obj;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Unreadable catalogue event skipped: " + e.getMessage());
      return false;
    }
    String type = obj.getString("eventType", "");
    try {
      return switch (type) {
        case "ProductCategorised" -> {
          UUID productId = Ids.parse(obj.getString("productId"));
          boolean done =
              repo.projectProductCategorisedOnce(
                  Ids.parse(obj.getString("eventId")),
                  CONSUMER,
                  Ids.parse(obj.getString("tenantId")),
                  productId,
                  ids(obj, "categoryPath"),
                  ids(obj, "variantIds"));
          if (done) LOG.log(Level.INFO, "Catalogue: product {0} categorised", productId);
          if (done) appliedPrices.catchUp(Ids.parse(obj.getString("tenantId")));
          yield done;
        }
        case "VariantCreated" -> {
          UUID variantId = Ids.parse(obj.getString("aggregateId"));
          boolean done =
              repo.projectVariantCreatedOnce(
                  Ids.parse(obj.getString("eventId")),
                  CONSUMER,
                  Ids.parse(obj.getString("tenantId")),
                  variantId,
                  Ids.parse(obj.getString("productId")));
          if (done) LOG.log(Level.INFO, "Catalogue: variant {0} placed", variantId);
          if (done) appliedPrices.catchUp(Ids.parse(obj.getString("tenantId")));
          yield done;
        }
        case "VariantMeasured" -> {
          // 03.13: the measure a unit price is computed from, or nulls when none is declared.
          UUID variantId = Ids.parse(obj.getString("aggregateId"));
          String unit = obj.isNull("unit") ? null : obj.getString("unit");
          java.math.BigDecimal quantity =
              obj.isNull("quantity") ? null : new java.math.BigDecimal(obj.getString("quantity"));
          long version = obj.getJsonNumber("version").longValueExact();
          if (version < 0
              || (unit == null) != (quantity == null)
              || unit != null && (!MEASURE_UNITS.contains(unit) || quantity.signum() <= 0)) {
            throw new IllegalArgumentException("measure " + unit + " " + quantity);
          }
          boolean done =
              repo.projectVariantMeasuredOnce(
                  Ids.parse(obj.getString("eventId")),
                  CONSUMER,
                  Ids.parse(obj.getString("tenantId")),
                  variantId,
                  Ids.parse(obj.getString("productId")),
                  obj.isNull("soldBy") ? null : obj.getString("soldBy"),
                  unit,
                  quantity,
                  version);
          if (done) LOG.log(Level.INFO, "Catalogue: variant {0} measured", variantId);
          if (done) appliedPrices.catchUp(Ids.parse(obj.getString("tenantId")));
          yield done;
        }
        default -> false;
      };
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed " + type + " skipped: " + e.getMessage());
      return false;
    }
  }

  private static List<UUID> ids(JsonObject obj, String field) {
    List<UUID> out = new ArrayList<>();
    if (obj.containsKey(field) && !obj.isNull(field)) {
      for (JsonString v : obj.getJsonArray(field).getValuesAs(JsonString.class)) {
        out.add(Ids.parse(v.getString()));
      }
    }
    return out;
  }
}
