package com.shelfj.order.api;

import com.shelfj.order.domain.EInvoiceTransports.Transmission;
import com.shelfj.order.dto.EInvoiceTransportDtos.SetTransportRequest;
import com.shelfj.order.mapper.EInvoiceTransportMappers;
import com.shelfj.order.service.EInvoiceTransportService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Where a business's e-invoices leave, and each attempt to send one (the transport seam behind
 * 07.13 and 18.9). Management-only, as everything under {@code /admin} is.
 */
@RequestScoped
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "E-invoice transport")
public class EInvoiceTransportResource {

  static final int MAX_PAGE = 100;

  @Inject EInvoiceTransportService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The network the business sends its e-invoices on",
      description =
          "NONE (documents are issued and downloaded, never sent), PEPPOL, FR_PDP, KSEF or IRP,"
              + " with the provider behind it: SIMULATED on every network (the platform stands in"
              + " for the network; nothing leaves it), or a real provider once the deployment"
              + " holds its credentials. Also what the business's country asks for today.")
  @APIResponse(responseCode = "200", description = "The settings")
  @GET
  @Path("/einvoicing/transport")
  public Response settings() {
    return Response.ok(
            ApiResponse.ok(EInvoiceTransportMappers.toDto(svc.settings(ctx.requireTenantId()))))
        .build();
  }

  @Operation(
      summary = "What stands between this business and its first e-invoice",
      description =
          "Every condition that must hold, checked — and the network **asked**, not assumed. A key that"
              + " was right last month and a network that is up are different facts from a field being"
              + " filled in, and only one of them can be established by reading the database. Nothing"
              + " is sent: the network is asked whether it knows us, which is the only question a"
              + " check can answer honestly. Built for the day a provider contract lands, so a shop"
              + " presses one button instead of learning from the first invoice that does not arrive."
              + " `ready` is true when every condition holds and the network answered.")
  @APIResponse(
      responseCode = "200",
      description = "The conditions, and what asking the network came to")
  @GET
  @Path("/einvoicing/readiness")
  public Response readiness() {
    var r = svc.readiness(ctx.requireTenantId());
    return Response.ok(ApiResponse.ok(EInvoiceTransportMappers.toDto(r))).build();
  }

  @Operation(
      summary = "Choose the network and the provider",
      description =
          "Documents issued from now on are queued and sent; those already issued can be sent by"
              + " hand. Peppol needs the business's own electronic address on the tenant.")
  @APIResponse(responseCode = "200", description = "The settings as they now stand")
  @APIResponse(
      responseCode = "400",
      description = "An unknown network or provider, or a provider not named")
  @APIResponse(
      responseCode = "409",
      description =
          "A provider this deployment has no credentials for, or Peppol without a sender address")
  @PUT
  @Path("/einvoicing/transport")
  public Response setSettings(SetTransportRequest req) {
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                EInvoiceTransportMappers.toDto(
                    svc.setSettings(
                        ctx.requireTenantId(),
                        new EInvoiceTransportService.SettingsChange(
                            req.network(),
                            req.provider(),
                            req.providerAccount(),
                            req.providerSecret()),
                        ctx.userId()))))
        .build();
  }

  @Operation(summary = "Every attempt to send a document", description = "Newest first.")
  @APIResponse(responseCode = "200", description = "The attempts")
  @APIResponse(responseCode = "404", description = "No such document")
  @GET
  @Path("/sales-invoices/{id}/transmissions")
  public Response ofInvoice(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.of(ctx.requireTenantId(), id).stream()
                    .map(EInvoiceTransportMappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(
      summary = "Send a document now",
      description =
          "The first time, or again after the network refused it or could not be reached. The"
              + " answer is what the network said: delivered, refused with the reason, or taken and"
              + " awaiting the receiver.")
  @APIResponse(responseCode = "200", description = "The attempt, with the network's answer")
  @APIResponse(responseCode = "404", description = "No such document")
  @APIResponse(
      responseCode = "409",
      description =
          "No network chosen; the document already taken, on its way or delivered; the buyer"
              + " without an address; the provider no longer deployed")
  @POST
  @Path("/sales-invoices/{id}/transmissions")
  public Response send(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                EInvoiceTransportMappers.toDto(svc.send(ctx.requireTenantId(), id, ctx.userId()))))
        .build();
  }

  @Operation(
      summary = "The outbox: every attempt, newest first",
      description = "status filters; pass meta.nextCursor back as after.")
  @APIResponse(responseCode = "200", description = "One page")
  @APIResponse(responseCode = "400", description = "A status that does not exist, or a bad cursor")
  @GET
  @Path("/einvoicing/transmissions")
  public Response list(
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    int size = Math.max(1, Math.min(limit, MAX_PAGE));
    List<Transmission> page =
        svc.list(ctx.requireTenantId(), status, Parsing.optionalUuid(after, "after"), size);
    String next = page.size() == size ? page.get(page.size() - 1).id().toString() : null;
    return Response.ok(
            ApiResponse.ok(
                page.stream().map(EInvoiceTransportMappers::toDto).toList(),
                new ApiResponse.Meta(ctx.requestId(), next)))
        .build();
  }
}
