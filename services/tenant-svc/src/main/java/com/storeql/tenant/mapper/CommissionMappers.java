package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Commission.Assignment;
import com.storeql.tenant.domain.Commission.Band;
import com.storeql.tenant.domain.Commission.Earned;
import com.storeql.tenant.domain.Commission.Scheme;
import com.storeql.tenant.domain.Commission.Segment;
import com.storeql.tenant.dto.CommissionDtos.AssignmentResponse;
import com.storeql.tenant.dto.CommissionDtos.BandResponse;
import com.storeql.tenant.dto.CommissionDtos.EarnedResponse;
import com.storeql.tenant.dto.CommissionDtos.RatedResponse;
import com.storeql.tenant.dto.CommissionDtos.SchemeResponse;
import com.storeql.tenant.dto.CommissionDtos.SegmentResponse;
import com.storeql.tenant.service.CommissionService.Rated;
import java.util.Map;

/**
 * Commission arrangements on the wire.
 *
 * <p>Every figure goes out as a string, as money does everywhere in this platform: a rate of 2.50
 * and a threshold of 10000.00 read back as themselves, where a JSON number would arrive as a double
 * and a statement would disagree with itself by a penny.
 */
public final class CommissionMappers {

  private CommissionMappers() {}

  public static SchemeResponse toDto(Scheme s) {
    return new SchemeResponse(
        s.id().toString(),
        s.name(),
        s.basis(),
        s.currency(),
        s.status(),
        s.note(),
        text(s.supersedes()),
        text(s.supersededBy()),
        s.bands().stream().map(CommissionMappers::toDto).toList(),
        s.createdAt().toString());
  }

  public static BandResponse toDto(Band b) {
    return new BandResponse(b.thresholdFrom().toPlainString(), b.rate().toPlainString());
  }

  public static AssignmentResponse toDto(Assignment a) {
    return new AssignmentResponse(
        a.id().toString(),
        a.userId().toString(),
        text(a.schemeId()),
        a.effectiveFrom().toString(),
        a.note(),
        a.createdAt().toString());
  }

  /**
   * What a person earned, with each segment naming the scheme it earned under.
   *
   * @param names scheme names by id, so a statement reads as words rather than as identifiers
   */
  public static RatedResponse toDto(Rated r, Map<String, String> names) {
    return new RatedResponse(
        r.userId().toString(),
        r.segments().stream().map(s -> toDto(s, names)).toList(),
        r.commission().toPlainString(),
        r.currency());
  }

  private static SegmentResponse toDto(Segment s, Map<String, String> names) {
    String id = text(s.schemeId());
    return new SegmentResponse(
        id,
        id == null ? null : names.get(id),
        s.from().toString(),
        s.to().toString(),
        s.amount().toPlainString(),
        s.earned().stream().map(CommissionMappers::toDto).toList(),
        s.commission().toPlainString());
  }

  private static EarnedResponse toDto(Earned e) {
    return new EarnedResponse(
        e.thresholdFrom().toPlainString(),
        e.rate().toPlainString(),
        e.amountInBand().toPlainString(),
        e.commission().toPlainString());
  }

  private static String text(java.util.UUID id) {
    return id == null ? null : id.toString();
  }
}
