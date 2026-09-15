package com.shelfj.tenant.mapper;

import com.shelfj.tenant.domain.Retention.ClassStatus;
import com.shelfj.tenant.domain.Retention.Floor;
import com.shelfj.tenant.domain.Retention.Hold;
import com.shelfj.tenant.domain.Retention.Run;
import com.shelfj.tenant.domain.Retention.Schedule;
import com.shelfj.tenant.dto.RetentionDtos.ClassResponse;
import com.shelfj.tenant.dto.RetentionDtos.HoldResponse;
import com.shelfj.tenant.dto.RetentionDtos.RunResponse;
import com.shelfj.tenant.dto.RetentionDtos.SheetResponse;
import com.shelfj.tenant.service.RetentionService.Sheet;
import java.util.List;

/** Retention domain objects to their DTOs. */
public final class RetentionMappers {

  private RetentionMappers() {}

  public static SheetResponse toSheet(Sheet s) {
    return new SheetResponse(
        s.country(),
        List.copyOf(s.countries()),
        s.classes().stream().map(RetentionMappers::toClass).toList(),
        s.holds().stream().map(RetentionMappers::toHold).toList());
  }

  public static ClassResponse toClass(ClassStatus c) {
    Floor f = c.floor();
    Schedule s = c.schedule();
    return new ClassResponse(
        c.dataClass().code(),
        c.dataClass().name(),
        c.dataClass().purgeKind(),
        c.dataClass().purgedBy(),
        c.dataClass().description(),
        f == null ? null : f.minDays(),
        f == null ? null : f.scope(),
        f == null ? null : f.citation(),
        f == null ? null : f.summary(),
        s == null ? null : s.periodDays(),
        s == null ? null : s.setBy().toString(),
        s == null ? null : s.setAt().toString());
  }

  public static HoldResponse toHold(Hold h) {
    return new HoldResponse(
        h.id().toString(),
        h.dataClass(),
        h.subjectKind().name(),
        str(h.subjectId()),
        h.reason(),
        h.placedBy().toString(),
        h.placedAt().toString(),
        h.isActive(),
        str(h.releasedBy()),
        str(h.releasedAt()),
        h.releaseReason());
  }

  public static RunResponse toRun(Run r) {
    return new RunResponse(
        r.id().toString(),
        r.service(),
        r.dataClass(),
        r.cutoff().toString(),
        r.rowsAffected(),
        r.heldSkipped(),
        r.startedAt().toString(),
        r.finishedAt().toString(),
        str(r.recordedAt()));
  }

  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
