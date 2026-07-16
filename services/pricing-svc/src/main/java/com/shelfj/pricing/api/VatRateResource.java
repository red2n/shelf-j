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

  @Operation(summary = "List VAT rates", description = "All VAT rates configured for the tenant.")
  @APIResponse(responseCode = "200", description = "List of VAT rates")
  @GET
  public Response list() {
    return Response.ok(ApiResponse.ok(svc.listVatRates(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "Get a VAT rate by code", description = "Looks up a VAT rate by its code.")
  @APIResponse(responseCode = "200", description = "VAT rate found")
  @APIResponse(responseCode = "404", description = "VAT code not found")
  @GET
  @Path("/{code}")
  public Response get(@PathParam("code") String code) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getVatRate(ctx, code)))).build();
  }

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
