package com.storeql.order.mapper;

import com.storeql.order.domain.RecallNotice.Detail;
import com.storeql.order.domain.RecallNotice.Line;
import com.storeql.order.domain.RecallNotice.Notice;
import com.storeql.order.domain.RecallNotice.Progress;
import com.storeql.order.dto.RecallNoticeDtos.LineResponse;
import com.storeql.order.dto.RecallNoticeDtos.NoticeResponse;
import com.storeql.order.dto.RecallNoticeDtos.ProgressResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Recall notice domain objects to their DTOs. */
public final class RecallNoticeMappers {

  private RecallNoticeMappers() {}

  /**
   * Converts a notice to its wire form. The buyer's phone number never leaves the service.
   *
   * @param d the notice with its lines
   * @return its API representation
   */
  public static NoticeResponse toNotice(Detail d) {
    Notice n = d.notice();
    return new NoticeResponse(
        n.id().toString(),
        n.recallId().toString(),
        n.reference(),
        n.hazard(),
        n.reason(),
        n.customerNotice(),
        n.remedies().stream().map(Enum::name).sorted().toList(),
        n.singleRemedyReason(),
        n.contactPhone(),
        n.contactUrl(),
        n.orderId().toString(),
        n.storeId().toString(),
        n.channel(),
        str(n.customerId()),
        str(n.loginId()),
        n.buyerIdentified(),
        str(n.soldAt()),
        n.status().name(),
        str(n.issuedAt()),
        n.choice() == null ? null : n.choice().remedy().name(),
        n.choice() == null ? null : str(n.choice().at()),
        n.choice() == null ? null : n.choice().via().name(),
        n.settlement() == null ? null : n.settlement().resolution().name(),
        n.settlement() == null ? null : str(n.settlement().at()),
        n.settlement() == null ? null : str(n.settlement().returnId()),
        n.settlement() == null ? null : n.settlement().notes(),
        d.lines().stream().map(RecallNoticeMappers::toLine).toList());
  }

  private static LineResponse toLine(Line l) {
    return new LineResponse(
        l.variantId().toString(),
        l.productName(),
        l.sku(),
        l.batchNo(),
        str(l.expiryDate()),
        l.qty(),
        l.match());
  }

  /**
   * Converts a recall's buyer progress to its wire form.
   *
   * @param recallId the recall the figures describe
   * @param p the figures
   * @return its API representation
   */
  public static ProgressResponse toProgress(UUID recallId, Progress p) {
    Map<String, Integer> chosen = new LinkedHashMap<>();
    p.chosen().forEach((remedy, count) -> chosen.put(remedy.name(), count));
    return new ProgressResponse(
        recallId.toString(),
        p.notices(),
        p.identified(),
        p.unidentified(),
        p.remedyChosen(),
        p.resolved(),
        chosen);
  }

  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
