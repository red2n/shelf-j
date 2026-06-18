package com.shelfj.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.shelfj.customer.domain.Domain.Customer;
import com.shelfj.customer.domain.Domain.LoyaltyAccount;
import com.shelfj.customer.domain.Domain.StoreCreditAccount;
import com.shelfj.customer.dto.Dtos.AdjustPointsRequest;
import com.shelfj.customer.dto.Dtos.IssueStoreCreditRequest;
import com.shelfj.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.shelfj.customer.repo.CustomerRepository;
import com.shelfj.service.OutboxRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Gap #76 — adjustPoints/issueStoreCredit/redeemStoreCredit must publish an outbox event like every
 * other write in this service, so reporting-svc/notification-svc can observe loyalty adjustments
 * and store-credit money movement.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceEventsTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CUSTOMER = UUID.randomUUID();

  @Mock CustomerRepository repo;
  private CustomerService service;

  @BeforeEach
  void setUp() {
    service = new CustomerService();
    service.repo = repo;
    when(repo.findById(eq(TENANT), eq(CUSTOMER)))
        .thenReturn(
            Optional.of(
                new Customer(
                    CUSTOMER,
                    TENANT,
                    "a@example.com",
                    null,
                    "A",
                    "B",
                    null,
                    null,
                    Customer.STATUS_ACTIVE,
                    null,
                    null,
                    Instant.now(),
                    Instant.now())));
  }

  @Test
  void adjustPointsPublishesLoyaltyAdjustedEvent() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.adjustPoints(eq(TENANT), eq(CUSTOMER), any(), anyString(), captor.capture()))
        .thenReturn(
            new LoyaltyAccount(
                UUID.randomUUID(),
                TENANT,
                CUSTOMER,
                BigDecimal.TEN,
                BigDecimal.TEN,
                LoyaltyAccount.TIER_BRONZE,
                Instant.now(),
                Instant.now()));

    service.adjustPoints(TENANT, CUSTOMER, new AdjustPointsRequest(BigDecimal.TEN, "manual"));

    OutboxRow event = captor.getValue();
    assertNotNull(event);
    assertEquals("LoyaltyAdjusted", event.eventType());
    assertEquals("shelfj.customer.loyalty-adjusted", event.topic());
    assertEquals(CUSTOMER, event.aggregateId());
  }

  @Test
  void issueStoreCreditPublishesStoreCreditIssuedEvent() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.issueStoreCredit(
            eq(TENANT), eq(CUSTOMER), any(), anyString(), any(), any(), captor.capture()))
        .thenReturn(
            new StoreCreditAccount(
                UUID.randomUUID(),
                TENANT,
                CUSTOMER,
                BigDecimal.TEN,
                "GBP",
                Instant.now(),
                Instant.now()));

    service.issueStoreCredit(
        TENANT, CUSTOMER, new IssueStoreCreditRequest(BigDecimal.TEN, "GBP", null, "refund"));

    OutboxRow event = captor.getValue();
    assertNotNull(event);
    assertEquals("StoreCreditIssued", event.eventType());
    assertEquals("shelfj.customer.store-credit-issued", event.topic());
  }

  @Test
  void redeemStoreCreditPublishesStoreCreditRedeemedEvent() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.redeemStoreCredit(
            eq(TENANT), eq(CUSTOMER), any(), anyString(), any(), any(), captor.capture()))
        .thenReturn(
            new StoreCreditAccount(
                UUID.randomUUID(),
                TENANT,
                CUSTOMER,
                BigDecimal.ZERO,
                "GBP",
                Instant.now(),
                Instant.now()));

    service.redeemStoreCredit(
        TENANT, CUSTOMER, new RedeemStoreCreditRequest(BigDecimal.TEN, "GBP", null, "purchase"));

    OutboxRow event = captor.getValue();
    assertNotNull(event);
    assertEquals("StoreCreditRedeemed", event.eventType());
    assertEquals("shelfj.customer.store-credit-redeemed", event.topic());
  }
}
