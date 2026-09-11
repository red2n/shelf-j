package com.shelfj.pricing.mapper;

import com.shelfj.pricing.domain.Domain;
import com.shelfj.pricing.domain.Domain.CustomerVatStatus;
import com.shelfj.pricing.domain.Domain.PriceList;
import com.shelfj.pricing.domain.Domain.PriceListItem;
import com.shelfj.pricing.domain.Domain.PriceOverride;
import com.shelfj.pricing.domain.Domain.ProductVatCategory;
import com.shelfj.pricing.domain.Domain.Promotion;
import com.shelfj.pricing.domain.Domain.PromotionItem;
import com.shelfj.pricing.domain.Domain.ResolvedPrice;
import com.shelfj.pricing.domain.Domain.TaxSummary;
import com.shelfj.pricing.domain.Domain.TaxSummaryRow;
import com.shelfj.pricing.domain.Domain.TaxTransaction;
import com.shelfj.pricing.domain.Domain.VatRate;
import com.shelfj.pricing.domain.Domain.VatReturn;
import com.shelfj.pricing.dto.Dtos;
import com.shelfj.pricing.dto.Dtos.CustomerVatStatusResponse;
import com.shelfj.pricing.dto.Dtos.PriceListItemResponse;
import com.shelfj.pricing.dto.Dtos.PriceListResponse;
import com.shelfj.pricing.dto.Dtos.PriceOverrideResponse;
import com.shelfj.pricing.dto.Dtos.ProductVatCategoryResponse;
import com.shelfj.pricing.dto.Dtos.PromotionItemResponse;
import com.shelfj.pricing.dto.Dtos.PromotionResponse;
import com.shelfj.pricing.dto.Dtos.ResolvedPriceResponse;
import com.shelfj.pricing.dto.Dtos.TaxSummaryResponse;
import com.shelfj.pricing.dto.Dtos.TaxSummaryRowResponse;
import com.shelfj.pricing.dto.Dtos.TaxSummaryTotalsResponse;
import com.shelfj.pricing.dto.Dtos.TaxTransactionResponse;
import com.shelfj.pricing.dto.Dtos.VatRateResponse;
import com.shelfj.pricing.dto.Dtos.VatReturnResponse;

/** Domain → DTO conversions. No HTTP or persistence types. */
public final class Mappers {

  private Mappers() {}

  public static VatRateResponse toDto(VatRate r) {
    return new VatRateResponse(
        r.id(),
        r.tenantId(),
        r.code(),
        r.name(),
        r.rate(),
        r.exempt(),
        r.description(),
        r.effectiveFrom() != null ? r.effectiveFrom().toString() : null,
        r.effectiveTo() != null ? r.effectiveTo().toString() : null,
        r.createdAt() != null ? r.createdAt().toString() : null);
  }

  public static ProductVatCategoryResponse toDto(ProductVatCategory pvc) {
    return new ProductVatCategoryResponse(
        pvc.id(),
        pvc.tenantId(),
        pvc.variantId(),
        pvc.vatCode(),
        pvc.effectiveFrom() != null ? pvc.effectiveFrom().toString() : null,
        pvc.effectiveTo() != null ? pvc.effectiveTo().toString() : null,
        pvc.createdAt() != null ? pvc.createdAt().toString() : null);
  }

  public static CustomerVatStatusResponse toDto(CustomerVatStatus cvs) {
    return new CustomerVatStatusResponse(
        cvs.id(),
        cvs.tenantId(),
        cvs.customerId(),
        cvs.vatNumber(),
        cvs.vatRegistered(),
        cvs.reverseChargeEligible(),
        cvs.countryCode(),
        cvs.createdAt() != null ? cvs.createdAt().toString() : null,
        cvs.updatedAt() != null ? cvs.updatedAt().toString() : null);
  }

  public static PriceListResponse toDto(PriceList pl) {
    return new PriceListResponse(
        pl.id(),
        pl.tenantId(),
        pl.name(),
        pl.channel(),
        pl.currency(),
        pl.effectiveFrom() != null ? pl.effectiveFrom().toString() : null,
        pl.effectiveTo() != null ? pl.effectiveTo().toString() : null,
        pl.active(),
        pl.createdAt() != null ? pl.createdAt().toString() : null);
  }

  public static PriceListItemResponse toDto(PriceListItem item) {
    return new PriceListItemResponse(
        item.id(),
        item.tenantId(),
        item.priceListId(),
        item.variantId(),
        item.price(),
        item.minQty(),
        item.createdAt() != null ? item.createdAt().toString() : null,
        item.updatedAt() != null ? item.updatedAt().toString() : null);
  }

  public static ResolvedPriceResponse toDto(ResolvedPrice rp) {
    return new ResolvedPriceResponse(
        rp.variantId(),
        rp.unitPrice(),
        rp.vatCode(),
        rp.vatRate(),
        rp.vatAmount(),
        rp.totalWithVat(),
        rp.currency(),
        rp.priceListId(),
        rp.promotionApplied());
  }

  public static PromotionResponse toDto(Promotion p) {
    return new PromotionResponse(
        p.id(),
        p.tenantId(),
        p.storeId(),
        p.name(),
        p.type(),
        p.value(),
        p.minOrderAmount(),
        p.channel(),
        p.active(),
        p.startsAt() != null ? p.startsAt().toString() : null,
        p.endsAt() != null ? p.endsAt().toString() : null,
        p.createdAt() != null ? p.createdAt().toString() : null,
        p.priority(),
        p.exclusive(),
        p.couponCode(),
        p.maxRedemptions(),
        p.maxPerCustomer(),
        p.buyQty(),
        p.getQty(),
        p.getDiscountPct());
  }

  public static PromotionItemResponse toDto(PromotionItem pi) {
    return new PromotionItemResponse(
        pi.id(),
        pi.tenantId(),
        pi.promotionId(),
        pi.scopeType(),
        pi.scopeId(),
        pi.createdAt() != null ? pi.createdAt().toString() : null);
  }

  public static TaxTransactionResponse toDto(TaxTransaction tt) {
    return new TaxTransactionResponse(
        tt.id(),
        tt.tenantId(),
        tt.orderId(),
        tt.orderLineId(),
        tt.variantId(),
        tt.storeId(),
        tt.vatCode(),
        tt.vatRate(),
        tt.netAmount(),
        tt.vatAmount(),
        tt.grossAmount(),
        tt.exempt(),
        tt.taxPointDate() != null ? tt.taxPointDate().toString() : null,
        tt.invoiceRef(),
        tt.createdAt() != null ? tt.createdAt().toString() : null);
  }

  public static VatReturnResponse toDto(VatReturn vr) {
    return new VatReturnResponse(
        vr.box1(),
        vr.box2(),
        vr.box3(),
        vr.box4(),
        vr.box5(),
        vr.box6(),
        vr.box7(),
        vr.box8(),
        vr.box9(),
        vr.periodFrom(),
        vr.periodTo());
  }

  public static PriceOverrideResponse toDto(PriceOverride p) {
    return new PriceOverrideResponse(
        p.id() != null ? p.id().toString() : null,
        p.orderId() != null ? p.orderId().toString() : null,
        p.variantId() != null ? p.variantId().toString() : null,
        p.storeId() != null ? p.storeId().toString() : null,
        p.originalPrice(),
        p.overridePrice(),
        p.overrideReason(),
        p.overriddenBy() != null ? p.overriddenBy().toString() : null,
        p.createdAt() != null ? p.createdAt().toString() : null);
  }

  public static TaxSummaryResponse toDto(TaxSummary ts) {
    return new TaxSummaryResponse(
        ts.rows().stream().map(Mappers::toDto).toList(),
        new TaxSummaryTotalsResponse(
            ts.totals().netAmount(),
            ts.totals().vatAmount(),
            ts.totals().outputVat(),
            ts.totals().grossAmount(),
            ts.totals().transactions()),
        ts.periodFrom(),
        ts.periodTo());
  }

  public static TaxSummaryRowResponse toDto(TaxSummaryRow r) {
    return new TaxSummaryRowResponse(
        r.groupKey(), r.exempt(), r.netAmount(), r.vatAmount(), r.grossAmount(), r.transactions());
  }

  public static Dtos.StatusChangeResponse toDto(Domain.StatusChange c) {
    return new Dtos.StatusChangeResponse(
        c.id(),
        c.subjectType(),
        c.subjectId(),
        c.active(),
        c.reason(),
        c.changedBy(),
        c.changedAt());
  }
}
