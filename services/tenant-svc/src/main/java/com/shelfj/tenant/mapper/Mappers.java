package com.shelfj.tenant.mapper;

import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.Zone;
import com.shelfj.tenant.dto.Dtos.StoreResponse;
import com.shelfj.tenant.dto.Dtos.TenantResponse;
import com.shelfj.tenant.dto.Dtos.ZoneResponse;

/** Entity → DTO conversion (never expose entities over HTTP). */
public final class Mappers {

    private Mappers() {}

    public static TenantResponse toTenant(Tenant t) {
        return new TenantResponse(t.id().toString(), t.name(), t.status(), t.country(), t.currency());
    }

    public static StoreResponse toStore(Store s) {
        return new StoreResponse(s.id().toString(), s.name(), s.code(), s.type(), s.status(), s.isDefault(), s.timezone());
    }

    public static ZoneResponse toZone(Zone z) {
        return new ZoneResponse(z.id().toString(), z.storeId().toString(), z.name(), z.code(), z.type(), z.status());
    }
}
