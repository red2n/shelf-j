package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.CreateVatRateRequest;
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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** HMRC UK VAT rate management (T1/T5/T0/TX per VAT Notice 700). */
@RequestScoped
@Path("/vat-rates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "VAT Rates")
public class VatRateResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /**
   * Creates a VAT rate for the tenant.
   *
   * @param req the code, name, rate as a fraction (0.20 = 20%), exempt flag and effective date
   * @return the created rate
   * @throws com.shelfj.web.ApiException {@code 400} when the rate exceeds 1
   */
  @Operation(
      summary = "Create a VAT rate",
      description = "Registers a new VAT rate/code (e.g. T1 standard, T0 zero, T5 exempt).")
  @APIResponse(responseCode = "201", description = "VAT rate created")
  @APIResponse(responseCode = "400", description = "VAT rate is not between 0 and 1")
  @POST
  public Response create(CreateVatRateRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createVatRate(req, ctx))))
        .build();
  }

  /**
   * All VAT rates configured for the tenant.
   *
   * @return the configured rates
   */
  @Operation(summary = "List VAT rates", description = "All VAT rates configured for the tenant.")
  @APIResponse(responseCode = "200", description = "List of VAT rates")
  @GET
  public Response list() {
    return Response.ok(ApiResponse.ok(svc.listVatRates(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * Looks up one VAT rate by its code.
   *
   * @param code the VAT code, matched case-insensitively
   * @return the rate
   * @throws com.shelfj.web.ApiException {@code 404} when the code is not configured
   */
  @Operation(summary = "Get a VAT rate by code", description = "Looks up a VAT rate by its code.")
  @APIResponse(responseCode = "200", description = "VAT rate found")
  @APIResponse(responseCode = "404", description = "VAT code not found")
  @GET
  @Path("/{code}")
  public Response get(@PathParam("code") String code) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getVatRate(ctx, code)))).build();
  }

  /**
   * Updates a VAT rate in place.
   *
   * <p>Tax transactions already recorded keep the figures they were stamped with; only future ones
   * use the new value.
   *
   * @param code the VAT code to update
   * @param req the new name, rate, exempt flag and optional effective date
   * @return the updated rate
   * @throws com.shelfj.web.ApiException {@code 400} when the rate exceeds 1; {@code 404} when the
   *     code is not configured
   */
  @Operation(
      summary = "Update a VAT rate",
      description = "Updates the name, rate, exemption flag, description, or effective-from date.")
  @APIResponse(responseCode = "200", description = "VAT rate updated")
  @APIResponse(responseCode = "400", description = "VAT rate is not between 0 and 1")
  @APIResponse(responseCode = "404", description = "VAT code not found")
  @PUT
  @Path("/{code}")
  public Response update(@PathParam("code") String code, CreateVatRateRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.updateVatRate(ctx, code, req)))).build();
  }
}
