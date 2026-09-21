package com.storeql.notification.messaging;

import java.util.List;
import java.util.Map;

/** The words a recall's messages share: what a hazard is called, what a remedy is called. */
final class RecallText {

  private RecallText() {}

  private static final Map<String, String> HAZARD_TEXT =
      Map.of(
          "MICROBIOLOGICAL", "microbiological contamination",
          "ALLERGEN", "undeclared allergen",
          "FOREIGN_BODY", "foreign body",
          "CHEMICAL", "chemical contamination",
          "LABELLING", "labelling error",
          "QUALITY", "quality defect");

  private static final Map<String, String> REMEDY_TEXT =
      Map.of("REFUND", "a refund", "REPLACEMENT", "a replacement", "REPAIR", "a repair");

  static String hazard(String hazard) {
    return HAZARD_TEXT.getOrDefault(hazard, "safety issue");
  }

  /** "a refund or a replacement" — the choice GPSR art.37 gives the buyer, in words. */
  static String remedies(List<String> remedies) {
    List<String> words = remedies.stream().map(r -> REMEDY_TEXT.getOrDefault(r, r)).toList();
    if (words.size() <= 1) return String.join("", words);
    return String.join(", ", words.subList(0, words.size() - 1))
        + " or "
        + words.get(words.size() - 1);
  }
}
