package com.shelfj.product.mapper;

import com.shelfj.product.domain.Merchandising.Fixture;
import com.shelfj.product.domain.Merchandising.Planogram;
import com.shelfj.product.domain.Merchandising.Position;
import com.shelfj.product.domain.Merchandising.Reset;
import com.shelfj.product.dto.MerchandisingDtos;
import com.shelfj.product.service.MerchandisingService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Merchandising on the wire (07.17). */
public final class MerchandisingMappers {

  private MerchandisingMappers() {}

  public static MerchandisingDtos.FixtureResponse toDto(Fixture f) {
    return new MerchandisingDtos.FixtureResponse(
        f.id().toString(),
        f.storeId().toString(),
        text(f.zoneId()),
        f.code(),
        f.name(),
        f.kind(),
        f.shelfCount(),
        f.shelfWidthMm(),
        f.totalWidthMm(),
        f.status(),
        text(f.createdAt()));
  }

  public static MerchandisingDtos.PositionResponse toDto(Position p) {
    return new MerchandisingDtos.PositionResponse(
        p.id() == null ? null : p.id().toString(),
        p.variantId().toString(),
        p.shelf(),
        p.sequence(),
        p.facings(),
        p.depth(),
        p.capacity(),
        p.minPresentation());
  }

  public static MerchandisingDtos.PlanogramResponse toDto(Planogram p) {
    return new MerchandisingDtos.PlanogramResponse(
        p.id().toString(),
        p.fixtureId().toString(),
        p.version(),
        p.status(),
        text(p.effectiveFrom()),
        p.note(),
        text(p.supersedes()),
        text(p.supersededBy()),
        p.totalCapacity(),
        text(p.createdAt()),
        text(p.publishedAt()),
        p.positions().stream().map(MerchandisingMappers::toDto).toList());
  }

  public static MerchandisingDtos.ShelfFitResponse toDto(MerchandisingService.ShelfFit f) {
    return new MerchandisingDtos.ShelfFitResponse(
        f.shelf(), f.usedMm(), f.availableMm(), f.unmeasured(), f.overflows());
  }

  /**
   * A reset, with lateness worked out for the day it is read.
   *
   * @param asOf the day the question is asked — passed in rather than taken from the clock here, so
   *     a caller asking "as of the reset week" gets that answer and not today's
   */
  public static MerchandisingDtos.ResetResponse toDto(Reset r, LocalDate asOf) {
    return new MerchandisingDtos.ResetResponse(
        r.id().toString(),
        r.categoryId().toString(),
        r.name(),
        text(r.scheduledFor()),
        r.status(),
        r.cancelledReason(),
        r.overdue(asOf),
        text(r.createdAt()),
        text(r.completedAt()),
        r.planogramIds().stream().map(UUID::toString).toList());
  }

  /**
   * A space line.
   *
   * <p>Shares and money go out as strings, like every other decimal on this platform: a JSON number
   * is a double by the time a browser has parsed it, and 0.0825 is a figure a buyer acts on.
   */
  public static MerchandisingDtos.SpaceLineResponse toDto(MerchandisingService.SpaceLine l) {
    return new MerchandisingDtos.SpaceLineResponse(
        l.categoryId().toString(),
        l.targetShare().toPlainString(),
        l.actualShare().toPlainString(),
        l.variance().toPlainString(),
        l.actualMm(),
        text(l.reviewOn()));
  }

  public static List<MerchandisingDtos.FixtureResponse> fixtures(List<Fixture> all) {
    return all.stream().map(MerchandisingMappers::toDto).toList();
  }

  public static List<MerchandisingDtos.PlanogramResponse> planograms(List<Planogram> all) {
    return all.stream().map(MerchandisingMappers::toDto).toList();
  }

  public static List<MerchandisingDtos.ShelfFitResponse> fits(
      List<MerchandisingService.ShelfFit> all) {
    return all.stream().map(MerchandisingMappers::toDto).toList();
  }

  public static List<MerchandisingDtos.SpaceLineResponse> spaceLines(
      List<MerchandisingService.SpaceLine> all) {
    return all.stream().map(MerchandisingMappers::toDto).toList();
  }

  public static List<MerchandisingDtos.ResetResponse> resets(List<Reset> all, LocalDate asOf) {
    return all.stream().map(r -> toDto(r, asOf)).toList();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(LocalDate day) {
    return day == null ? null : day.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }
}
