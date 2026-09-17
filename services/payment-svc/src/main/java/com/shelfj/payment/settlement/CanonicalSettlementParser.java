package com.shelfj.payment.settlement;

import com.shelfj.payment.domain.Settlements.ParsedFile;
import com.shelfj.payment.domain.Settlements.ParsedLine;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The platform's own layout, for an acquirer whose export has no parser here yet: a spreadsheet
 * anyone can make from a statement. Columns {@code type, reference, original_reference, gross, fee,
 * net, occurred_at}; {@code fee}, {@code net}, {@code original_reference} and {@code occurred_at}
 * may be left out. The payout's number, date, currency and sum are given with the import.
 */
@ApplicationScoped
public class CanonicalSettlementParser implements SettlementFileParser {

  public static final String FORMAT = "SHELFJ";

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public ParsedFile parse(String content) {
    Csv.Table table = Tables.read(content, "type", "gross");
    List<ParsedLine> lines = new ArrayList<>(table.size());
    for (int row = 0; row < table.size(); row++) {
      String type = table.get(row, "type");
      if (type == null) {
        throw new SettlementFileException(Fields.INVALID, Fields.at(row) + "type is missing");
      }
      lines.add(
          Lines.of(
              type.toUpperCase(Locale.ROOT).replace(' ', '_'),
              Fields.reference(table.get(row, "reference"), row, "reference"),
              Fields.reference(table.get(row, "original_reference"), row, "original_reference"),
              Fields.amount(table.get(row, "gross"), row, "gross"),
              Fields.amount(table.get(row, "fee"), row, "fee"),
              Fields.amount(table.get(row, "net"), row, "net"),
              Fields.time(table.get(row, "occurred_at"), null, row, "occurred_at"),
              row));
    }
    return new ParsedFile(lines, null, null, null, null);
  }
}
