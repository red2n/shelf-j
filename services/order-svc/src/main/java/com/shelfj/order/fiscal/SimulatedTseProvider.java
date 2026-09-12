package com.shelfj.order.fiscal;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.Domain.TseDevice;
import com.shelfj.order.domain.Domain.TseStamp;
import com.shelfj.order.repo.TseDeviceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * A security module in software (18.5).
 *
 * <p>It does what BSI TR-03153 asks a module to do — a key pair per device, a serial derived from
 * the public key, a transaction counter and a signature counter that never repeat, an ECDSA
 * signature over the transaction — and it is not certified, because a certified module keeps its
 * key where no one can read it and this one keeps it in a table. It exists so every document is
 * stamped on a rig with no device, and so the stamp can be verified in a test with the public key
 * beside it.
 *
 * <p>What it signs is the QR payload the receipt prints, without the signature itself: every field
 * an inspector reads off the receipt is under the signature. A certified module signs a DER
 * structure over the same fields (TR-03151), which is what {@link CloudTseProvider} returns.
 */
@ApplicationScoped
public class SimulatedTseProvider implements TseProvider {

  /** BSI's name for plain ECDSA over P-256 with SHA-256; Java calls the same thing P1363. */
  public static final String ALGORITHM = "ecdsa-plain-SHA256";

  public static final String TIME_FORMAT = "unixTime";

  @Inject TseDeviceRepository devices;

  @Override
  public String name() {
    return TseDevice.PROVIDER_SIMULATED;
  }

  @Override
  public TseDevice register(RegistrationRequest req) {
    KeyPair pair = generate();
    String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    return new TseDevice(
        Ids.newId(),
        req.tenantId(),
        req.storeId(),
        name(),
        req.clientId(),
        serialOf(pair.getPublic()),
        publicKey,
        ALGORITHM,
        TIME_FORMAT,
        privateKey,
        null,
        0,
        0,
        null,
        req.registeredBy());
  }

  @Override
  public TseStamp sign(TseDevice device, SaleFigures sale) {
    if (device.privateKey() == null) {
      throw new TseException("simulated device " + device.serialNumber() + " has no key", null);
    }
    var counters = devices.nextCounters(device.id());
    Instant started = sale.startedAt() == null ? Instant.now() : sale.startedAt();
    Instant finished = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    if (finished.isBefore(started)) {
      finished = started;
    }
    String processData = ProcessData.kassenbeleg(sale);
    String toSign =
        signedPayload(
            device.clientId(),
            TseStamp.PROCESS_TYPE_RECEIPT,
            processData,
            counters.transactionNumber(),
            counters.signatureCounter(),
            started,
            finished);
    String signature = sign(device.privateKey(), toSign);
    String qr =
        ProcessData.qr(
            device.clientId(),
            TseStamp.PROCESS_TYPE_RECEIPT,
            processData,
            counters.transactionNumber(),
            counters.signatureCounter(),
            started,
            finished,
            ALGORITHM,
            TIME_FORMAT,
            signature,
            device.publicKey());
    return new TseStamp(
        device.serialNumber(),
        device.clientId(),
        counters.transactionNumber(),
        counters.signatureCounter(),
        signature,
        ALGORITHM,
        device.publicKey(),
        TIME_FORMAT,
        started,
        finished,
        TseStamp.PROCESS_TYPE_RECEIPT,
        processData,
        qr,
        null);
  }

  /** The bytes under the signature: the QR payload up to and including the time format. */
  public static String signedPayload(
      String clientId,
      String processType,
      String processData,
      long transactionNumber,
      long signatureCounter,
      Instant startedAt,
      Instant finishedAt) {
    return String.join(
        ";",
        "V0",
        clientId,
        processType,
        processData,
        Long.toString(transactionNumber),
        Long.toString(signatureCounter),
        startedAt.toString(),
        finishedAt.toString(),
        ALGORITHM,
        TIME_FORMAT);
  }

  /** The serial a module carries: SHA-256 over the public key, hex — as TR-03153 defines it. */
  public static String serialOf(PublicKey key) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static KeyPair generate() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("EC P-256 unavailable", e);
    }
  }

  static String sign(String base64PrivateKey, String payload) {
    try {
      PrivateKey key =
          KeyFactory.getInstance("EC")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64PrivateKey)));
      Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
      s.initSign(key);
      s.update(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new TseException("simulated device could not sign", e);
    }
  }

  /** Whether a stamp's signature is the device's signature over its own printed fields. */
  public static boolean verify(String base64PublicKey, String payload, String base64Signature) {
    try {
      PublicKey key =
          KeyFactory.getInstance("EC")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
      Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
      s.initVerify(key);
      s.update(payload.getBytes(StandardCharsets.UTF_8));
      return s.verify(Base64.getDecoder().decode(base64Signature));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
