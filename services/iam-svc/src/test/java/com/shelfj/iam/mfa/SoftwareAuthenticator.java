package com.shelfj.iam.mfa;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A passkey authenticator in software, for tests: a P-256 key, a credential id, a signature
 * counter, and the two ceremonies as a browser and an authenticator would produce them between them
 * — so the verifier is tested against bytes it did not make itself.
 */
final class SoftwareAuthenticator {

  static final int UP = 0x01;
  static final int UV = 0x04;
  static final int AT = 0x40;

  record Attestation(byte[] clientDataJson, byte[] attestationObject) {}

  record Assertion(byte[] clientDataJson, byte[] authenticatorData, byte[] signature) {}

  final String rpId;
  final KeyPair keys;
  final byte[] credentialId = new byte[32];
  long counter;

  SoftwareAuthenticator(String rpId) {
    this.rpId = rpId;
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
      gen.initialize(new ECGenParameterSpec("secp256r1"));
      keys = gen.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    new SecureRandom().nextBytes(credentialId);
  }

  Attestation register(byte[] challenge, String origin, int flags) {
    byte[] authData = concat(authData(flags | AT), attested());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xa3);
    text(out, "fmt");
    text(out, "none");
    text(out, "attStmt");
    out.write(0xa0);
    text(out, "authData");
    bytes(out, authData);
    return new Attestation(clientData("webauthn.create", challenge, origin), out.toByteArray());
  }

  Assertion sign(byte[] challenge, String origin, int flags) {
    counter++;
    byte[] clientData = clientData("webauthn.get", challenge, origin);
    byte[] authData = authData(flags);
    return new Assertion(clientData, authData, signature(authData, clientData));
  }

  byte[] signature(byte[] authData, byte[] clientData) {
    try {
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(keys.getPrivate());
      signer.update(authData);
      signer.update(MessageDigest.getInstance("SHA-256").digest(clientData));
      return signer.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] clientData(String type, byte[] challenge, String origin) {
    return ("{\"type\":\""
            + type
            + "\",\"challenge\":\""
            + Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
            + "\",\"origin\":\""
            + origin
            + "\",\"crossOrigin\":false}")
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] authData(int flags) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.writeBytes(
          MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)));
      out.write(flags);
      out.write((int) (counter >> 24) & 0xff);
      out.write((int) (counter >> 16) & 0xff);
      out.write((int) (counter >> 8) & 0xff);
      out.write((int) counter & 0xff);
      return out.toByteArray();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** aaguid, the credential id with its length, and the COSE key. */
  byte[] attested() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[16]);
    out.write(credentialId.length >> 8);
    out.write(credentialId.length & 0xff);
    out.writeBytes(credentialId);
    out.writeBytes(coseKey());
    return out.toByteArray();
  }

  byte[] coseKey() {
    ECPublicKey pub = (ECPublicKey) keys.getPublic();
    return coseEc2(fixed(pub.getW().getAffineX()), fixed(pub.getW().getAffineY()));
  }

  static byte[] coseEc2(byte[] x, byte[] y) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xa5);
    out.write(0x01);
    out.write(0x02); // kty: EC2
    out.write(0x03);
    out.write(0x26); // alg: -7
    out.write(0x20);
    out.write(0x01); // crv: P-256
    out.write(0x21);
    bytes(out, x);
    out.write(0x22);
    bytes(out, y);
    return out.toByteArray();
  }

  static byte[] fixed(BigInteger n) {
    byte[] raw = n.toByteArray();
    byte[] out = new byte[32];
    int from = Math.max(0, raw.length - 32);
    System.arraycopy(raw, from, out, 32 - (raw.length - from), raw.length - from);
    return out;
  }

  static void text(ByteArrayOutputStream out, String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    out.write(0x60 | b.length);
    out.writeBytes(b);
  }

  static void bytes(ByteArrayOutputStream out, byte[] b) {
    if (b.length < 24) {
      out.write(0x40 | b.length);
    } else if (b.length < 256) {
      out.write(0x58);
      out.write(b.length);
    } else {
      out.write(0x59);
      out.write(b.length >> 8);
      out.write(b.length & 0xff);
    }
    out.writeBytes(b);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
