package com.shelfj.purchase;

import static com.shelfj.purchase.PurchaseFixtures.STORE_A;
import static com.shelfj.purchase.PurchaseFixtures.T;
import static com.shelfj.purchase.PurchaseFixtures.USER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The steps a payment run test takes through the API, as a manager and the owner would: suppliers,
 * invoices due, a run proposed by one manager and approved by another.
 */
final class PaymentRunSteps {

  static final String USER2 = "01a090ae-611e-7a2b-8c3d-4e5f60718293";
  static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  private final WebTarget target;

  PaymentRunSteps(WebTarget target) {
    this.target = target;
  }

  Invocation.Builder as(String pathAndQuery, String tenant, String role, String user) {
    return com.shelfj.test.WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", user)
        .header("X-Roles", role);
  }

  Response post(String path, String json, String role, String user) {
    return as(path, T, role, user).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  Response postXml(String path, String xml, String role, String user) {
    return as(path, T, role, user).post(Entity.entity(xml, "application/xml"));
  }

  Response put(String path, String json, String role, String user) {
    return as(path, T, role, user).put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  Response get(String pathAndQuery, String role, String user) {
    return as(pathAndQuery, T, role, user).get();
  }

  static void assertCode(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString(code));
  }

  /** The envelope's data of a response that must have succeeded with this status. */
  static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  /** A supplier in a currency with the given bank details, as the owner adds it. */
  String supplier(String name, String currency, String bank) {
    return data(
            post(
                "/suppliers",
                "{\"name\":\""
                    + name
                    + "\",\"currency\":\""
                    + currency
                    + "\",\"paymentTermsDays\":30,"
                    + bank
                    + "}",
                "OWNER",
                USER),
            201)
        .getString("id");
  }

  /** An order in the currency, received in full and invoiced as ordered, due 40 days ago. */
  void dueInvoice(String supplierId, String number, String currency, int qty, String unitPrice) {
    String poId =
        data(
                post(
                    "/purchase-orders",
                    "{\"supplierId\":\""
                        + supplierId
                        + "\",\"storeId\":\""
                        + STORE_A
                        + "\",\"currency\":\""
                        + currency
                        + "\"}",
                    "OWNER",
                    USER),
                201)
            .getString("id");
    data(
        post(
            "/purchase-orders/" + poId + "/lines",
            PurchaseFixtures.lineJson(qty, unitPrice),
            "OWNER",
            USER),
        201);
    data(post("/purchase-orders/" + poId + "/submit", "{}", "OWNER", USER), 200);
    data(post("/goods-receipts", PurchaseFixtures.receiptJson(poId, qty), "OWNER", USER), 201);
    JsonObject invoice =
        data(
            post(
                "/supplier-invoices",
                PurchaseFixtures.invoiceJson(
                    poId, number, TODAY.minusDays(40), qty, unitPrice, "0"),
                "OWNER",
                USER),
            201);
    assertThat(invoice.getString("status"), is("MATCHED"));
  }

  /** A run for what is due in the currency, proposed by one manager and approved by another. */
  JsonObject approvedRun(String currency, LocalDate paymentDate) {
    String id =
        data(
                post(
                    "/payment-runs",
                    "{\"payUpTo\":\""
                        + TODAY
                        + "\",\"paymentDate\":\""
                        + paymentDate
                        + "\",\"currency\":\""
                        + currency
                        + "\"}",
                    "MANAGER",
                    USER),
                201)
            .getString("id");
    return data(post("/payment-runs/" + id + "/approve", "{}", "MANAGER", USER2), 200);
  }

  /** Runs a call n times at once, released together, and returns each result. */
  static <R> List<R> inParallel(int n, Callable<R> call) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<R>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return call.call();
                }));
      }
      start.countDown();
      List<R> out = new ArrayList<>();
      for (Future<R> f : futures) out.add(f.get(120, TimeUnit.SECONDS));
      return out;
    } finally {
      pool.shutdownNow();
    }
  }
}
