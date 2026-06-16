package com.shelfj.customer.mapper;

import com.shelfj.customer.domain.Domain.Customer;
import com.shelfj.customer.domain.Domain.CustomerAddress;
import com.shelfj.customer.domain.Domain.LoyaltyAccount;
import com.shelfj.customer.domain.Domain.LoyaltyLedgerEntry;
import com.shelfj.customer.domain.Domain.StoreCreditAccount;
import com.shelfj.customer.dto.Dtos.AddressResponse;
import com.shelfj.customer.dto.Dtos.CustomerResponse;
import com.shelfj.customer.dto.Dtos.LoyaltyAccountResponse;
import com.shelfj.customer.dto.Dtos.LoyaltyLedgerEntryResponse;
import com.shelfj.customer.dto.Dtos.StoreCreditAccountResponse;
import java.time.Instant;

/** Domain → DTO conversion. Never expose domain records directly over HTTP. */
public final class Mappers {

  private Mappers() {}

  public static CustomerResponse toCustomer(Customer c) {
    return new CustomerResponse(
        c.id().toString(),
        c.email(),
        c.phone(),
        c.firstName(),
        c.lastName(),
        c.dob() == null ? null : c.dob().toString(),
        c.gender(),
        c.status(),
        ts(c.gdprConsentAt()),
        ts(c.createdAt()),
        ts(c.updatedAt()));
  }

  public static AddressResponse toAddress(CustomerAddress a) {
    return new AddressResponse(
        a.id().toString(),
        a.customerId().toString(),
        a.type(),
        a.line1(),
        a.line2(),
        a.city(),
        a.state(),
        a.country(),
        a.pincode(),
        a.isDefault(),
        ts(a.createdAt()));
  }

  public static LoyaltyAccountResponse toLoyalty(LoyaltyAccount la) {
    return new LoyaltyAccountResponse(
        la.customerId().toString(), la.pointsBalance(), la.lifetimePoints(), la.tier());
  }

  public static LoyaltyLedgerEntryResponse toLedgerEntry(LoyaltyLedgerEntry e) {
    return new LoyaltyLedgerEntryResponse(
        e.id().toString(),
        e.type(),
        e.points(),
        e.balanceAfter(),
        e.orderId() == null ? null : e.orderId().toString(),
        e.reason(),
        ts(e.createdAt()));
  }

  public static StoreCreditAccountResponse toStoreCredit(StoreCreditAccount sc) {
    return new StoreCreditAccountResponse(sc.customerId().toString(), sc.balance(), sc.currency());
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
