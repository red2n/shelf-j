package com.shelfj.notification.api;

import com.shelfj.ids.Ids;
import com.shelfj.notification.dto.Dtos.SendNotificationRequest;
import com.shelfj.notification.dto.Dtos.SendNotificationResponse;
import com.shelfj.notification.service.Notifier;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Staff-triggered notification send (POS email receipt, manual resend, etc.). Not under {@code
 * /admin/} so cashiers (staff but not management) can call it; {@link
 * com.shelfj.web.AdminAuthorizationFilter} still requires any staff role on POST.
 * Service-to-service callers (order-svc) forward the cashier's identity headers.
 */
@Path("/notifications")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Send")
public class SendResource {

  @Inject Notifier notifier;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Send a notification",
      description =
          "Delivers one notification via the configured channel (APP log or SMTP). Idempotent on"
              + " (eventId, type) when eventId is supplied. Requires a staff role.")
  @APIResponse(responseCode = "202", description = "Accepted (sent or already delivered)")
  @APIResponse(responseCode = "400", description = "Validation failed")
  @APIResponse(responseCode = "403", description = "Caller is not staff")
  @POST
  @Path("/send")
  public Response send(SendNotificationRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID eventId =
        req.eventId() != null && !req.eventId().isBlank()
            ? UUID.fromString(req.eventId())
            : Ids.newId();
    String type = req.type() == null || req.type().isBlank() ? "MANUAL" : req.type().trim();
    UUID customerId =
        req.customerId() != null && !req.customerId().isBlank()
            ? UUID.fromString(req.customerId())
            : null;
    notifier.notifyOnce(
        eventId, type, tenantId, customerId, req.recipient(), req.subject(), req.body());
    return Response.accepted()
        .entity(ApiResponse.ok(new SendNotificationResponse(eventId.toString(), type, "SENT")))
        .build();
  }
}
