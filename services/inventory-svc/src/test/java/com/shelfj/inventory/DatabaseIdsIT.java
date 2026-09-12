package com.shelfj.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Ids made inside inventory's schema rather than by {@code Ids.newId()}: the {@code uuid_v7()}
 * function behind the one set-based insert, the seed rows migrations write, and the guards that
 * stop a column — or a stored row — bringing another uuid version back. Real Postgres.
 */
@HelidonTest
class DatabaseIdsIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "inventory");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    System.setProperty("shelfj.inventory.food-safety.overdue-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-7014-8cd5-baf0862fa319";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── uuid_v7() ────────────────────────────────────────────────────────────────

  @Test
  void uuidV7MakesVersion7IdsCarryingTheCurrentTime() throws SQLException {
    long before = System.currentTimeMillis();
    UUID id = UUID.fromString(scalar("SELECT inventory.uuid_v7()::text"));
    long after = System.currentTimeMillis();

    assertEquals(7, id.version());
    assertEquals(2, id.variant());
    long millis = id.getMostSignificantBits() >>> 16;
    assertTrue(
        millis >= before - 1_000 && millis <= after + 1_000,
        "timestamp " + millis + " outside [" + before + ", " + after + "]");
  }

  /** A set-based insert calls it once per row inside one statement, often in one millisecond. */
  @Test
  void uuidV7NeverRepeatsAcrossOneStatement() throws SQLException {
    assertEquals(
        "100000",
        scalar("SELECT count(DISTINCT inventory.uuid_v7())::text FROM generate_series(1, 100000)"));
    assertEquals(
        "0",
        scalar(
            "SELECT count(*)::text FROM (SELECT inventory.uuid_v7() AS id"
                + " FROM generate_series(1, 10000)) s WHERE substring(id::text, 15, 1) <> '7'"));
  }

  @Test
  void demandHistoryBucketsGetVersion7Ids() throws SQLException {
    String store = Ids.newId().toString();
    String first = Ids.newId().toString();
    String second = Ids.newId().toString();
    for (String variant : List.of(first, second)) {
      receive(store, variant, "20");
      sell(store, variant, 3);
      sell(store, variant, 2);
    }

    Response aggregated =
        post(
            "/admin/inventory/demand/aggregate",
            "{\"storeId\":\"" + store + "\",\"bucketType\":\"DAY\"}");
    assertThat(aggregated.readEntity(String.class), aggregated.getStatus(), is(200));

    List<String> ids =
        column(
            "SELECT id::text FROM inventory.demand_history WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + store
                + "'");
    assertEquals(2, ids.size(), "one DAY bucket per variant");
    for (String id : ids) {
      assertEquals(7, UUID.fromString(id).version(), id);
    }
  }

  // ── seeds ────────────────────────────────────────────────────────────────────

  @Test
  void rowsTheMigrationsSeedCarryVersion7Ids() throws SQLException {
    for (String table :
        List.of("transaction_reason_codes", "transaction_source_types", "fs_check_types")) {
      assertTrue(
          Integer.parseInt(scalar("SELECT count(*)::text FROM inventory." + table)) > 0, table);
      assertEquals(
          "0",
          scalar(
              "SELECT count(*)::text FROM inventory."
                  + table
                  + " WHERE substring(id::text, 15, 1) <> '7'"),
          table);
    }
  }

  // ── guards ───────────────────────────────────────────────────────────────────

  @Test
  void noColumnInTheSchemaGeneratesItsOwnIds() throws SQLException {
    assertEquals(
        List.of(),
        column(
            "SELECT table_name || '.' || column_name FROM information_schema.columns"
                + " WHERE table_schema = 'inventory' AND column_default ILIKE '%uuid%'"));
  }

  /** The afterMigrate check, run against a column that would mint its own ids: it must refuse. */
  @Test
  void theMigrationGuardRefusesAColumnThatMintsItsOwnIds() throws Exception {
    String guard = afterMigrateSql();
    for (String generator : List.of("gen_random_uuid()", "inventory.uuid_v7()")) {
      try (Connection c = connect()) {
        c.setAutoCommit(false);
        try (var st = c.createStatement()) {
          st.execute("SET search_path TO inventory");
          st.execute("CREATE TABLE guard_probe (id UUID PRIMARY KEY DEFAULT " + generator + ")");
          SQLException refused = assertThrows(SQLException.class, () -> st.execute(guard));
          assertThat(refused.getMessage(), containsString("guard_probe.id"));
        } finally {
          c.rollback();
        }
      }
    }
    try (Connection c = connect();
        var st = c.createStatement()) {
      st.execute("SET search_path TO inventory");
      st.execute(guard);
    }
  }

  /**
   * The afterMigrate check as Flyway really runs it: discovered inside the common-service jar,
   * against the migrating schema. All of inventory's migrations plus one that gives a column a uuid
   * default must fail; the same migrations without it must succeed.
   */
  @Test
  void flywayFailsAMigrationThatLeavesAColumnMintingItsOwnIds() throws SQLException {
    String schema = "guard_probe_" + Ids.shortRef(Ids.newId());
    try {
      FlywayException refused =
          assertThrows(
              FlywayException.class,
              () -> migrate(schema, "classpath:db/migration", "classpath:db/guard-probe"));
      assertThat(refused.getMessage(), containsString("generate their own ids"));
      assertThat(refused.getMessage(), containsString("lot_actions.id"));
    } finally {
      exec("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    String clean = "guard_clean_" + Ids.shortRef(Ids.newId());
    try {
      migrate(clean, "classpath:db/migration");
    } finally {
      exec("DROP SCHEMA IF EXISTS " + clean + " CASCADE");
    }
  }

  /** Configured as common-service's FlywayRunner configures it. */
  private static void migrate(String schema, String... locations) {
    Flyway.configure()
        .dataSource(PG.jdbcUrl(), PG.username(), PG.password())
        .locations(locations)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .baselineOnMigrate(true)
        .load()
        .migrate();
  }

  /** The audit every integration test ends with must name a table that holds another version. */
  @Test
  void theEndOfTestAuditNamesATableHoldingANonV7Id() throws SQLException {
    UUID legacy = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
    exec(
        "INSERT INTO inventory.transaction_reason_codes (id, tenant_id, code, description)"
            + " VALUES ('"
            + legacy
            + "', '"
            + T
            + "', 'AUDIT_PROBE', 'v4 on purpose')");
    try {
      assertEquals(Long.valueOf(1), PG.nonV7Ids().get("inventory.transaction_reason_codes"));
    } finally {
      exec("DELETE FROM inventory.transaction_reason_codes WHERE id = '" + legacy + "'");
    }
    assertTrue(PG.nonV7Ids().isEmpty(), "clean again: " + PG.nonV7Ids());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private void receive(String store, String variant, String qty) {
    Response r =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"qty\":%s}".formatted(store, variant, qty));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private void sell(String store, String variant, int qty) {
    Response reserved =
        post(
            "/inventory/reservations",
            "{\"storeId\":\"%s\",\"variantId\":\"%s\",\"qty\":%d,\"orderId\":\"%s\"}"
                .formatted(store, variant, qty, Ids.newId()));
    String body = reserved.readEntity(String.class);
    assertThat(body, reserved.getStatus(), is(201));
    String id;
    try (var reader = Json.createReader(new StringReader(body))) {
      id = reader.readObject().getJsonObject("data").getString("id");
    }
    Response consumed = post("/inventory/reservations/" + id + "/consume", "");
    assertThat(consumed.readEntity(String.class), consumed.getStatus(), is(200));
  }

  private Response post(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .header("X-User-Id", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String afterMigrateSql() throws IOException {
    try (InputStream in =
        DatabaseIdsIT.class.getClassLoader().getResourceAsStream("db/migration/afterMigrate.sql")) {
      assertTrue(in != null, "afterMigrate.sql is on the classpath");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
  }

  private static String scalar(String sql) throws SQLException {
    List<String> values = column(sql);
    assertEquals(1, values.size(), sql);
    return values.get(0);
  }

  private static List<String> column(String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = connect();
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = connect();
        var st = c.createStatement()) {
      st.execute(sql);
    }
  }
}
