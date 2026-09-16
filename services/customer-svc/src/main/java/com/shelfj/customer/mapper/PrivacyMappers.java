package com.shelfj.customer.mapper;

import com.shelfj.customer.domain.Privacy;
import com.shelfj.customer.domain.Privacy.ConsentEntry;
import com.shelfj.customer.domain.Privacy.GuardianConsent;
import com.shelfj.customer.domain.Privacy.Intimation;
import com.shelfj.customer.domain.Privacy.Notice;
import com.shelfj.customer.domain.Privacy.PurposeConsent;
import com.shelfj.customer.domain.Privacy.Request;
import com.shelfj.customer.domain.Privacy.Settings;
import com.shelfj.customer.dto.PrivacyDtos.ConsentEntryResponse;
import com.shelfj.customer.dto.PrivacyDtos.ConsentsResponse;
import com.shelfj.customer.dto.PrivacyDtos.GuardianResponse;
import com.shelfj.customer.dto.PrivacyDtos.IntimationResponse;
import com.shelfj.customer.dto.PrivacyDtos.LanguageResponse;
import com.shelfj.customer.dto.PrivacyDtos.NoticeResponse;
import com.shelfj.customer.dto.PrivacyDtos.NoticeViewResponse;
import com.shelfj.customer.dto.PrivacyDtos.PurposeConsentResponse;
import com.shelfj.customer.dto.PrivacyDtos.PurposeResponse;
import com.shelfj.customer.dto.PrivacyDtos.RequestResponse;
import com.shelfj.customer.dto.PrivacyDtos.SettingsResponse;
import com.shelfj.customer.service.PrivacyService.ConsentsView;
import com.shelfj.customer.service.PrivacyService.NoticeView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Privacy records to their wire form (13.12). */
public final class PrivacyMappers {

  private PrivacyMappers() {}

  public static SettingsResponse toDto(Settings s) {
    return new SettingsResponse(
        s.grievanceName(),
        s.grievanceEmail(),
        s.grievancePhone(),
        s.grievanceAddress(),
        s.responseDays(),
        s.hasGrievanceContact(),
        ts(s.updatedAt()));
  }

  public static NoticeResponse toDto(Notice n) {
    return n == null
        ? null
        : new NoticeResponse(
            n.id().toString(),
            n.language(),
            Privacy.LANGUAGES.get(n.language()),
            n.version(),
            n.title(),
            n.body(),
            ts(n.publishedAt()));
  }

  public static List<PurposeResponse> purposes() {
    List<PurposeResponse> out = new ArrayList<>();
    for (String p : Privacy.PURPOSES) {
      out.add(new PurposeResponse(p, Privacy.PURPOSE_TEXT.get(p), Privacy.TRACKING.contains(p)));
    }
    return out;
  }

  public static NoticeViewResponse toDto(NoticeView v) {
    Set<String> published = new java.util.HashSet<>();
    for (Notice n : v.available()) published.add(n.language());
    List<LanguageResponse> languages = new ArrayList<>();
    for (String code : Privacy.LANGUAGE_ORDER) {
      languages.add(
          new LanguageResponse(code, Privacy.LANGUAGES.get(code), published.contains(code)));
    }
    return new NoticeViewResponse(
        v.requested(),
        v.served(),
        toDto(v.notice()),
        languages,
        purposes(),
        toDto(v.settings()),
        v.dpdp(),
        v.dpdpFrom() == null ? null : v.dpdpFrom().toString());
  }

  public static ConsentsResponse toDto(ConsentsView v) {
    Map<String, PurposeConsent> by = new HashMap<>();
    for (PurposeConsent c : v.consents()) by.put(c.purpose(), c);
    List<PurposeConsentResponse> consents = new ArrayList<>();
    for (String p : Privacy.PURPOSES) {
      PurposeConsent c = by.get(p);
      consents.add(
          new PurposeConsentResponse(
              p,
              Privacy.PURPOSE_TEXT.get(p),
              Privacy.TRACKING.contains(p),
              c != null && c.granted(),
              c == null ? null : c.noticeLanguage(),
              c == null ? null : c.noticeVersion(),
              c == null ? null : ts(c.updatedAt())));
    }
    GuardianConsent g = v.guardian().orElse(null);
    List<NoticeResponse> notices = new ArrayList<>();
    for (String code : Privacy.LANGUAGE_ORDER) {
      Notice n = v.notices().get(code);
      if (n != null) notices.add(toDto(n));
    }
    return new ConsentsResponse(consents, v.child(), toDto(g), !v.child() || g != null, notices);
  }

  public static GuardianResponse toDto(GuardianConsent g) {
    return g == null
        ? null
        : new GuardianResponse(
            g.guardianName(),
            g.verification(),
            g.reference(),
            ts(g.givenAt()),
            ts(g.withdrawnAt()),
            g.standing());
  }

  public static ConsentEntryResponse toDto(ConsentEntry e) {
    return new ConsentEntryResponse(
        e.id().toString(),
        e.purpose(),
        e.granted(),
        e.source(),
        e.noticeLanguage(),
        e.noticeVersion(),
        str(e.actorId()),
        ts(e.recordedAt()));
  }

  public static RequestResponse toDto(Request r, LocalDate today) {
    return new RequestResponse(
        r.id().toString(),
        r.customerId().toString(),
        r.kind(),
        r.detail(),
        r.nomineeName(),
        r.nomineeContact(),
        ts(r.openedAt()),
        r.dueOn().toString(),
        r.status(),
        r.overdue(today),
        r.resolution(),
        ts(r.resolvedAt()),
        str(r.resolvedBy()));
  }

  public static IntimationResponse toDto(Intimation i) {
    return new IntimationResponse(
        i.id().toString(),
        str(i.noticeId()),
        i.subject(),
        i.body(),
        ts(i.sentAt()),
        str(i.sentBy()),
        i.recipients(),
        i.failures());
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  private static String str(UUID u) {
    return u == null ? null : u.toString();
  }
}
