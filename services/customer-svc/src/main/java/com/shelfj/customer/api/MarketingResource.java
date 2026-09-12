package com.shelfj.customer.api;

import com.shelfj.customer.dto.Dtos.UnsubscribeRequest;
import com.shelfj.customer.service.MarketingConsentService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The opt-out link every marketing message has to carry (PECR reg.23, UK GDPR art.21(3)).
 *
 * <p>Its own resource rather than a route under {@code /customers}, because it is the one thing
 * here that identifies nobody: the token in the link is the whole capability. Requiring a sign-in
 * would be the opposite of the "simple means of refusing" the regulation asks for — the person
 * clicking may be on a different device, may never have had a password, and must not be made to
 * prove who they are in order to be left alone.
 */
@Path("/marketing")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarketingResource {

  @Inject MarketingConsentService marketing;
  @Inject TenantContext ctx;

  /**
   * Stops marketing on one channel, or on every channel.
   *
   * @param req the token from the link, and optionally the single channel to stop
   * @return {@code 200} with how many channels were stopped
   * @throws com.shelfj.web.ApiException {@code 404} when the token is not one this shop issued
   */
  @Operation(
      summary = "Unsubscribe from marketing",
      description =
          "One click, no sign-in: the token in the message is the capability. Omitting the channel"
              + " stops all of them, which is what an unsubscribe link means. Clicking the same"
              + " link twice is not an error — an objection to marketing does not expire.")
  @APIResponse(responseCode = "200", description = "Marketing stopped")
  @APIResponse(responseCode = "400", description = "An unknown channel")
  @APIResponse(responseCode = "404", description = "The token is not one we issued")
  @Tag(name = "Marketing")
  @POST
  @Path("/unsubscribe")
  public ApiResponse<UnsubscribeResult> unsubscribe(UnsubscribeRequest req) {
    Validations.validate(req);
    int stopped = marketing.unsubscribe(req.token(), req.channel());
    return ApiResponse.ok(new UnsubscribeResult(stopped), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * How many channels an unsubscribe stopped.
   *
   * @param channelsStopped the number of channels now set to refuse marketing
   */
  public record UnsubscribeResult(int channelsStopped) {}
}
