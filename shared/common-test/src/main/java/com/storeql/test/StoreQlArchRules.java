package com.storeql.test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Shared ArchUnit rules encoding the StoreQL layering and SOLID constraints from
 * docs/coding-standards.md. Import these constants into each service's ArchitectureTest with
 * {@code @ArchTest} so violations become failing CI tests rather than review comments.
 *
 * <p>Rules are intentionally additive — each targets one precise invariant so failures point to
 * exactly which constraint was broken.
 */
public final class StoreQlArchRules {

  private static final String REF_SRP = " See docs/coding-standards.md §2.1 (SRP).";

  private StoreQlArchRules() {}

  // ── Layer rules (Golden rule #9: controllers are thin; no cross-layer bypasses) ──────────

  /**
   * Resource classes ({@code api} package) must not call repositories directly. All data access
   * goes through the service layer.
   */
  public static final ArchRule API_DOES_NOT_CALL_REPO =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .should()
          .accessClassesThat()
          .resideInAPackage("..repo..")
          .because(
              "api/ resources are thin — all persistence access goes through service/." + REF_SRP);

  /**
   * Service classes must not depend on HTTP/JAX-RS concerns. Services know nothing about the
   * transport layer.
   */
  public static final ArchRule SERVICE_IS_HTTP_FREE =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("jakarta.ws.rs..", "io.helidon.webserver..")
          .because("service/ classes must be HTTP-agnostic." + REF_SRP);

  /**
   * Domain objects are pure value types: no SQL, no HTTP, no service calls. They may only use the
   * Java standard library and shared contract types.
   */
  public static final ArchRule DOMAIN_IS_PURE =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..repo..", "..service..", "..api..", "java.sql..")
          .because(
              "domain/ objects are pure value types — no DB, no HTTP, no service calls." + REF_SRP);

  // ── Messaging rules (SRP: consumer owns lifecycle, handler owns logic) ────────────────────

  /**
   * Kafka Consumer classes must not call repositories or services directly. Their only job is to
   * poll Kafka and dispatch to a Handler bean.
   */
  public static final ArchRule CONSUMERS_DELEGATE_TO_HANDLERS =
      noClasses()
          .that()
          .resideInAPackage("..messaging..")
          .and()
          .haveSimpleNameEndingWith("Consumer")
          .should()
          .accessClassesThat(
              // com.storeql.service is the shared infra module (KafkaEventLoop, ServiceSettings),
              // not a business service layer — consumers exist to drive that poll loop.
              resideInAnyPackage("..repo..", "..service..")
                  .and(not(resideInAPackage("com.storeql.service.."))))
          .because(
              "Consumer classes own only the Kafka poll loop."
                  + " Business logic belongs in a Handler bean."
                  + REF_SRP);

  // ── DTO boundary (Golden rule #10: never expose JPA/domain entities over HTTP) ───────────

  /**
   * DTO classes must not reference domain objects. DTOs are the public API contract; domain objects
   * are internal.
   */
  public static final ArchRule DTOS_DO_NOT_EXPOSE_DOMAIN =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..domain..")
          .because(
              "DTOs are the HTTP contract — they must not expose domain internals."
                  + " Use Mappers to convert. See docs/coding-standards.md §2.1.");

  /**
   * Every id is an RFC 9562 UUIDv7 — in main code and in tests alike: minted with {@code
   * Ids.newId()}, derived with {@code Ids.derived}, read with {@code Ids.parse}. Nothing outside
   * the id module makes a UUID of another version or reads one leniently. PMD holds main code to
   * the same rule; this is what holds the tests, which PMD does not read — a test that stores a v4
   * or sends one proves nothing about the platform that refuses them.
   */
  public static final ArchRule IDS_ARE_V7 =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.storeql.ids..")
          .should()
          .callMethod(java.util.UUID.class, "randomUUID")
          .orShould()
          .callMethod(java.util.UUID.class, "nameUUIDFromBytes", byte[].class)
          .orShould()
          .callMethod(java.util.UUID.class, "fromString", String.class)
          .orShould()
          .callConstructor(java.util.UUID.class, long.class, long.class)
          .because(
              "StoreQL ids are RFC 9562 UUIDv7: Ids.newId() / Ids.derived() to make one,"
                  + " Ids.parse() to read one");
}
