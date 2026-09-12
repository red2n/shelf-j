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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Assign HMRC VAT codes to product variants. */
@RequestScoped
@Path("/product-vat-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Product VAT Categories")
public class ProductVatCategoryResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Assigns a variant to a VAT code.
   *
   * <p>The code is checked against the tenant's own rates, so a typo cannot leave a product
   * pointing at a band that does not exist and silently falling back to standard rate at checkout.
   *
   * @param req the variant and the VAT code to assign it
   * @return the stored assignment
   * @throws com.shelfj.web.ApiException {@code 404} when the VAT code is not configured
   */
  @Operation(
      summary = "Assign a VAT category to a variant",
      description = "Sets the HMRC VAT code applied to a product variant's price resolution.")
  @APIResponse(responseCode = "200", description = "VAT category assigned")
  @APIResponse(responseCode = "404", description = "VAT code not found")
  @POST
  public Response upsert(UpsertProductVatCategoryRequest req) {
    Validations.validate(req);
    return Response.status(200)
        .entity(ApiResponse.ok(Mappers.toDto(svc.upsertProductVatCategory(req, ctx))))
        .build();
  }

  /**
   * Reads a variant's VAT assignment.
   *
   * <p>Reports absence as a 404, unlike price resolution, which quietly falls back to the standard
   * rate: the admin screen needs to know a product was never categorised.
   *
   * @param variantId the variant to look up
   * @return the assignment
   * @throws com.shelfj.web.ApiException {@code 404} when none is assigned
   */
  @Operation(
      summary = "Get a variant's VAT category",
      description = "Looks up the VAT code assigned to a product variant.")
  @APIResponse(responseCode = "200", description = "VAT category found")
  @APIResponse(responseCode = "404", description = "No VAT category assigned for this variant")
  @GET
  @Path("/{variantId}")
  public Response get(@PathParam("variantId") UUID variantId) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getProductVatCategory(ctx, variantId))))
        .build();
  }
}
