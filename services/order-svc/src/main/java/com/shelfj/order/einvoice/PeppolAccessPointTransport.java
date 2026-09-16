package com.shelfj.order.einvoice;

import com.shelfj.order.domain.EInvoiceTransports;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A Peppol access point, through the HTTP facade the certified providers offer in front of AS4: a
 * document is posted with its sender and receiver participant identifiers, the Peppol document type
 * and process it travels under, and the UBL; the access point looks the receiver up in the SMP,
 * signs and delivers, and answers with a reference to ask after. Configured with the platform's
 * base URL and key ({@code shelfj.einvoice.peppol.base-url}, {@code .api-key}); without them it is
 * deployed but cannot be chosen. A contract with a certified provider is not code.
 */
@ApplicationScoped
public class PeppolAccessPointTransport implements EInvoiceTransport {

  public static final String NAME = "ACCESS_POINT";

  static final String PROCESS_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
  static final String INVOICE_TYPE_ID =
      "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice"
          + "##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";
  static final String CREDIT_NOTE_TYPE_ID =
      "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2::CreditNote"
          + "##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";

  @Inject
  @ConfigProperty(name = "shelfj.einvoice.peppol.base-url")
  Optional<String> baseUrlConfig;

  @Inject
  @ConfigProperty(name = "shelfj.einvoice.peppol.api-key")
  Optional<String> apiKeyConfig;

  /** Resolved at startup; package-private so a test can point at a stub. */
  String baseUrl = "";

  String apiKey = "";

  private WebClient web;

  @PostConstruct
  void init() {
    baseUrl = baseUrlConfig.orElse("").strip();
    apiKey = apiKeyConfig.orElse("").strip();
    web = client();
  }

  /** For tests: a transport pointed at a stub, with no CDI. */
  static PeppolAccessPointTransport forTest(String baseUrl, String apiKey) {
    PeppolAccessPointTransport t = new PeppolAccessPointTransport();
    t.baseUrl = baseUrl;
    t.apiKey = apiKey;
    t.web = client();
    return t;
  }

  private static WebClient client() {
    return WebClient.builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(15))
        .build();
  }

  @Override
  public Set<String> networks() {
    return Set.of(EInvoiceTransports.NETWORK_PEPPOL);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return !baseUrl.isBlank() && !apiKey.isBlank();
  }

  @Override
  public String configuration() {
    return "shelfj.einvoice.peppol.base-url and shelfj.einvoice.peppol.api-key";
  }

  @Override
  public Dispatch send(Outbound d) {
    JsonObject body =
        Json.createObjectBuilder()
            .add("sender", d.sender() == null ? "" : d.sender())
            .add("receiver", d.receiver() == null ? "" : d.receiver())
            .add(
                "documentTypeId",
                "CreditNote".equals(d.documentKind()) ? CREDIT_NOTE_TYPE_ID : INVOICE_TYPE_ID)
            .add("processId", PROCESS_ID)
            .add("documentNumber", d.number() == null ? "" : d.number())
            .add(
                "document",
                Base64.getEncoder().encodeToString(d.ubl().getBytes(StandardCharsets.UTF_8)))
            .build();
    try (HttpClientResponse res =
        web.post(baseUrl + "/documents")
            .header(HeaderNames.AUTHORIZATION, "Bearer " + apiKey)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .header(HeaderNames.ACCEPT, "application/json")
            .submit(body.toString())) {
      int status = res.status().code();
      String answer = res.as(String.class);
      if (status >= 200 && status < 300) {
        JsonObject o = object(answer);
        String ref = o.getString("id", null);
        if (ref == null || ref.isBlank()) {
          throw new TransportException("the access point answered without a document id", null);
        }
        return new Dispatch(ref, outcome(o, answer));
      }
      if (status == 400 || status == 404 || status == 409 || status == 422) {
        return new Dispatch(null, Outcome.rejected(reason(answer, status), answer));
      }
      throw new TransportException("the access point answered HTTP " + status, null);
    } catch (TransportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TransportException("the access point could not be reached: " + e.getMessage(), e);
    }
  }

  @Override
  public Outcome status(Outbound d, String providerRef) {
    try (HttpClientResponse res =
        web.get(baseUrl + "/documents/" + providerRef)
            .header(HeaderNames.AUTHORIZATION, "Bearer " + apiKey)
            .header(HeaderNames.ACCEPT, "application/json")
            .request()) {
      int status = res.status().code();
      String answer = res.as(String.class);
      if (status == 200) return outcome(object(answer), answer);
      if (status == 404) {
        return Outcome.rejected("the access point no longer knows document " + providerRef, answer);
      }
      throw new TransportException("the access point answered HTTP " + status, null);
    } catch (TransportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TransportException("the access point could not be reached: " + e.getMessage(), e);
    }
  }

  /** The access point's state words, as the facades use them: delivered, failed, or on the way. */
  private static Outcome outcome(JsonObject o, String answer) {
    String state = o.getString("status", "").toUpperCase(Locale.ROOT);
    String reason = o.getString("reason", o.getString("message", ""));
    return switch (state) {
      case "DELIVERED", "ACCEPTED", "RECEIVED" -> Outcome.accepted("delivered", answer);
      case "FAILED", "REJECTED", "ERROR" ->
          Outcome.rejected(reason.isBlank() ? "the network refused the document" : reason, answer);
      default -> Outcome.pending(reason.isBlank() ? "taken; awaiting delivery" : reason, answer);
    };
  }

  private static String reason(String answer, int status) {
    try {
      JsonObject o = object(answer);
      String reason = o.getString("reason", o.getString("message", ""));
      if (!reason.isBlank()) return reason;
      JsonObject error = o.getJsonObject("error");
      if (error != null) return error.getString("message", "refused with HTTP " + status);
    } catch (RuntimeException ignored) {
      // not JSON: the status is all there is
    }
    return "refused with HTTP " + status;
  }

  private static JsonObject object(String json) {
    try (JsonReader r = Json.createReader(new StringReader(json == null ? "{}" : json))) {
      return r.readObject();
    }
  }
}
