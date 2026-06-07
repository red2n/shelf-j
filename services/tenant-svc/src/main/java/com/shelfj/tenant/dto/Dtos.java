package com.shelfj.tenant.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response DTOs for tenant-svc. No tenant_id in requests — it comes from the JWT/context. */
public final class Dtos {

    private Dtos() {}

    // --- onboarding ---
    public record CreateTenantRequest(
            @NotBlank String businessName,
            String legalName,
            @NotBlank @Size(min = 2, max = 2) String country,   // ISO-3166 alpha-2
            @NotBlank @Size(min = 3, max = 3) String currency) {} // ISO-4217

    public record CreateStoreRequest(
            @NotBlank String name,
            @NotBlank String code,
            String type,                                         // STORE | WAREHOUSE (default STORE)
            String line1, String line2, String city, String state, String country, String pincode,
            java.math.BigDecimal geoLat, java.math.BigDecimal geoLng,
            String timezone, String businessHours) {}

    public record CreateZoneRequest(
            @NotBlank String name,
            @NotBlank String code,
            String type) {}                                      // AISLE|RACK|... (default AISLE)

    public record AssignStaffRequest(
            @NotBlank String userId,
            @NotBlank String storeId,
            @NotBlank String role) {}                            // MANAGER|STOREKEEPER|CASHIER

    // --- responses ---
    public record TenantResponse(String id, String name, String status, String country, String currency) {}

    public record StoreResponse(String id, String name, String code, String type, String status,
                                boolean isDefault, String timezone) {}

    public record ZoneResponse(String id, String storeId, String name, String code, String type, String status) {}

    public record OnboardingStatus(boolean tenantActive, boolean hasDefaultStore, List<String> nextSteps) {}
}
