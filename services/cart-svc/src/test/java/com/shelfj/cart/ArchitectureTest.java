package com.shelfj.cart;

import com.shelfj.test.ShelfJArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.shelfj.cart", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest ArchRule apiDoesNotCallRepo = ShelfJArchRules.API_DOES_NOT_CALL_REPO;

  @ArchTest ArchRule serviceIsHttpFree = ShelfJArchRules.SERVICE_IS_HTTP_FREE;

  @ArchTest ArchRule domainIsPure = ShelfJArchRules.DOMAIN_IS_PURE;

  @ArchTest
  ArchRule consumersOnlyDelegateToHandlers = ShelfJArchRules.CONSUMERS_DELEGATE_TO_HANDLERS;

  @ArchTest ArchRule dtosDoNotExposeDomain = ShelfJArchRules.DTOS_DO_NOT_EXPOSE_DOMAIN;
}
