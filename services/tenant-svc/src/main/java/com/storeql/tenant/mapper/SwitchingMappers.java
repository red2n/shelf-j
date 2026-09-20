package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Switching.Evidence;
import com.storeql.tenant.domain.Switching.Switch;
import com.storeql.tenant.dto.SwitchingDtos.EvidenceResponse;
import com.storeql.tenant.dto.SwitchingDtos.NoticeResponse;
import com.storeql.tenant.dto.SwitchingDtos.StatusResponse;
import com.storeql.tenant.service.SwitchingService.Status;
import jakarta.json.Json;
import java.io.StringReader;
import java.util.Objects;

/** Domain to wire for a business leaving (21.14). */
public final class SwitchingMappers {

  private SwitchingMappers() {}

  public static StatusResponse toStatus(Status s) {
    return new StatusResponse(
        toNotice(s.notice()),
        s.stage().name(),
        s.evidence().stream().map(SwitchingMappers::toEvidence).toList(),
        s.services(),
        s.awaiting());
  }

  static NoticeResponse toNotice(Switch s) {
    return new NoticeResponse(
        s.id().toString(),
        s.intent(),
        text(s.noticeGivenAt()),
        text(s.noticeGivenBy()),
        text(s.noticeEndsOn()),
        text(s.transitionEndsOn()),
        text(s.extendedAt()),
        text(s.retrievalEndsOn()),
        text(s.erasureDueOn()),
        text(s.cancelledAt()),
        s.cancelReason(),
        text(s.erasureStartedAt()));
  }

  static EvidenceResponse toEvidence(Evidence e) {
    try (var reader = Json.createReader(new StringReader(e.tables()))) {
      return new EvidenceResponse(
          e.service(),
          e.rowsErased(),
          text(e.erasedAt()),
          text(e.recordedAt()),
          reader.readObject());
    }
  }

  private static String text(Object o) {
    return Objects.toString(o, null);
  }
}
