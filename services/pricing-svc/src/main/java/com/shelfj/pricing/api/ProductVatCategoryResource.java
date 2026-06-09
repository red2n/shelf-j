package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.UpsertProductVatCategoryRequest;
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

/** Assign HMRC VAT codes to product variants. */
@RequestScoped
@Path("/product-vat-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductVatCategoryResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @POST
  public Response upsert(UpsertProductVatCategoryRequest req) {
    Validations.validate(req);
    return Response.status(200)
        .entity(ApiResponse.ok(Mappers.toDto(svc.upsertProductVatCategory(req, ctx))))
        .build();
  }

  @GET
  @Path("/{variantId}")
  public Response get(@PathParam("variantId") UUID variantId) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getProductVatCategory(ctx, variantId))))
        .build();
  }
}
