package com.storeql.payment.settlement;

import com.storeql.payment.domain.Settlements.ParsedFile;

/** One acquirer's settlement file layout, read into the lines every layout comes down to. */
public interface SettlementFileParser {

  /** The most lines one file may carry: a day of a large estate, with room. */
  int MAX_LINES = 20_000;

  /** The layout's name, as an import asks for it. */
  String format();

  /**
   * @param content the file's text
   * @return its lines, signed from the business's side, and what the file says about its payout
   * @throws SettlementFileException when the file is not this layout or a line cannot be read
   */
  ParsedFile parse(String content);
}
