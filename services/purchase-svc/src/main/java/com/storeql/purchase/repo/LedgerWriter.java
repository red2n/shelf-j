package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The one place ledger lines are inserted. Lines are built — and their balance checked — by {@code
 * LedgerPosting}; this only stores them, inside whatever transaction the document's own write is
 * in, so a posting and the document it records commit together or not at all.
 */
final class LedgerWriter {

  private LedgerWriter() {}

  static void insert(Connection c, List<NominalLedgerEntry> entries) throws SQLException {
    if (entries.isEmpty()) return;
    try (var ps =
        c.prepareStatement(
            "INSERT INTO nominal_ledger_entries"
                + " (id,tenant_id,entry_date,nominal_code,nominal_name,debit,credit,description,"
                + "  source_ref,journal_id,source_type,store_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      for (NominalLedgerEntry e : entries) {
        ps.setObject(1, e.id());
        ps.setObject(2, e.tenantId());
        ps.setObject(3, e.entryDate());
        ps.setString(4, e.nominalCode());
        ps.setString(5, e.nominalName());
        ps.setBigDecimal(6, e.debit());
        ps.setBigDecimal(7, e.credit());
        ps.setString(8, e.description());
        ps.setObject(9, e.sourceRef());
        ps.setObject(10, e.journalId());
        ps.setString(11, e.sourceType());
        ps.setObject(12, e.storeId());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }
}
