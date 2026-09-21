package com.storeql.iam.api;

import com.storeql.iam.auth.SigningKeys;
import com.storeql.iam.domain.SigningKey;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The token signing keys (20.15): the public key set every verifier reads, and rotation for the
 * platform administrator. No route here ever returns a private half.
 */
@RequestScoped
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Auth")
public class SigningKeyResource {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  @Inject SigningKeys keys;
  @Inject TenantContext ctx;

  /** A signing key as the platform administrator sees it: never its material. */
  @Schema(name = "SigningKeyResponse")
  public record SigningKeyResponse(
      String kid,
      String algorithm,
      @Schema(description = "ACTIVE signs; RETIRING still verifies; RETIRED does neither.")
          String status,
      String createdAt,
      String retiringAt,
      String retiredAt) {}

  /**
   * The public keys tokens are verified with (RFC 7517): the key that signs now and any still
   * retiring. Public, and cacheable for five minutes.
   *
   * @return the JSON Web Key Set
   */
  @Operation(
      summary = "The token signing keys' public halves (JWKS)",
      description =
          "RFC 7517 key set: the key that signs access tokens now and any rotated key whose tokens"
              + " can still be alive, each by its kid. Public: the gateway and the MQTT broker"
              + " verify tokens against it, so neither holds anything that can mint one.")
  @APIResponse(responseCode = "200", description = "The key set")
  @GET
  @Path("/.well-known/jwks.json")
  public Response jwks() {
    JsonArrayBuilder set = Json.createArrayBuilder();
    for (SigningKey k : keys.published()) {
      RSAPublicKey pub = SigningKeys.publicKey(k.publicKey());
      set.add(
          Json.createObjectBuilder()
              .add("kty", "RSA")
              .add("use", "sig")
              .add("alg", k.algorithm())
              .add("kid", k.kid())
              .add("n", URL.encodeToString(unsigned(pub.getModulus())))
              .add("e", URL.encodeToString(unsigned(pub.getPublicExponent()))));
    }
    return Response.ok(Json.createObjectBuilder().add("keys", set).build().toString())
        .header("Cache-Control", "public, max-age=300")
        .build();
  }

  /**
   * The recent signing keys and where each is in its life.
   *
   * @return keys, newest first, without their material
   */
  @Operation(summary = "List the token signing keys", description = "PLATFORM_ADMIN only.")
  @APIResponse(responseCode = "200", description = "Keys, newest first")
  @APIResponse(responseCode = "403", description = "Not a platform administrator")
  @GET
  @Path("/admin/signing-keys")
  public ApiResponse<List<SigningKeyResponse>> list() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(keys.recent().stream().map(SigningKeyResource::toDto).toList());
  }

  /**
   * Rotates the signing key now: the new key is published at once and signs after the publish lead
   * time; the old one stays published while the tokens it signed can still be alive.
   *
   * @return the new key
   */
  @Operation(
      summary = "Rotate the token signing key",
      description =
          "The new key is published at once and signs after storeql.jwt.publish-lead-seconds, so"
              + " every verifier has read it before a token names it; the old one keeps verifying"
              + " until the tokens it signed have expired, then is retired and its private half"
              + " wiped. PLATFORM_ADMIN only. Keys also rotate by themselves at"
              + " storeql.jwt.rotation-days.")
  @APIResponse(responseCode = "201", description = "The new key")
  @APIResponse(responseCode = "403", description = "Not a platform administrator")
  @POST
  @Path("/admin/signing-keys/rotate")
  public Response rotate() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return Response.status(201).entity(ApiResponse.ok(toDto(keys.rotate()))).build();
  }

  private static SigningKeyResponse toDto(SigningKey k) {
    return new SigningKeyResponse(
        k.kid(),
        k.algorithm(),
        k.status(),
        k.createdAt().toString(),
        k.retiringAt() == null ? null : k.retiringAt().toString(),
        k.retiredAt() == null ? null : k.retiredAt().toString());
  }

  /** A big-endian magnitude without the sign byte, as a JWK wants it. */
  private static byte[] unsigned(BigInteger i) {
    byte[] b = i.toByteArray();
    if (b.length > 1 && b[0] == 0) {
      byte[] t = new byte[b.length - 1];
      System.arraycopy(b, 1, t, 0, t.length);
      return t;
    }
    return b;
  }
}
