package com.shelfj.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.inventory.domain.Recall.Match;
import com.shelfj.inventory.domain.Recall.Scope;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a recall's scope takes off sale: a known lot or date outside the scope rules a batch out, an
 * unknown one cannot, and a batch number this service wrote itself is not a lot.
 */
class RecallTest {

  private static final UUID VARIANT = UUID.randomUUID();
  private static final LocalDate OCT_1 = LocalDate.parse("2026-10-01");
  private static final LocalDate OCT_31 = LocalDate.parse("2026-10-31");

  @Test
  void aLineNamingNothingCoversEveryPackWhateverItsLotOrDate() {
    Scope every = new Scope(UUID.randomUUID(), VARIANT, null, null, null);
    assertTrue(every.coversEveryPack());
    assertEquals(Match.IN_SCOPE, every.classify("L1", OCT_1));
    assertEquals(Match.IN_SCOPE, every.classify(null, null));
  }

  @Test
  void aLotIsMatchedIgnoringCaseAndSpacesAndAnotherLotIsRuledOut() {
    Scope lot = new Scope(UUID.randomUUID(), VARIANT, "L-2291", null, null);
    assertEquals(Match.IN_SCOPE, lot.classify(" l-2291 ", null));
    assertNull(lot.classify("L-2292", null));
    assertEquals(Match.LOT_UNKNOWN, lot.classify(null, OCT_1));
    assertEquals(Match.LOT_UNKNOWN, lot.classify("  ", OCT_1));
  }

  @Test
  void aBatchNumberTheServiceWroteItselfCannotRuleABatchOut() {
    Scope lot = new Scope(UUID.randomUUID(), VARIANT, "L-2291", null, null);
    for (String system :
        List.of("ADJ", "CC-1a2b3c4d", "MO-1a2b3c4d", "TO-1a2b3c4d", "RET-1a2b3c4d")) {
      assertEquals(Match.LOT_UNKNOWN, lot.classify(system, OCT_1), system);
    }
    assertTrue(Recall.isSupplierLot("TO-SUPPLIER-LOT"));
  }

  @Test
  void datesAreInclusiveAndAnUnknownDateIsHeldAsPossiblyAffected() {
    Scope range = new Scope(UUID.randomUUID(), VARIANT, null, OCT_1, OCT_31);
    assertEquals(Match.IN_SCOPE, range.classify("L1", OCT_1));
    assertEquals(Match.IN_SCOPE, range.classify("L1", OCT_31));
    assertNull(range.classify("L1", OCT_31.plusDays(1)));
    assertNull(range.classify("L1", OCT_1.minusDays(1)));
    assertEquals(Match.DATE_UNKNOWN, range.classify("L1", null));

    Scope onOrAfter = new Scope(UUID.randomUUID(), VARIANT, null, OCT_1, null);
    assertEquals(Match.IN_SCOPE, onOrAfter.classify(null, OCT_31.plusYears(1)));
  }

  @Test
  void aKnownMismatchOutranksAnUnknownAndTheMostCertainLineWins() {
    Scope both = new Scope(UUID.randomUUID(), VARIANT, "L1", OCT_1, OCT_31);
    assertNull(both.classify("L2", null), "a different lot rules it out, date or no date");
    assertNull(both.classify(null, OCT_31.plusDays(1)), "an out-of-range date rules it out");
    assertEquals(Match.LOT_UNKNOWN, both.classify(null, null));

    List<Scope> notice =
        List.of(
            new Scope(UUID.randomUUID(), VARIANT, "L9", null, null),
            new Scope(UUID.randomUUID(), VARIANT, null, OCT_1, OCT_31),
            new Scope(UUID.randomUUID(), UUID.randomUUID(), null, null, null));
    assertEquals(Match.IN_SCOPE, Recall.classify(notice, VARIANT, null, OCT_1));
    assertEquals(Match.LOT_UNKNOWN, Recall.classify(notice, VARIANT, "ADJ", OCT_31.plusDays(9)));
    assertNull(Recall.classify(notice, VARIANT, "L1", OCT_31.plusDays(9)));
    assertFalse(Match.IN_SCOPE.isReleasable());
    assertTrue(Match.DATE_UNKNOWN.isReleasable());
  }
}
