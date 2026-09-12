package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.CreateSupplierRequest;
import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
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

@RequestScoped
/**
 * Thin JAX-RS resource for supplier master records — validate, delegate to {@link PurchaseService},
 * wrap in envelope. No logic here.
 */
@Path("/suppliers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Suppliers")
public class SupplierResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Creates a supplier master record for the caller's tenant.
   *
   * @param req the supplier's name, VAT details, country, currency and payment terms
   * @return {@code 201} with the created supplier
   */
  @Operation(
      summary = "Create a supplier",
      description = "Creates a supplier master record for the caller's tenant.")
  @APIResponse(responseCode = "201", description = "Supplier created")
  @POST
  public Response create(CreateSupplierRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createSupplier(req, ctx))))
        .build();
  }

  /**
   * Lists suppliers for the caller's tenant.
   *
   * @param limit page size; clamped to the platform default and maximum when absent or out of range
   * @return the suppliers
   */
  @Operation(summary = "List suppliers", description = "Lists suppliers for the caller's tenant.")
  @APIResponse(responseCode = "200", description = "The suppliers")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.shelfj.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(svc.listSuppliers(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * Reads a single supplier.
   *
   * @param id the supplier to read
   * @return the supplier
   * @throws com.shelfj.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(summary = "Get a supplier", description = "Returns a single supplier.")
  @APIResponse(responseCode = "404", description = "Supplier not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getSupplier(ctx, id)))).build();
  }
}
