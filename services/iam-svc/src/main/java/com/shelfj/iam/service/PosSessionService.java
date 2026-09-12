package com.shelfj.iam.service;

import com.shelfj.iam.domain.PosSession;
import com.shelfj.iam.dto.Dtos.StartPosSessionRequest;
import com.shelfj.iam.repo.PosSessionRepository;
import com.shelfj.ids.Ids;
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

  /**
   * Opens a POS session for the calling cashier at a store.
   *
   * @param ctx caller context; supplies the tenant and the cashier's user id
   * @param req the store and an optional idle timeout, defaulting to 900s
   * @return the newly opened session
   * @throws ApiException {@code POS_SESSION_INVALID_TIMEOUT} (400) when the timeout falls outside
   *     60..86400; {@code TENANT_NOT_OPERATIONAL} or {@code STORE_NOT_OPERATIONAL} (409) when the
   *     tenant or store is not trading
   */
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
            Ids.newId(),
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

  /**
   * Resets a session's idle clock, keeping it clear of {@link #sweepIdle()}.
   *
   * @param ctx caller context; supplies the tenant the session must belong to
   * @param sessionId the session to keep alive
   * @throws ApiException {@code POS_SESSION_NOT_FOUND} (404) when no such session exists in this
   *     tenant; {@code POS_SESSION_NOT_ACTIVE} (409) when it has already ended or expired
   */
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

  /**
   * Closes a POS session.
   *
   * <p>Unlike {@link #touch}, an already-closed session is not an error, so signing off twice is
   * safe.
   *
   * @param ctx caller context; supplies the tenant the session must belong to
   * @param sessionId the session to close
   * @throws ApiException {@code POS_SESSION_NOT_FOUND} (404) when no such session exists in this
   *     tenant
   */
  public void end(TenantContext ctx, UUID sessionId) {
    var session =
        repo.find(sessionId)
            .orElseThrow(() -> ApiException.notFound("POS_SESSION_NOT_FOUND", "session not found"));
    if (!session.tenantId().equals(ctx.requireTenantId()))
      throw ApiException.notFound("POS_SESSION_NOT_FOUND", "session not found");
    repo.end(sessionId);
  }

  /**
   * Lists every currently open POS session in the tenant, across all stores.
   *
   * @param ctx caller context; supplies the tenant
   * @return the active sessions
   */
  public List<PosSession> listActive(TenantContext ctx) {
    return repo.listActive(ctx.requireTenantId());
  }

  /**
   * Expires every session whose idle timeout has elapsed.
   *
   * <p>Runs across all tenants — it is driven by the scheduled sweeper, not by a request, so there
   * is no tenant in context to scope it to.
   *
   * @return how many sessions were expired
   */
  public int sweepIdle() {
    return repo.expireIdle();
  }
}
