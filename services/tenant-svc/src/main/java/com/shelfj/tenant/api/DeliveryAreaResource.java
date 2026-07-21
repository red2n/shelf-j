package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.CreateDeliveryAreaRequest;
import com.shelfj.tenant.dto.Dtos.DeliveryAreaResponse;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Admin CRUD for pincode → store delivery coverage. */
@RequestScoped
@Path("/admin/stores/{storeId}/delivery-areas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Delivery Areas")
public class DeliveryAreaResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "List delivery areas for a store",
      description = "Pincodes this store fulfils for home delivery, lowest priority first.")
  @APIResponse(responseCode = "200", description = "Delivery areas")
  @APIResponse(responseCode = "404", description = "Store not found")
  @GET
  public ApiResponse<List<DeliveryAreaResponse>> list(@PathParam("storeId") UUID storeId) {
    return ApiResponse.ok(service.listDeliveryAreas(ctx.requireTenantId(), storeId));
  }

  @Operation(
      summary = "Add a delivery area",
      description =
          "Maps a pincode to this store. Lower priority wins when multiple stores cover it.")
  @APIResponse(responseCode = "201", description = "Delivery area created")
  @APIResponse(responseCode = "404", description = "Store not found")
  @APIResponse(responseCode = "409", description = "Store already covers this pincode")
  @POST
  public Response create(@PathParam("storeId") UUID storeId, CreateDeliveryAreaRequest req) {
    Validations.validate(req);
    var area = service.addDeliveryArea(ctx.requireTenantId(), storeId, req);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(area)).build();
  }

  @Operation(
      summary = "Remove a delivery area",
      description = "Stops this store covering the pincode.")
  @APIResponse(responseCode = "204", description = "Removed")
  @APIResponse(responseCode = "404", description = "Delivery area not found")
  @DELETE
  @Path("/{areaId}")
  public Response delete(@PathParam("storeId") UUID storeId, @PathParam("areaId") UUID areaId) {
    service.deleteDeliveryArea(ctx.requireTenantId(), storeId, areaId);
    return Response.noContent().build();
  }
}
