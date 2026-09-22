package com.storeql.product;

import com.storeql.test.StoreQlArchRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Every id this module makes or reads is an RFC 9562 UUIDv7 — its tests included, which is why this
 * imports them where {@code ArchitectureTest} does not.
 */
@AnalyzeClasses(packages = "com.storeql.product")
class IdsArchitectureTest {

  @ArchTest ArchRule idsAreV7 = StoreQlArchRules.IDS_ARE_V7;
}
