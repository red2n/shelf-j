package com.shelfj.inventory.mapper;

import com.shelfj.inventory.domain.FoodSafety.CheckType;
import com.shelfj.inventory.domain.FoodSafety.CorrectiveAction;
import com.shelfj.inventory.domain.FoodSafety.DiaryEntry;
import com.shelfj.inventory.domain.FoodSafety.MonitoringPoint;
import com.shelfj.inventory.domain.FoodSafety.PointStatus;
import com.shelfj.inventory.domain.FoodSafety.Review;
import com.shelfj.inventory.dto.FoodSafetyDtos.CheckRecordResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.CheckTypeResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.CorrectiveActionResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.PointResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.ReviewResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Food-safety domain objects to their DTOs. */
public final class FoodSafetyMappers {

  private FoodSafetyMappers() {}

  public static CheckTypeResponse toCheckType(CheckType t) {
    return new CheckTypeResponse(
        t.id().toString(),
        t.code(),
        t.name(),
        t.kind().name(),
        t.limits().min(),
        t.limits().max(),
        t.unit(),
        t.basis(),
        t.statutory(),
        t.isPlatformType(),
        t.active());
  }

  public static PointResponse toPoint(PointStatus s, Instant now) {
    MonitoringPoint p = s.point();
    return new PointResponse(
        p.id().toString(),
        p.storeId().toString(),
        str(p.zoneId()),
        p.name(),
        toCheckType(s.type()),
        p.limits().min(),
        p.limits().max(),
        p.frequencyHours(),
        p.active(),
        ts(s.lastRecordedAt()),
        s.lastResult() == null ? null : s.lastResult().name(),
        s.lastValue(),
        ts(s.nextDueAt()),
        s.dueStatus(now).name(),
        s.openFailures());
  }

  public static CheckRecordResponse toRecord(
      DiaryEntry e, List<CorrectiveAction> correctiveActions) {
    var r = e.record();
    return new CheckRecordResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.pointId().toString(),
        e.pointName(),
        e.checkTypeCode(),
        e.checkTypeName(),
        r.kind().name(),
        r.value(),
        r.unit(),
        r.limits().min(),
        r.limits().max(),
        r.result().name(),
        r.notes(),
        r.refType(),
        str(r.refId()),
        r.recordedBy().toString(),
        ts(r.recordedAt()),
        correctiveActions == null ? e.actions() : correctiveActions.size(),
        e.isOpenFailure(),
        correctiveActions == null
            ? null
            : correctiveActions.stream().map(FoodSafetyMappers::toCorrectiveAction).toList());
  }

  public static CorrectiveActionResponse toCorrectiveAction(CorrectiveAction a) {
    return new CorrectiveActionResponse(
        a.id().toString(),
        a.recordId().toString(),
        a.action(),
        a.foodDisposition().name(),
        a.recordedBy().toString(),
        ts(a.recordedAt()));
  }

  public static ReviewResponse toReview(Review r) {
    return new ReviewResponse(
        r.id().toString(),
        r.storeId().toString(),
        ts(r.periodFrom()),
        ts(r.periodTo()),
        r.counts().records(),
        r.counts().failures(),
        r.counts().openFailures(),
        r.notes(),
        r.reviewedBy().toString(),
        ts(r.reviewedAt()));
  }

  private static String str(UUID id) {
    return id == null ? null : id.toString();
  }

  private static String ts(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
