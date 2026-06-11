package com.shelfj.iam.service;

import com.shelfj.iam.domain.PosSession;
import com.shelfj.iam.dto.Dtos.StartPosSessionRequest;
import com.shelfj.iam.repo.PosSessionRepository;
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

  public PosSession start(TenantContext ctx, StartPosSessionRequest req) {
    int timeout = req.idleTimeoutSeconds() != null ? req.idleTimeoutSeconds() : 900;
    if (timeout < 60 || timeout > 86400)
      throw ApiException.badRequest(
          "POS_SESSION_INVALID_TIMEOUT", "idleTimeoutSeconds must be 60–86400");
    var session =
        new PosSession(
            UUID.randomUUID(),
            ctx.requireTenantId(),
            ctx.userId(),
            UUID.fromString(req.storeId()),
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
