package com.storeql.gs1;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

/**
 * Every id this module makes or reads is an RFC 9562 UUIDv7 — its tests included. The rule is
 * common-test's {@code StoreQlArchRules.IDS_ARE_V7}, stated again here because common-test depends
 * on this module and cannot be depended on by it.
 */
@AnalyzeClasses(packages = "com.storeql.gs1")
class IdsArchitectureTest {

  @ArchTest
  ArchRule idsAreV7 =
      noClasses()
          .should()
          .callMethod(UUID.class, "randomUUID")
          .orShould()
          .callMethod(UUID.class, "nameUUIDFromBytes", byte[].class)
          .orShould()
          .callMethod(UUID.class, "fromString", String.class)
          .orShould()
          .callConstructor(UUID.class, long.class, long.class)
          .because(
              "StoreQL ids are RFC 9562 UUIDv7: Ids.newId() to make one, Ids.parse() to read one");
}
