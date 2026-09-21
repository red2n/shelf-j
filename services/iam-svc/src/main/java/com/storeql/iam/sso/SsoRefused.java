package com.storeql.iam.sso;

/**
 * An identity provider, or what it sent, that single sign-on cannot go on with. The code is stable
 * and is what a person is shown; the message is for the log and never carries a provider's answer
 * back to whoever asked, because a provider's answer is whatever the issuer URL a business typed
 * points at.
 */
public class SsoRefused extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The provider could not be reached, or did not answer in time. */
  public static final String UNREACHABLE = "SSO_PROVIDER_UNREACHABLE";

  /** The address is one this service will not call: not HTTPS, or inside the network. */
  public static final String ADDRESS_REFUSED = "SSO_PROVIDER_ADDRESS_REFUSED";

  /** The discovery document is missing, malformed, or names another issuer. */
  public static final String DISCOVERY_INVALID = "SSO_DISCOVERY_INVALID";

  /** The provider refused the exchange: a wrong client secret, a code already spent. */
  public static final String EXCHANGE_REFUSED = "SSO_PROVIDER_REFUSED";

  /** The ID token failed a check: signature, issuer, audience, time or nonce. */
  public static final String ID_TOKEN_INVALID = "SSO_ID_TOKEN_INVALID";

  private final String code;

  public SsoRefused(String code, String message) {
    super(message);
    this.code = code;
  }

  public SsoRefused(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
