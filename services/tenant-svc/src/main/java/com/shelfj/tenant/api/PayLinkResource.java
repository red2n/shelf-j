package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.BillingDtos;
import com.shelfj.tenant.mapper.BillingMappers;
import com.shelfj.tenant.service.DunningService;
import com.shelfj.web.ApiResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code POST /billing/pay/{token}}: the way back for a business that cannot sign in (21.12).
 *
 * <p><b>Unauthenticated by design</b>, and this is the one route in the service that is. By the
 * time the later dunning notices go out the business has been suspended, which means it cannot sign
 * in — telling it to pay while denying it the means is not a dunning process, it is a dead end. The
 * same reasoning as the marketing opt-out link (PECR reg.23), which is also reachable without a
 * token because requiring one would defeat what the link is for.
 *
 * <p>The token <em>is</em> the capability and it is deliberately narrow: it names one invoice and
 * the only thing it can do is pay it. It is 256 bits of randomness, only its hash is stored, and a
 * token for an invoice that is no longer open opens nothing — so a link from an old notice cannot
 * pay twice. See {@link com.shelfj.service.CapabilityTokens}.
 *
 * <p>Three things have to agree for this to be reachable at all, and if any one of them is missed a
 * suspended business is told to pay and cannot: this route, the gateway's public-path list, and the
 * authorisation filter's open-mutation list. Each carries a comment pointing at the other two.
 */
@Path("/billing/pay")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Billing")
public class PayLinkResource {

  @Inject DunningService dunning;

  /**
   * Pays the one invoice this token names, in full.
   *
   * <p>No role is asserted, deliberately — see the class doc. There is nothing to assert: the
   * caller has no session and the token is the whole permission.
   *
   * @param token the raw token from the link in a notice
   * @return the invoice, settled
   * @throws com.shelfj.web.ApiException 404 {@code PAY_LINK_INVALID} for a token this platform did
   *     not issue <em>and</em> for one whose invoice is no longer open — the same answer either
   *     way, because telling them apart would tell whoever is guessing which half they got right
   */
  @Operation(
      summary = "Pays an invoice from the link in a dunning notice",
      description =
          "Unauthenticated, because a suspended business cannot sign in. The token names one invoice"
              + " and pays it in full; a token for an invoice no longer open opens nothing, so an old"
              + " link cannot pay twice. Paying everything owed brings the business back — but only"
              + " one the platform suspended for non-payment, never one an administrator switched"
              + " off.")
  @POST
  @Path("/{token}")
  public ApiResponse<BillingDtos.InvoiceResponse> pay(@PathParam("token") String token) {
    return ApiResponse.ok(BillingMappers.toDto(dunning.payByLink(token, null)));
  }
}
