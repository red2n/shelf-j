package com.shelfj.order.service;

import com.shelfj.ids.Ids;
import com.shelfj.order.dto.Dtos.NoSaleRequest;
import com.shelfj.order.dto.Dtos.NoSaleResponse;
import com.shelfj.order.dto.Dtos.ParkSaleRequest;
import com.shelfj.order.dto.Dtos.ParkedSaleItemResponse;
import com.shelfj.order.dto.Dtos.ParkedSaleResponse;
import com.shelfj.order.repo.ParkedSaleRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parked (suspended) sales and no-sale/open-drawer audit. A parked sale holds line items in a draft
 * state so the cashier can serve the next customer and resume the original sale later. Parked sales
 * are not inventory-committed.
 */
@ApplicationScoped
public class ParkedSaleService {

  @Inject ParkedSaleRepository repo;

  public ParkedSaleResponse park(UUID tenantId, UUID cashierId, ParkSaleRequest req) {
    if (req.items() == null || req.items().isEmpty()) {
      throw new ApiException(400, "PARK_EMPTY", "Cannot park a sale with no items", List.of());
    }
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    UUID saleId = Ids.newId();

    List<ParkedSaleItemResponse> items =
        req.items().stream()
            .map(
                item -> {
                  BigDecimal discount =
                      item.discountAmount() == null ? BigDecimal.ZERO : item.discountAmount();
                  BigDecimal lineTotal = item.unitPrice().multiply(item.qty()).subtract(discount);
                  return new ParkedSaleItemResponse(
                      item.variantId(),
                      item.qty(),
                      item.unitPrice(),
                      discount,
                      lineTotal,
                      item.notes());
                })
            .collect(Collectors.toList());

    BigDecimal subtotal =
        items.stream()
            .map(ParkedSaleItemResponse::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal discountTotal =
        items.stream()
            .map(i -> i.discountAmount() == null ? BigDecimal.ZERO : i.discountAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return repo.park(
        tenantId,
        saleId,
        cashierId,
        storeId,
        req.customerId(),
        req.customerName(),
        subtotal,
        discountTotal,
        req.notes(),
        items);
  }

  public ParkedSaleResponse get(UUID tenantId, UUID saleId) {
    return repo.findById(tenantId, saleId);
  }

  public List<ParkedSaleResponse> list(UUID tenantId, UUID storeId) {
    return repo.listOpen(tenantId, storeId);
  }

  public void cancel(UUID tenantId, UUID saleId) {
    repo.cancel(tenantId, saleId);
  }

  public NoSaleResponse logNoSale(UUID tenantId, UUID cashierId, NoSaleRequest req) {
    UUID storeId = req.storeId() == null ? null : Parsing.uuid(req.storeId(), "storeId");
    UUID sessionId =
        req.tillSessionId() == null ? null : Parsing.uuid(req.tillSessionId(), "tillSessionId");
    return repo.logNoSale(tenantId, storeId, cashierId, sessionId, req.reason(), null);
  }
}
