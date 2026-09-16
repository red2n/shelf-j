package com.shelfj.order.support;

import static com.shelfj.order.support.InvoicingStubs.data;
import static com.shelfj.order.support.InvoicingStubs.dataArray;
import static com.shelfj.order.support.InvoicingStubs.eventually;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.shelfj.ids.Ids;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The calls an invoicing test makes as a member of staff: a till sale, and the reads after it. */
public final class Till {

  private final WebTarget target;

  public Till(WebTarget target) {
    this.target = target;
  }

  public Response post(String path, String json, String tenant) {
    return postAs(path, json, tenant, "OWNER");
  }

  public Response postAs(String path, String json, String tenant, String role) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", role)
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  public Response put(String path, String json, String tenant, String role) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", role)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  public Response get(String path, String tenant, String... params) {
    return getAs(path, tenant, "OWNER", params);
  }

  public Response getAs(String path, String tenant, String role, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", role).get();
  }

  /** Places a till basket; the order id. */
  public String place(String basket, String tenant) {
    Response r = post("/orders", basket, tenant);
    JsonObject placed = data(r);
    assertThat(placed.toString(), r.getStatus(), is(201));
    return placed.getString("id");
  }

  /** Places and confirms a till sale: a completed sale, handed over, that can be invoiced. */
  public String sell(String basket, String tenant) {
    String id = place(basket, tenant);
    Response r = post("/orders/" + id + "/confirm", "{}", tenant);
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    return id;
  }

  public JsonArray documentsOf(String order, String tenant) {
    return dataArray(get("/admin/orders/" + order + "/invoices", tenant));
  }

  /** The documents a sale has once it has {@code n} of them: issuing runs in the background. */
  public JsonArray documentsOf(String order, String tenant, int n) {
    JsonArray docs =
        eventually(
            () -> {
              JsonArray d = documentsOf(order, tenant);
              return d.size() >= n ? d : null;
            });
    assertThat("documents of " + order, docs, notNullValue());
    return docs;
  }
}
