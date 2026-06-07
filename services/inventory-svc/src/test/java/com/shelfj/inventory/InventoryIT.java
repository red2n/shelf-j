package com.shelfj.inventory;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration test for inventory against real Postgres (Testcontainers): receive two batches, reserve, over-reserve
 * (422), consume with FIFO deduction, tenant isolation. Kafka/Consul disabled.
 */
@HelidonTest
class InventoryIT {

    private static final PostgresSupport PG;

    static {
        PG = PostgresSupport.start();
        System.setProperty("shelfj.db.url", PG.jdbcUrl());
        System.setProperty("shelfj.db.user", PG.username());
        System.setProperty("shelfj.db.password", PG.password());
        System.setProperty("shelfj.db.schema", "inventory");
        System.setProperty("shelfj.consul.enabled", "false");
        System.setProperty("shelfj.kafka.enabled", "false");
    }

    private static final String T = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "99999999-9999-9999-9999-999999999999";
    private static final String S = "22222222-2222-2222-2222-222222222222";
    private static final String V = "33333333-3333-3333-3333-333333333333";

    @Inject
    WebTarget target;

    @AfterAll
    static void stopDb() {
        PG.stop();
    }

    private Response post(String path, String json, String tenant) {
        return target.path(path).request().header("X-Tenant-Id", tenant)
                .post(Entity.entity(json, MediaType.APPLICATION_JSON));
    }

    private String get(String path, String tenant) {
        return target.path(path).request().header("X-Tenant-Id", tenant).get(String.class);
    }

    @Test
    void receiveReserveConsumeFifoAndIsolation() {
        // two batches: A (earlier expiry, 10) then B (later, 5)
        assertThat(post("/admin/inventory/receive",
                "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V + "\",\"qty\":10,\"batchNo\":\"A\",\"expiryDate\":\"2026-01-01\"}", T).getStatus(), is(201));
        assertThat(post("/admin/inventory/receive",
                "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V + "\",\"qty\":5,\"batchNo\":\"B\",\"expiryDate\":\"2027-01-01\"}", T).getStatus(), is(201));

        // levels: onHand 15, available 15
        assertThat(get("/admin/inventory/levels", T), containsString("\"available\":15"));

        // reserve 12 → available 3
        Response r = post("/inventory/reservations",
                "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V + "\",\"qty\":12}", T);
        assertThat(r.getStatus(), is(201));
        String reservationId = field(r.readEntity(String.class), "id");
        assertThat(get("/admin/inventory/levels", T), containsString("\"available\":3"));

        // over-reserve (5 > 3) → 422
        Response over = post("/inventory/reservations",
                "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V + "\",\"qty\":5}", T);
        assertThat(over.getStatus(), is(422));
        assertThat(over.readEntity(String.class), containsString("INSUFFICIENT_STOCK"));

        // consume (FIFO: A drains, B reduced) → onHand 3
        Response consume = post("/inventory/reservations/" + reservationId + "/consume", "", T);
        assertThat(consume.getStatus(), is(200));
        String levels = get("/admin/inventory/levels", T);
        assertThat(levels, containsString("\"onHand\":3"));

        // tenant isolation
        assertThat(get("/admin/inventory/levels", OTHER), not(containsString(V)));
    }

    private static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) throw new AssertionError(name + " not in " + json);
        int start = i + key.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
