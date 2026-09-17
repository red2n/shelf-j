package com.shelfj.iam.mfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

  @Test
  void tenCodesEachUnlikeTheOthersInAnAlphabetWithoutLookAlikes() {
    Set<String> seen = new HashSet<>();
    for (int round = 0; round < 20; round++) {
      List<String> codes = RecoveryCodes.generate();
      assertEquals(RecoveryCodes.COUNT, codes.size());
      for (String code : codes) {
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"), code);
        assertTrue(RecoveryCodes.looksLikeOne(code));
        assertTrue(seen.add(code), "a code came up twice");
      }
    }
  }

  @Test
  void aCodeIsTheSameCodeHoweverItIsTyped() {
    String code = RecoveryCodes.generate().get(0);
    String hash = RecoveryCodes.hash(code);
    assertEquals(64, hash.length());
    assertEquals(hash, RecoveryCodes.hash(code.toLowerCase()));
    assertEquals(hash, RecoveryCodes.hash(code.replace("-", "")));
    assertEquals(hash, RecoveryCodes.hash(" " + code.replace("-", " ") + " "));
    assertNotEquals(hash, RecoveryCodes.hash(RecoveryCodes.generate().get(1)));
    assertFalse(hash.contains(code.replace("-", "")), "only the hash is kept");
  }

  @Test
  void whatIsNotShapedLikeACodeIsNotOne() {
    for (String bad :
        new String[] {
          null, "", "123456", "ABCD-EFGH", "ABCD-EFGH-IJKL", "ABCD-EFGH-JKL0", "ABCD-EFGH-JKLM-NPQR"
        }) {
      assertFalse(RecoveryCodes.looksLikeOne(bad), String.valueOf(bad));
    }
  }
}
