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

/** HMRC UK VAT rate management (T1/T5/T0/TX per VAT Notice 700). */
@RequestScoped
@Path("/vat-rates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VatRateResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @POST
  public Response create(CreateVatRateRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createVatRate(req, ctx))))
        .build();
  }

  @GET
  public Response list() {
    return Response.ok(ApiResponse.ok(svc.listVatRates(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @GET
  @Path("/{code}")
  public Response get(@PathParam("code") String code) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getVatRate(ctx, code)))).build();
  }

  @PUT
  @Path("/{code}")
  public Response update(@PathParam("code") String code, CreateVatRateRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.updateVatRate(ctx, code, req)))).build();
  }
}
