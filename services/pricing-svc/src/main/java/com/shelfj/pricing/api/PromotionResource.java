package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.AddPromotionItemRequest;
import com.shelfj.pricing.dto.Dtos.CreatePromotionRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Time-bounded promotional discounts (PERCENT or FLAT, scoped to ALL / variant / category). */
@RequestScoped
@Path("/promotions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Promotions")
public class PromotionResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a promotion",
      description =
          "Creates a time-bounded PERCENT or FLAT discount, optionally scoped to a store/channel.")
  @APIResponse(responseCode = "201", description = "Promotion created")
  @POST
  public Response create(CreatePromotionRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPromotion(req, ctx))))
        .build();
  }

  @Operation(
      summary = "List active promotions",
      description = "All currently-active promotions for the tenant.")
  @APIResponse(responseCode = "200", description = "List of active promotions")
  @GET
  public Response list() {
    return Response.ok(
            ApiResponse.ok(svc.listActivePromotions(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Add a scope item to a promotion",
      description = "Attaches the promotion to a scope (ALL, VARIANT, or CATEGORY) it applies to.")
  @APIResponse(responseCode = "201", description = "Promotion item added")
  @POST
  @Path("/{id}/items")
  public Response addItem(@PathParam("id") UUID id, AddPromotionItemRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPromotionItem(ctx, id, req))))
        .build();
  }
}
