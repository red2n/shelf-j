package com.shelfj.order.support;

import com.shelfj.test.JsonStub;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * India's Invoice Registration Portal as a test sees it: the portal's own RSA key pair, a sign-in
 * that opens the password and AppKey the way the portal does and answers with a session key sealed
 * under the AppKey, and a registration that opens the INV-01 with that session key and answers,
 * sealed the same way, with an IRN. {@code mode} decides the answer: register, duplicate (already
 * registered, the IRN in the alert), refuse (a rule broken), or down.
 */
public final class IrpPortalStub {

  public static final String AUTH_PATH = "/irp/auth";
  public static final String INVOICE_PATH = "/irp/Invoice";

  private final KeyPair keys;
  private final Map<String, String> users = new ConcurrentHashMap<>();
  private final Map<String, byte[]> sessions = new ConcurrentHashMap<>();
  private final AtomicInteger signIns = new AtomicInteger();
  private final AtomicInteger registrations = new AtomicInteger();
  private volatile String mode = "register";
  private volatile String lastInvoice;

  private IrpPortalStub(KeyPair keys) {
    this.keys = keys;
  }

  /** Routes the portal's two calls on the stub, with one taxpayer user. */
  public static IrpPortalStub on(JsonStub stub, String user, String password) {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      IrpPortalStub portal = new IrpPortalStub(gen.generateKeyPair());
      portal.users.put(user, password);
      stub.on("POST", AUTH_PATH, portal::signIn);
      stub.on("POST", INVOICE_PATH, portal::register);
      return portal;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String publicKeyBase64() {
    return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
  }

  public void mode(String mode) {
    this.mode = mode;
  }

  public int signIns() {
    return signIns.get();
  }

  public int registrations() {
    return registrations.get();
  }

  /** The last INV-01 the portal opened, as JSON text. */
  public String lastInvoice() {
    return lastInvoice;
  }

  private JsonStub.Answer signIn(JsonStub.Call call) {
    signIns.incrementAndGet();
    if (!"cid".equals(call.header("client_id")) || !"csec".equals(call.header("client_secret"))) {
      return new JsonStub.Answer(
          401, "{\"Status\":0,\"ErrorDetails\":\"Invalid client credentials\"}");
    }
    JsonObject data = object(call.body()).getJsonObject("data");
    String user = data.getString("UserName", "");
    String password = new String(rsaOpen(data.getString("Password")), StandardCharsets.UTF_8);
    byte[] appKey = rsaOpen(data.getString("AppKey"));
    if (!password.equals(users.get(user))) {
      return new JsonStub.Answer(
          200,
          "{\"Status\":0,\"Data\":null,\"ErrorDetails\":\"[{\\\"ErrorCode\\\":\\\"1005\\\",\\\"ErrorMessage\\\":\\\"Invalid Token\\\"}]\",\"InfoDtls\":null}");
    }
    byte[] sek = new byte[32];
    new SecureRandom().nextBytes(sek);
    String token = "TOK-" + signIns.get();
    sessions.put(token, sek);
    String expiry =
        LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
            .plusHours(1)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    return new JsonStub.Answer(
        200,
        Json.createObjectBuilder()
            .add("Status", 1)
            .add(
                "Data",
                Json.createObjectBuilder()
                    .add("ClientId", "cid")
                    .add("UserName", user)
                    .add("AuthToken", token)
                    .add("Sek", Base64.getEncoder().encodeToString(aes(appKey, true, sek)))
                    .add("TokenExpiry", expiry))
            .addNull("ErrorDetails")
            .build()
            .toString());
  }

  private JsonStub.Answer register(JsonStub.Call call) {
    registrations.incrementAndGet();
    if ("down".equals(mode)) return new JsonStub.Answer(503, "{\"message\":\"maintenance\"}");
    byte[] sek = sessions.get(call.header("AuthToken"));
    if (sek == null || !"cid".equals(call.header("client_id"))) {
      return new JsonStub.Answer(401, "{\"Status\":0,\"ErrorDetails\":\"Invalid token\"}");
    }
    String gstin = call.header("Gstin");
    byte[] opened =
        aes(sek, false, Base64.getDecoder().decode(object(call.body()).getString("Data")));
    String invoice = new String(Base64.getDecoder().decode(opened), StandardCharsets.UTF_8);
    lastInvoice = invoice;
    JsonObject inv = object(invoice);
    if (!"1.1".equals(inv.getString("Version", ""))) {
      return status0(
          "[{\"ErrorCode\":\"2158\",\"ErrorMessage\":\"Invalid schema version\"}]", null);
    }
    String number = inv.getJsonObject("DocDtls").getString("No", "");
    String irn = sha256(gstin + "|" + number);
    if ("refuse".equals(mode)) {
      return status0(
          "[{\"ErrorCode\":\"2172\",\"ErrorMessage\":\"Recipient GSTIN cannot be blank\"}]", null);
    }
    if ("duplicate".equals(mode)) {
      return status0(
          "[{\"ErrorCode\":\"2150\",\"ErrorMessage\":\"Duplicate IRN\"}]",
          "[{\"InfCd\":\"DUPIRN\",\"Desc\":{\"AckNo\":112010036563234,\"AckDt\":\"2026-09-16 10:00:00\",\"Irn\":\""
              + irn
              + "\"}}]");
    }
    String registered =
        Json.createObjectBuilder()
            .add("AckNo", 112010036563234L)
            .add("AckDt", "2026-09-16 10:00:00")
            .add("Irn", irn)
            .add("SignedInvoice", "eyJhbGciOiJSUzI1NiJ9.signed-invoice")
            .add("SignedQRCode", "eyJhbGciOiJSUzI1NiJ9.signed-qr")
            .add("Status", "ACT")
            .build()
            .toString();
    return new JsonStub.Answer(
        200,
        Json.createObjectBuilder()
            .add("Status", 1)
            .add(
                "Data",
                Base64.getEncoder()
                    .encodeToString(aes(sek, true, registered.getBytes(StandardCharsets.UTF_8))))
            .addNull("ErrorDetails")
            .add("InfoDtls", "registered")
            .build()
            .toString());
  }

  private static JsonStub.Answer status0(String errors, String info) {
    return new JsonStub.Answer(
        200,
        "{\"Status\":0,\"Data\":null,\"ErrorDetails\":"
            + Json.createValue(errors)
            + ",\"InfoDtls\":"
            + (info == null ? "null" : Json.createValue(info).toString())
            + "}");
  }

  private byte[] rsaOpen(String base64) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      c.init(Cipher.DECRYPT_MODE, keys.getPrivate());
      return c.doFinal(Base64.getDecoder().decode(base64));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] aes(byte[] key, boolean encrypt, byte[] data) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
      c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
      return c.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonObject object(String json) {
    try (JsonReader r = Json.createReader(new StringReader(json))) {
      return r.readObject();
    }
  }
}
