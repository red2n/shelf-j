package com.storeql.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** A business's API keys (22.7) on the wire. */
public final class ApiKeyDtos {

  private ApiKeyDtos() {}

  @Schema(
      name = "CreateApiKeyRequest",
      description =
          "A key for one of the business's systems. It acts in one staff tier — MANAGER,"
              + " STOREKEEPER or CASHIER, never OWNER — for the stores named or all of them, until"
              + " the day given or until revoked.")
  public record CreateRequest(
      @Schema(description = "What the owner calls it, e.g. Warehouse ERP.")
          @NotBlank
          @Size(max = 80)
          String name,
      @Schema(description = "MANAGER, STOREKEEPER or CASHIER.") @NotBlank String role,
      @Schema(description = "The stores it may work in; leave out for every store.")
          List<String> storeIds,
      @Schema(description = "When it stops, ISO-8601; leave out for until revoked.")
          String expiresAt) {}

  @Schema(name = "ApiKey", description = "A key as listed: never the key itself.")
  public record KeyResponse(
      String id,
      String name,
      @Schema(description = "The first twelve characters, to tell keys apart.") String prefix,
      String role,
      List<String> storeIds,
      String createdBy,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      String revokedBy) {}

  @Schema(
      name = "ApiKeyCreated",
      description = "The key as made. The key itself is shown here and never again.")
  public record CreatedResponse(
      String id,
      String name,
      String prefix,
      String role,
      List<String> storeIds,
      String createdBy,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      @Schema(description = "The whole key. Copy it now: it is not kept.") String key) {

    /** The listing's view of the key, with the key itself beside it. */
    public static CreatedResponse of(KeyResponse r, String key) {
      return new CreatedResponse(
          r.id(),
          r.name(),
          r.prefix(),
          r.role(),
          r.storeIds(),
          r.createdBy(),
          r.createdAt(),
          r.expiresAt(),
          r.lastUsedAt(),
          r.revokedAt(),
          key);
    }
  }

  @Schema(name = "ApiKeyPage")
  public record Page(List<KeyResponse> items, String nextCursor) {}

  @Schema(name = "ApiKeyIntrospectRequest")
  public record IntrospectRequest(@NotBlank String key) {}

  @Schema(
      name = "ApiKeyIntrospection",
      description =
          "What a key may do, as the gateway asks it: the business, the tier and the stores when"
              + " it is active; why not when it is not.")
  public record IntrospectionResponse(
      boolean active,
      @Schema(description = "unknown, revoked, expired or tenant suspended; absent when active.")
          String reason,
      String keyId,
      String tenantId,
      List<String> roles,
      List<String> storeIds,
      String name) {

    public static IntrospectionResponse refused(String reason) {
      return new IntrospectionResponse(false, reason, null, null, null, null, null);
    }
  }
}
