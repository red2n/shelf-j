package com.shelfj.iam.service;

import com.shelfj.iam.domain.PosSession;
import com.shelfj.iam.dto.Dtos.StartPosSessionRequest;
import com.shelfj.iam.repo.PosSessionRepository;
import com.shelfj.service.StoreStatusRepository;
import com.shelfj.service.TenantStatusRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Gap #45 — POS session idle timeout management. */
@ApplicationScoped
public class PosSessionService {

  @Inject PosSessionRepository repo;
  @Inject TenantStatusRepository tenantStatusRepo;
  @Inject StoreStatusRepository storeStatusRepo;

  public PosSession start(TenantContext ctx, StartPosSessionRequest req) {
    int timeout = req.idleTimeoutSeconds() != null ? req.idleTimeoutSeconds() : 900;
    if (timeout < 60 || timeout > 86400)
      throw ApiException.badRequest(
          "POS_SESSION_INVALID_TIMEOUT", "idleTimeoutSeconds must be 60–86400");
    UUID storeId = UUID.fromString(req.storeId());
    UUID tenantId = ctx.requireTenantId();
    // Checked in addition to store status: tenant suspension in tenant-svc cascades to the
    // stores' own status column locally, but does not fan out a StoreStatusChanged event per
    // store, so this projection's store_status row can still read ACTIVE after a suspension.
    if (!tenantStatusRepo.isActive(tenantId))
      throw ApiException.conflict(
          "TENANT_NOT_OPERATIONAL",
          "Tenant is suspended or blocked — POS sessions are unavailable");
    if (!storeStatusRepo.isActive(tenantId, storeId))
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL",
          "Store is not accepting new sessions — it is closed or suspended");
    var session =
        new PosSession(
            UUID.randomUUID(),
            tenantId,
            ctx.userId(),
            storeId,
            Instant.now(),
            Instant.now(),
            null,
            timeout,
            PosSession.STATUS_ACTIVE);
    return repo.insert(session);
  }

  public void touch(TenantContext ctx, UUID sessionId) {
    var session =
        repo.find(sessionId)
            .orElseThrow(() -> ApiException.notFound("POS_SESSION_NOT_FOUND", "session not found"));
    if (!session.tenantId().equals(ctx.requireTenantId()))
      throw ApiException.notFound("POS_SESSION_NOT_FOUND", "session not found");
    if (!PosSession.STATUS_ACTIVE.equals(session.status()))
      throw ApiException.conflict("POS_SESSION_NOT_ACTIVE", "session is not active");
    repo.touch(sessionId);
  }

  public void end(TenantContext ctx, UUID sessionId) {
    var session =
        repo.find(sessionId)
            .orElseThrow(() -> ApiException.notFound("POS_SESSION_NOT_FOUND", "session not found"));
    if (!session.tenantId().equals(ctx.requireTenantId()))
      throw ApiException.notFound("POS_SESSION_NOT_FOUND", "session not found");
    repo.end(sessionId);
  }

  public List<PosSession> listActive(TenantContext ctx) {
    return repo.listActive(ctx.requireTenantId());
  }

  public int sweepIdle() {
    return repo.expireIdle();
  }
}
