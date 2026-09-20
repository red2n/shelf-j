package com.shelfj.tenant.mapper;

import com.shelfj.tenant.domain.StoreTasks.Day;
import com.shelfj.tenant.domain.StoreTasks.Instance;
import com.shelfj.tenant.domain.StoreTasks.InstanceItem;
import com.shelfj.tenant.domain.StoreTasks.Template;
import com.shelfj.tenant.domain.StoreTasks.TemplateItem;
import com.shelfj.tenant.dto.StoreTaskDtos;
import java.time.Instant;
import java.util.UUID;

/** A shop's lists and its day on the wire. */
public final class StoreTaskMappers {

  private StoreTaskMappers() {}

  public static StoreTaskDtos.TemplateResponse toDto(Template t) {
    return new StoreTaskDtos.TemplateResponse(
        t.id().toString(),
        text(t.storeId()),
        t.title(),
        t.instructions(),
        t.kind(),
        t.daysOfWeek().stream().sorted().toList(),
        t.dueTime().toString(),
        t.graceMinutes(),
        t.role(),
        t.required(),
        t.status(),
        t.checklist(),
        t.items().stream().map(StoreTaskMappers::toDto).toList(),
        text(t.createdAt()),
        text(t.withdrawnAt()));
  }

  private static StoreTaskDtos.LineResponse toDto(TemplateItem i) {
    return new StoreTaskDtos.LineResponse(i.position(), i.text(), i.required());
  }

  public static StoreTaskDtos.InstanceResponse toDto(Instance i) {
    return new StoreTaskDtos.InstanceResponse(
        i.id().toString(),
        i.storeId().toString(),
        i.templateId().toString(),
        i.businessDate().toString(),
        i.dueAt().toString(),
        i.status(),
        i.title(),
        i.kind(),
        i.role(),
        i.required(),
        i.late(),
        i.outstanding(),
        text(i.completedAt()),
        text(i.completedBy()),
        i.skippedReason(),
        i.note(),
        i.items().stream().map(StoreTaskMappers::toDto).toList());
  }

  private static StoreTaskDtos.ItemResponse toDto(InstanceItem i) {
    return new StoreTaskDtos.ItemResponse(
        i.position(), i.text(), i.required(), text(i.tickedAt()), text(i.tickedBy()));
  }

  public static StoreTaskDtos.DayResponse toDto(Day d) {
    return new StoreTaskDtos.DayResponse(
        d.businessDate().toString(),
        d.storeId().toString(),
        d.total(),
        d.required(),
        d.done(),
        d.late(),
        d.skipped(),
        d.missed(),
        d.open(),
        d.settled());
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }
}
