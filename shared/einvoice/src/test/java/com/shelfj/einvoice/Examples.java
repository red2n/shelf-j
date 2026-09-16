package com.shelfj.einvoice;

import com.shelfj.einvoice.Invoice.Card;
import com.shelfj.einvoice.Invoice.Identifier;
import com.shelfj.einvoice.Invoice.Party;
import com.shelfj.einvoice.Invoice.PaymentInstructions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The official CEN and OpenPEPPOL example invoices, and small tools for changing one. */
final class Examples {

  private Examples() {}

  /** Every example's file name. */
  static Stream<String> names() {
    try (Stream<Path> files =
        Files.list(Path.of(Examples.class.getResource("/examples").toURI()))) {
      return files.map(p -> p.getFileName().toString()).sorted().toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] bytes(String name) {
    try (InputStream in = Examples.class.getResourceAsStream("/examples/" + name)) {
      if (in == null) throw new IllegalArgumentException("no example " + name);
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Invoice invoice(String name) {
    return EInvoices.read(bytes(name)).invoice();
  }

  /** A copy of a record with one component replaced, for breaking one rule at a time. */
  static <T extends Record> T with(T record, String component, Object value) {
    RecordComponent[] components = record.getClass().getRecordComponents();
    Object[] args = new Object[components.length];
    Class<?>[] types = new Class<?>[components.length];
    boolean found = false;
    try {
      for (int i = 0; i < components.length; i++) {
        types[i] = components[i].getType();
        if (components[i].getName().equals(component)) {
          args[i] = value;
          found = true;
        } else {
          args[i] = components[i].getAccessor().invoke(record);
        }
      }
      if (!found) throw new IllegalArgumentException(record.getClass() + " has no " + component);
      @SuppressWarnings("unchecked")
      Constructor<T> constructor = (Constructor<T>) record.getClass().getDeclaredConstructor(types);
      return constructor.newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * What survives a change of syntax: CII writes identifiers without a scheme before those with
   * one, and has nowhere for a card's network, which only UBL requires.
   */
  static Invoice acrossSyntaxes(Invoice inv) {
    Invoice out = inv;
    Invoice.Totals totals = inv.totals();
    if (totals != null && totals.vat() == null && !inv.vatBreakdown().isEmpty()) {
      // UBL's schema requires the total VAT CII may leave out; the writer gives the breakdown's
      // sum.
      java.math.BigDecimal sum =
          inv.vatBreakdown().stream()
              .map(Invoice.VatBreakdown::taxAmount)
              .filter(java.util.Objects::nonNull)
              .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
      out = with(out, "totals", with(totals, "vat", sum));
    }
    // CII carries a price discount only inside a gross price: net plus discount when none was
    // given.
    List<Invoice.Line> lines = new ArrayList<>();
    for (Invoice.Line l : inv.lines()) {
      Invoice.Price p = l.price();
      lines.add(
          p != null && p.gross() == null && p.discount() != null && p.net() != null
              ? with(l, "price", with(p, "gross", p.net().add(p.discount())))
              : l);
    }
    out = with(out, "lines", lines);
    out = with(out, "seller", orderedIdentifiers(inv.seller()));
    out = with(out, "buyer", orderedIdentifiers(inv.buyer()));
    PaymentInstructions p = inv.payment();
    if (p != null && p.card() != null) {
      Card card = p.card();
      out =
          with(
              out,
              "payment",
              with(p, "card", new Card(card.primaryAccountNumber(), null, card.holderName())));
    }
    return out;
  }

  private static Party orderedIdentifiers(Party p) {
    if (p == null) return null;
    List<Identifier> ids = new ArrayList<>(p.identifiers());
    ids.sort(Comparator.comparing(id -> id.scheme() != null));
    return with(p, "identifiers", ids);
  }
}
