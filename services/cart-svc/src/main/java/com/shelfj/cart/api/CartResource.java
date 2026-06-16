package com.shelfj.cart.api;

import com.shelfj.cart.dto.Dtos.AddItemRequest;
import com.shelfj.cart.dto.Dtos.CartItemResponse;
import com.shelfj.cart.dto.Dtos.CartResponse;
import com.shelfj.cart.dto.Dtos.CartViewResponse;
import com.shelfj.cart.dto.Dtos.CreateCartRequest;
import com.shelfj.cart.dto.Dtos.MergeCartRequest;
import com.shelfj.cart.dto.Dtos.UpdateItemQtyRequest;
import com.shelfj.cart.service.CartService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/** Thin JAX-RS resource — validate, delegate to CartService, wrap in envelope. No logic here. */
@Path("/cart")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CartResource {

  @Inject CartService service;
  @Inject TenantContext ctx;

  /** Create or get the caller's active cart. */
  @POST
  public ApiResponse<CartResponse> createOrGet(CreateCartRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.createOrGetCart(ctx, req));
  }

  /**
   * View the cart with its items. Identify the cart by cartId query param, session query param, or
   * the authenticated customer identity from the JWT.
   */
  @GET
  public ApiResponse<CartViewResponse> view(
      @QueryParam("cartId") String cartId, @QueryParam("session") String session) {
    return ApiResponse.ok(service.viewCart(ctx, cartId, session));
  }

  /** Add an item (or increment qty if the variant is already present). */
  @POST
  @Path("/items")
  public ApiResponse<CartItemResponse> addItem(AddItemRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.addItem(ctx, req));
  }

  /** Change the qty of an existing cart item. */
  @PUT
  @Path("/items/{itemId}")
  public ApiResponse<CartItemResponse> updateQty(
      @PathParam("itemId") UUID itemId, UpdateItemQtyRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.updateItemQty(ctx, itemId, req));
  }

  /** Remove an item from the cart. */
  @DELETE
  @Path("/items/{itemId}")
  public Response removeItem(
      @PathParam("itemId") UUID itemId, @QueryParam("cartId") String cartId) {
    service.removeItem(ctx, itemId, cartId);
    return Response.noContent().build();
  }

  /** Merge a guest cart (by sessionId) into the authenticated customer's cart. */
  @POST
  @Path("/merge")
  public ApiResponse<CartResponse> merge(MergeCartRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(service.mergeCart(ctx, req));
  }
}
