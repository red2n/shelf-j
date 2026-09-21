package com.storeql.payment.settlement;

/** Opens a settlement file as a table and says plainly when it is not the layout asked for. */
final class Tables {

  private Tables() {}

  /**
   * @param required columns the layout cannot be read without
   * @throws SettlementFileException when the text is not a table, has no lines, or lacks a column
   */
  static Csv.Table read(String content, String... required) {
    Csv.Table table;
    try {
      table = Csv.read(content, SettlementFileParser.MAX_LINES);
    } catch (Csv.Malformed e) {
      throw new SettlementFileException(Fields.INVALID, e.getMessage(), e);
    }
    for (String column : required) {
      if (!table.has(column)) {
        throw new SettlementFileException(
            "SETTLEMENT_FILE_WRONG_LAYOUT",
            "The file has no \"" + column + "\" column: it is not the layout named");
      }
    }
    if (table.size() == 0) {
      throw new SettlementFileException(Fields.INVALID, "The file has a header and no lines");
    }
    return table;
  }
}
