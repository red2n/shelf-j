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

  /**
   * Converts a customer profile to its wire form.
   *
   * @param c the customer profile to convert
   * @return its API representation, with ids and timestamps rendered as strings
   */
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

  /**
   * Converts a customer address to its wire form.
   *
   * @param a the customer address to convert
   * @return its API representation
   */
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

  /**
   * Converts a loyalty account to its wire form.
   *
   * @param la the loyalty account to convert
   * @return its API representation: balance, lifetime points and tier
   */
  public static LoyaltyAccountResponse toLoyalty(LoyaltyAccount la) {
    return new LoyaltyAccountResponse(
        la.customerId().toString(), la.pointsBalance(), la.lifetimePoints(), la.tier());
  }

  /**
   * Converts one append-only loyalty ledger entry to its wire form.
   *
   * @param e the ledger entry to convert
   * @return its API representation, including the balance the entry left behind
   */
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

  /**
   * Converts a store-credit account to its wire form.
   *
   * @param sc the account to convert
   * @return its API representation: balance and the currency it is held in
   */
  public static StoreCreditAccountResponse toStoreCredit(StoreCreditAccount sc) {
    return new StoreCreditAccountResponse(sc.customerId().toString(), sc.balance(), sc.currency());
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
