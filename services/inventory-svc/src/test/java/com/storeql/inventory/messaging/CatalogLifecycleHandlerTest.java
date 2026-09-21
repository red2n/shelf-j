package com.storeql.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.inventory.repo.CatalogLinesOutRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lines-out projection follows product-svc's lifecycle events (item lifecycle): discontinued
 * and delisted take a product's variants out of replenishment, launched and reinstated bring them
 * back; a payload without the variants is skipped, and a malformed one is skipped, not thrown.
 */
class CatalogLifecycleHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID PRODUCT = Ids.newId();
  private static final UUID V1 = Ids.newId();
  private static final UUID V2 = Ids.newId();

  static final class CapturingLinesOut extends CatalogLinesOutRepository {
    final List<String> recorded = new ArrayList<>();

    @Override
    public void markOut(
        UUID tenantId, UUID productId, List<UUID> variantIds, String status, Instant at) {
      recorded.add("OUT " + status + " " + productId + " " + variantIds);
    }

    @Override
    public void markIn(UUID tenantId, UUID productId) {
      recorded.add("IN " + productId);
    }
  }

  private CapturingLinesOut lines;
  private CatalogLifecycleHandler handler;

  @BeforeEach
  void setUp() {
    lines = new CapturingLinesOut();
    handler = new CatalogLifecycleHandler();
    handler.linesOut = lines;
  }

  private static String event(String type, String status, String variants) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + TENANT
        + "\",\"aggregateId\":\""
        + PRODUCT
        + "\",\"status\":\""
        + status
        + "\""
        + (variants == null ? "" : ",\"variantIds\":" + variants)
        + "}";
  }

  @Test
  void discontinuedTakesTheVariantsOutAndReinstatedBringsThemBack() {
    handler.handle(event("ProductDiscontinued", "DISCONTINUED", "[\"" + V1 + "\",\"" + V2 + "\"]"));
    handler.handle(event("ProductReinstated", "ACTIVE", "[\"" + V1 + "\",\"" + V2 + "\"]"));
    assertEquals(2, lines.recorded.size());
    assertEquals(
        "OUT DISCONTINUED " + PRODUCT + " [" + V1 + ", " + V2 + "]", lines.recorded.get(0));
    assertEquals("IN " + PRODUCT, lines.recorded.get(1));
  }

  @Test
  void delistedWithVariantsIsOutAndWithoutThemIsSkipped() {
    handler.handle(event("ProductDelisted", "DELISTED", "[\"" + V1 + "\"]"));
    handler.handle(event("ProductDelisted", "DELISTED", null));
    assertEquals(1, lines.recorded.size());
    assertTrue(lines.recorded.get(0).startsWith("OUT DELISTED"));
  }

  @Test
  void launchedBringsThemBackAndOtherEventsAndJunkAreIgnored() {
    handler.handle(event("ProductLaunched", "ACTIVE", "[]"));
    handler.handle(event("ProductUpdated", "ACTIVE", null));
    handler.handle("{\"eventType\":\"ProductDiscontinued\",\"tenantId\":\"not-a-uuid\"}");
    handler.handle("not json");
    assertEquals(List.of("IN " + PRODUCT), lines.recorded);
  }
}
