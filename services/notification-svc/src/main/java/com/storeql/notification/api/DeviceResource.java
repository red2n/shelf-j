package com.storeql.notification.api;

import com.storeql.notification.dto.Dtos.RegisterDeviceRequest;
import com.storeql.notification.mapper.Mappers;
import com.storeql.notification.service.NotificationService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

/**
 * The caller's own push devices (13.7). Keyed on the token's login: a shopper from a storefront or
 * a member of staff from their console registers the device in their hand, reads their own, removes
 * their own. Nothing here names another login, so nothing here can reach one.
 */
@Path("/notifications/devices")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Devices")
public class DeviceResource {

  @Inject NotificationService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Register the caller's device for push",
      description =
          "The same token registered again refreshes the device rather than adding one. At most"
              + " ten devices per login per shop.")
  @APIResponse(responseCode = "201", description = "Registered")
  @APIResponse(responseCode = "400", description = "Unknown platform, or a token of the wrong size")
  @APIResponse(responseCode = "401", description = "No authenticated login")
  @APIResponse(responseCode = "409", description = "The login already holds ten devices here")
  @POST
  public Response register(RegisterDeviceRequest req) {
    Validations.validate(req);
    var device =
        service.registerDevice(
            ctx.requireTenantId(), ctx.requireUserId(), req.platform(), req.token());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toDto(device), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(summary = "The caller's own devices", description = "Never the whole token.")
  @APIResponse(responseCode = "200", description = "The devices, empty when none")
  @GET
  public ApiResponse<?> mine() {
    var devices =
        service.listDevices(ctx.requireTenantId(), ctx.requireUserId()).stream()
            .map(Mappers::toDto)
            .toList();
    return ApiResponse.ok(devices, ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Remove one of the caller's own devices",
      description = "Another login's device is not found, not forbidden.")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "No such device of the caller's")
  @DELETE
  @Path("/{id}")
  public Response remove(@PathParam("id") UUID id) {
    service.deleteDevice(ctx.requireTenantId(), ctx.requireUserId(), id);
    return Response.noContent().build();
  }
}
