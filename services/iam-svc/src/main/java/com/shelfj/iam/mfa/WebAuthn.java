package com.shelfj.iam.mfa;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The relying party's half of WebAuthn (20.12; W3C Web Authentication Level 2, §7.1 and §7.2): what
 * a passkey's registration and each later sign-in have to prove. A passkey is the
 * phishing-resistant second factor — the browser binds every signature to the origin it was made
 * for, so a look-alike site gets a signature that is no use here.
 *
 * <p>Attestation is not asked for ("none"): the platform does not care which make of authenticator
 * a member of staff owns, only that the same key signs next time. What is checked, every time: the
 * ceremony type, the challenge this service issued, the origin, the relying party, that a person
 * was present (and verified, when required), the signature, and that the signature counter has not
 * gone backwards — which is how a cloned authenticator shows.
 */
public final class WebAuthn {

  /** COSE algorithm identifiers this service accepts: ECDSA P-256 with SHA-256, RSA PKCS#1 v1.5. */
  public static final long ES256 = -7;

  public static final long RS256 = -257;

  private static final int FLAG_USER_PRESENT = 0x01;
  private static final int FLAG_USER_VERIFIED = 0x04;
  private static final int FLAG_ATTESTED_DATA = 0x40;
  private static final int RP_HASH_BYTES = 32;
  private static final int MAX_CREDENTIAL_ID_BYTES = 1023;
  private static final int MAX_INPUT_BYTES = 16 * 1024;

  private WebAuthn() {}

  /**
   * Who this service is to an authenticator.
   *
   * @param id the relying party id: the site's domain, without scheme or port
   * @param origins the origins the app is served from, exactly as a browser reports them
   */
  public record RelyingParty(String id, Set<String> origins) {
    public RelyingParty {
      origins = Set.copyOf(origins);
    }
  }

  /**
   * A passkey as it is kept after registration.
   *
   * @param credentialId the authenticator's name for the key
   * @param publicKeyCose the public key, COSE-encoded, as the authenticator gave it
   * @param signCount the authenticator's signature counter at registration
   * @param userVerified whether the person was verified (PIN, biometric), not merely present
   */
  public record Registered(
      byte[] credentialId, byte[] publicKeyCose, long signCount, boolean userVerified) {
    public Registered {
      credentialId = credentialId.clone();
      publicKeyCose = publicKeyCose.clone();
    }

    @Override
    public byte[] credentialId() {
      return credentialId.clone();
    }

    @Override
    public byte[] publicKeyCose() {
      return publicKeyCose.clone();
    }
  }

  /** Refused: the message says which check failed, for the log — the caller answers one thing. */
  public static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Refused(String message) {
      super(message);
    }

    public Refused(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Verifies a registration (§7.1).
   *
   * @param clientDataJson the browser's account of the ceremony
   * @param attestationObject the authenticator's answer, CBOR
   * @param challenge the challenge this service issued for it
   * @param requireUserVerification whether presence alone is not enough
   * @throws Refused when anything does not hold
   */
  public static Registered registration(
      byte[] clientDataJson,
      byte[] attestationObject,
      byte[] challenge,
      RelyingParty rp,
      boolean requireUserVerification) {
    bounded(clientDataJson, attestationObject);
    clientData(clientDataJson, "webauthn.create", challenge, rp);
    byte[] authData;
    try {
      Object decoded = Cbor.decode(attestationObject);
      if (!(decoded instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] bytes)) {
        throw new Refused("the attestation object carries no authenticator data");
      }
      authData = bytes;
    } catch (IllegalArgumentException e) {
      throw new Refused("the attestation object is not CBOR this service reads", e);
    }
    int flags = authenticatorData(authData, rp, requireUserVerification);
    if ((flags & FLAG_ATTESTED_DATA) == 0) {
      throw new Refused("the authenticator sent no credential");
    }
    // rpIdHash(32) flags(1) signCount(4) aaguid(16) credentialIdLength(2) credentialId key
    int at = RP_HASH_BYTES + 1 + 4 + 16;
    if (authData.length < at + 2) throw new Refused("the credential data is cut short");
    int idLength = ((authData[at] & 0xff) << 8) | (authData[at + 1] & 0xff);
    at += 2;
    if (idLength == 0 || idLength > MAX_CREDENTIAL_ID_BYTES || authData.length < at + idLength) {
      throw new Refused("the credential id has an impossible length");
    }
    byte[] credentialId = Arrays.copyOfRange(authData, at, at + idLength);
    at += idLength;
    byte[] cose;
    try {
      Cbor key = new Cbor(authData, at);
      key.next();
      cose = Arrays.copyOfRange(authData, at, key.position());
    } catch (IllegalArgumentException e) {
      throw new Refused("the credential's public key is not CBOR this service reads", e);
    }
    publicKey(cose); // refuses a key of a kind, curve or size this service does not accept
    return new Registered(
        credentialId, cose, signCount(authData), (flags & FLAG_USER_VERIFIED) != 0);
  }

  /**
   * Verifies a sign-in (§7.2).
   *
   * @param publicKeyCose the key kept at registration
   * @param storedSignCount the counter kept from the last use
   * @return the authenticator's new counter, to keep
   * @throws Refused when anything does not hold
   */
  public static long assertion(
      byte[] clientDataJson,
      byte[] authenticatorData,
      byte[] signature,
      byte[] challenge,
      RelyingParty rp,
      byte[] publicKeyCose,
      long storedSignCount,
      boolean requireUserVerification) {
    bounded(clientDataJson, authenticatorData, signature);
    clientData(clientDataJson, "webauthn.get", challenge, rp);
    authenticatorData(authenticatorData, rp, requireUserVerification);
    try {
      byte[] signed =
          concat(authenticatorData, MessageDigest.getInstance("SHA-256").digest(clientDataJson));
      Map<?, ?> key = coseMap(publicKeyCose);
      Signature verifier =
          Signature.getInstance(
              Long.valueOf(RS256).equals(key.get(3L)) ? "SHA256withRSA" : "SHA256withECDSA");
      verifier.initVerify(publicKey(publicKeyCose));
      verifier.update(signed);
      if (!verifier.verify(signature)) throw new Refused("the signature does not verify");
    } catch (GeneralSecurityException e) {
      throw new Refused("the signature could not be checked", e);
    }
    long count = signCount(authenticatorData);
    if ((count != 0 || storedSignCount != 0) && count <= storedSignCount) {
      throw new Refused("the signature counter went backwards: a cloned authenticator?");
    }
    return count;
  }

  /** The browser's account: the right ceremony, our challenge, one of our origins, not embedded. */
  private static void clientData(byte[] json, String type, byte[] challenge, RelyingParty rp) {
    JsonObject data;
    try (var reader =
        Json.createReader(new StringReader(new String(json, StandardCharsets.UTF_8)))) {
      data = reader.readObject();
    } catch (RuntimeException e) {
      throw new Refused("the client data is not JSON", e);
    }
    if (!type.equals(data.getString("type", ""))) throw new Refused("the wrong ceremony type");
    byte[] given;
    try {
      given = Base64.getUrlDecoder().decode(data.getString("challenge", ""));
    } catch (IllegalArgumentException e) {
      throw new Refused("the challenge is not base64url", e);
    }
    if (challenge == null || challenge.length < 16 || !MessageDigest.isEqual(challenge, given)) {
      throw new Refused("not the challenge this service issued");
    }
    if (!rp.origins().contains(data.getString("origin", ""))) {
      throw new Refused("an origin this service is not served from");
    }
    if (JsonValue.TRUE.equals(data.get("crossOrigin"))) {
      throw new Refused("the ceremony ran embedded in another site");
    }
  }

  /** The authenticator's account: our relying party, a person present (and verified if asked). */
  private static int authenticatorData(byte[] authData, RelyingParty rp, boolean requireUv) {
    if (authData.length < RP_HASH_BYTES + 1 + 4) {
      throw new Refused("the authenticator data is cut short");
    }
    try {
      byte[] expected =
          MessageDigest.getInstance("SHA-256").digest(rp.id().getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(authData, 0, RP_HASH_BYTES))) {
        throw new Refused("made for another relying party");
      }
    } catch (GeneralSecurityException e) {
      throw new Refused("SHA-256 is not available", e);
    }
    int flags = authData[RP_HASH_BYTES] & 0xff;
    if ((flags & FLAG_USER_PRESENT) == 0) throw new Refused("nobody was present");
    if (requireUv && (flags & FLAG_USER_VERIFIED) == 0) {
      throw new Refused("the person was not verified");
    }
    return flags;
  }

  private static long signCount(byte[] authData) {
    int at = RP_HASH_BYTES + 1;
    return ((long) (authData[at] & 0xff) << 24)
        | ((long) (authData[at + 1] & 0xff) << 16)
        | ((long) (authData[at + 2] & 0xff) << 8)
        | (authData[at + 3] & 0xff);
  }

  private static Map<?, ?> coseMap(byte[] cose) {
    try {
      if (Cbor.decode(cose) instanceof Map<?, ?> map) return map;
    } catch (IllegalArgumentException e) {
      throw new Refused("the public key is not CBOR this service reads", e);
    }
    throw new Refused("the public key is not a COSE key");
  }

  /**
   * The Java key a COSE key describes: EC2 on P-256 for ES256, or RSA of at least 2048 bits for
   * RS256 — nothing else. An EC point is checked to be on the curve, which the JDK does not do for
   * a key built from coordinates, and an off-curve point is how an invalid-curve attack begins.
   */
  static PublicKey publicKey(byte[] cose) {
    Map<?, ?> key = coseMap(cose);
    Object kty = key.get(1L);
    Object alg = key.get(3L);
    try {
      if (Long.valueOf(2).equals(kty) && Long.valueOf(ES256).equals(alg)) {
        if (!Long.valueOf(1).equals(key.get(-1L))) throw new Refused("an EC key not on P-256");
        if (!(key.get(-2L) instanceof byte[] x) || !(key.get(-3L) instanceof byte[] y)) {
          throw new Refused("an EC key without coordinates");
        }
        if (x.length != 32 || y.length != 32) throw new Refused("EC coordinates of the wrong size");
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec curve = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        if (!onCurve(point, curve)) throw new Refused("an EC point that is not on the curve");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, curve));
      }
      if (Long.valueOf(3).equals(kty) && Long.valueOf(RS256).equals(alg)) {
        if (!(key.get(-1L) instanceof byte[] n) || !(key.get(-2L) instanceof byte[] e)) {
          throw new Refused("an RSA key without modulus or exponent");
        }
        BigInteger modulus = new BigInteger(1, n);
        if (modulus.bitLength() < 2048) throw new Refused("an RSA key shorter than 2048 bits");
        return KeyFactory.getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(modulus, new BigInteger(1, e)));
      }
    } catch (GeneralSecurityException e) {
      throw new Refused("the public key could not be built", e);
    }
    throw new Refused("a key type or algorithm this service does not accept");
  }

  private static boolean onCurve(ECPoint point, ECParameterSpec curve) {
    BigInteger p = ((java.security.spec.ECFieldFp) curve.getCurve().getField()).getP();
    BigInteger x = point.getAffineX();
    BigInteger y = point.getAffineY();
    if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
      return false;
    }
    BigInteger left = y.multiply(y).mod(p);
    BigInteger right =
        x.multiply(x)
            .multiply(x)
            .add(curve.getCurve().getA().multiply(x))
            .add(curve.getCurve().getB())
            .mod(p);
    return left.equals(right);
  }

  private static void bounded(byte[]... inputs) {
    for (byte[] input : inputs) {
      if (input == null || input.length == 0 || input.length > MAX_INPUT_BYTES) {
        throw new Refused("an input that is missing or far too large");
      }
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  /** The algorithms offered to a browser when it makes a key, most preferred first. */
  public static List<Long> algorithms() {
    return List.of(ES256, RS256);
  }
}
