package com.storeql.notification.template;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Where a business's own words are kept: all a message needs to be written. */
public interface TemplateStore {

  /**
   * One version of a business's words for one message, form and language.
   *
   * @param retiredAt when it stopped being used; null while it is the live one
   */
  record Stored(
      UUID id,
      String messageType,
      Catalogue.Form form,
      String language,
      int version,
      String subject,
      String body,
      Instant createdAt,
      UUID createdBy,
      Instant retiredAt) {}

  /**
   * How a business's messages go out.
   *
   * @param defaultLanguage what they are written in when the reader's language is not known
   * @param signOff what they are signed with; null for the business's own name
   */
  record Settings(String defaultLanguage, String signOff) {}

  /** The live version, if the business has written one. */
  Optional<Stored> live(UUID tenantId, String messageType, Catalogue.Form form, String language);

  /** The business's settings, if it has saved any. */
  Optional<Settings> settings(UUID tenantId);
}
