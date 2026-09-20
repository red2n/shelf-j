package com.storeql.payment.mapper;

import com.storeql.payment.domain.Terminals.Attempt;
import com.storeql.payment.domain.Terminals.Terminal;
import com.storeql.payment.dto.TerminalDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Terminals and their attempts as the wire carries them (07.16). */
public final class TerminalMappers {

  private TerminalMappers() {}

  public static TerminalDtos.TerminalResponse toDto(Terminal t) {
    return new TerminalDtos.TerminalResponse(
        t.id().toString(),
        t.storeId().toString(),
        t.label(),
        t.vendor(),
        t.serial(),
        t.status(),
        t.retiredReason(),
        text(t.createdAt()),
        text(t.updatedAt()));
  }

  /**
   * An attempt on the wire.
   *
   * <p>The amount goes out as a string, like every other amount on this platform: a JSON number is
   * a double by the time a browser has parsed it, and this one is money on a customer's card.
   */
  public static TerminalDtos.AttemptResponse toDto(Attempt a) {
    return new TerminalDtos.AttemptResponse(
        a.id().toString(),
        a.terminalId().toString(),
        a.orderId().toString(),
        a.amount().toPlainString(),
        a.currency(),
        a.kind(),
        text(a.refundOf()),
        a.state(),
        a.outcomeDetail(),
        a.scheme(),
        a.panLast4(),
        a.authCode(),
        a.aid(),
        a.applicationLabel(),
        a.entryMode(),
        a.verification(),
        a.providerRef(),
        text(a.paymentId()),
        a.receiptLine(),
        text(a.requestedAt()),
        text(a.settledAt()));
  }

  public static List<TerminalDtos.TerminalResponse> terminals(List<Terminal> all) {
    return all.stream().map(TerminalMappers::toDto).toList();
  }

  public static List<TerminalDtos.AttemptResponse> attempts(List<Attempt> all) {
    return all.stream().map(TerminalMappers::toDto).toList();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }
}
