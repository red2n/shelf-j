package com.storeql.order.api;

import com.storeql.service.TenantDataResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/tenant-data}: what order-svc holds for the owner's business, its rows page by page,
 * and their import into a fresh business (21.14, EU Data Act ch.VI). The owner alone.
 */
@Path("/admin/tenant-data")
@ApplicationScoped
@Tag(name = "Tenant data")
public class TenantDataExportResource extends TenantDataResource {}
