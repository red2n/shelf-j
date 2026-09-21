package com.storeql.inventory;

import com.storeql.test.StoreQlArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.storeql.inventory",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest ArchRule apiDoesNotCallRepo = StoreQlArchRules.API_DOES_NOT_CALL_REPO;

  @ArchTest ArchRule serviceIsHttpFree = StoreQlArchRules.SERVICE_IS_HTTP_FREE;

  @ArchTest ArchRule domainIsPure = StoreQlArchRules.DOMAIN_IS_PURE;

  @ArchTest
  ArchRule consumersOnlyDelegateToHandlers = StoreQlArchRules.CONSUMERS_DELEGATE_TO_HANDLERS;

  @ArchTest ArchRule dtosDoNotExposeDomain = StoreQlArchRules.DTOS_DO_NOT_EXPOSE_DOMAIN;
}
