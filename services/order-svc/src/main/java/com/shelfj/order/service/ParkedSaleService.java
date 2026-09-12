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

  /**
   * Parks an in-progress sale, totalling its lines and discounts as it stands.
   *
   * <p>No stock is committed — a parked sale is a draft, so nothing is held against it.
   *
   * @param tenantId owning tenant
   * @param cashierId the cashier parking it
   * @param req the store, customer and the basket rung so far
   * @return the parked sale with its computed subtotal and discount total
   * @throws ApiException {@code PARK_EMPTY} (400) when the sale has no items
   */
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
                  if (item.markdownId() != null && !item.markdownId().isBlank()) {
                    Parsing.uuid(item.markdownId(), "markdownId");
                  }
                  return new ParkedSaleItemResponse(
                      item.variantId(),
                      item.qty(),
                      item.unitPrice(),
                      discount,
                      lineTotal,
                      item.notes(),
                      item.markdownId() == null || item.markdownId().isBlank()
                          ? null
                          : item.markdownId());
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

  /**
   * Reads one parked sale, to resume it at the till.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale to read
   * @return the parked sale with its basket
   * @throws ApiException a 404 when no such parked sale exists in this tenant
   */
  public ParkedSaleResponse get(UUID tenantId, UUID saleId) {
    return repo.findById(tenantId, saleId);
  }

  /**
   * The tenant's still-open parked sales.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for the whole tenant
   * @return the open parked sales
   */
  public List<ParkedSaleResponse> list(UUID tenantId, UUID storeId) {
    return repo.listOpen(tenantId, storeId);
  }

  /**
   * Discards a parked sale without resuming it.
   *
   * <p>Nothing was sold and no stock was committed, so there is nothing to reverse.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale to discard
   */
  public void cancel(UUID tenantId, UUID saleId) {
    repo.cancel(tenantId, saleId);
  }

  /**
   * Records a cash-drawer open with no accompanying sale.
   *
   * <p>Audited because an unexplained drawer open is how cash leaves a till with no transaction to
   * show for it.
   *
   * @param tenantId owning tenant
   * @param cashierId the cashier who opened the drawer
   * @param req the store, till session and stated reason
   * @return the logged entry
   */
  public NoSaleResponse logNoSale(UUID tenantId, UUID cashierId, NoSaleRequest req) {
    UUID storeId = req.storeId() == null ? null : Parsing.uuid(req.storeId(), "storeId");
    UUID sessionId =
        req.tillSessionId() == null ? null : Parsing.uuid(req.tillSessionId(), "tillSessionId");
    return repo.logNoSale(tenantId, storeId, cashierId, sessionId, req.reason(), null);
  }
}
