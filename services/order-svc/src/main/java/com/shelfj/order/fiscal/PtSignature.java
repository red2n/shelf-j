package com.shelfj.order.fiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * The Portuguese document signature (18.5), as Despacho 8632/2014 and the SAF-T (PT) schema fix it:
 * an RSA-SHA1 over {@code InvoiceDate;SystemEntryDate;InvoiceNo;GrossTotal;PreviousHash}, base64,
 * with the previous document's signature chained in and an empty string for the first.
 *
 * <p>Pure functions over strings and keys. The key comes from configuration ({@link PtSigningKey});
 * the certificate the AT issues against its public half is a number in the same configuration.
 */
public final class PtSignature {

  private PtSignature() {}

  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter ENTRY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /**
   * The string that is signed.
   *
   * @param invoiceDate the document's date
   * @param systemEntryDate when the system recorded it, to the second
   * @param invoiceNo the SAF-T invoice number, e.g. {@code FS MAIN2026/42}
   * @param grossTotal the gross, two decimals
   * @param previousHash the previous document's signature, or empty for the first in the series
   * @return {@code 2026-09-12;2026-09-12T10:04:31;FS MAIN2026/42;12.00;<prev>}
   */
  public static String canonical(
      LocalDate invoiceDate,
      Instant systemEntryDate,
      String invoiceNo,
      BigDecimal grossTotal,
      String previousHash) {
    return DATE.format(invoiceDate)
        + ";"
        + ENTRY.format(systemEntryDate.atOffset(ZoneOffset.UTC))
        + ";"
        + invoiceNo
        + ";"
        + grossTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()
        + ";"
        + (previousHash == null ? "" : previousHash);
  }

  /**
   * The SAF-T invoice number for a document: {@code <type> <series>/<number>}, where the series is
   * the full number without its zero-padded counter. A simplified invoice ({@code FS}) is what a
   * till issues to a consumer.
   */
  public static String invoiceNo(String fullNumber, long number) {
    String counter = String.format("%06d", number);
    String series =
        fullNumber.endsWith("-" + counter)
            ? fullNumber.substring(0, fullNumber.length() - counter.length() - 1)
            : fullNumber;
    return "FS " + series.replace(" ", "").replace("/", "-") + "/" + number;
  }

  /** RSA-SHA1 over the canonical string, base64 — the {@code Hash} element of the document. */
  public static String sign(PrivateKey key, String canonical) {
    try {
      Signature s = Signature.getInstance("SHA1withRSA");
      s.initSign(key);
      s.update(canonical.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PT document signature failed", e);
    }
  }

  /** Whether a stored signature is the key's signature over the canonical string. */
  public static boolean verify(PublicKey key, String canonical, String base64Signature) {
    try {
      Signature s = Signature.getInstance("SHA1withRSA");
      s.initVerify(key);
      s.update(canonical.getBytes(StandardCharsets.UTF_8));
      return s.verify(Base64.getDecoder().decode(base64Signature));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * The four characters a receipt prints beside the certificate number: positions 1, 11, 21 and 31
   * of the signature (the law counts from one).
   */
  public static String excerpt(String hash) {
    if (hash == null || hash.length() < 31) {
      return null;
    }
    return "" + hash.charAt(0) + hash.charAt(10) + hash.charAt(20) + hash.charAt(30);
  }

  /**
   * Reads an RSA private key from PEM (PKCS#8, {@code BEGIN PRIVATE KEY}) or its bare base64 body.
   *
   * @throws IllegalArgumentException when the text is not a PKCS#8 RSA key
   */
  public static PrivateKey loadPrivateKey(String pem) {
    String body =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "shelfj.fiscal.pt.private-key is not a PKCS#8 RSA private key", e);
    }
  }

  /** The public half of a private key, for the audit to verify with. */
  public static PublicKey publicKeyOf(PrivateKey key) {
    if (!(key instanceof RSAPrivateCrtKey crt)) {
      throw new IllegalArgumentException("not an RSA CRT key");
    }
    try {
      return KeyFactory.getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Reads a public key from its base64 X.509 DER. */
  public static PublicKey loadPublicKey(String base64Der) {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Der)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("not an X.509 RSA public key", e);
    }
  }

  /**
   * Whether a string is a Portuguese NIF: nine digits whose last is the mod-11 check digit.
   *
   * @param nif the candidate
   * @return true when it is well-formed
   */
  public static boolean isValidNif(String nif) {
    if (nif == null || !nif.matches("[0-9]{9}")) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < 8; i++) {
      sum += (nif.charAt(i) - '0') * (9 - i);
    }
    int check = 11 - (sum % 11);
    if (check >= 10) {
      check = 0;
    }
    return check == nif.charAt(8) - '0';
  }
}
