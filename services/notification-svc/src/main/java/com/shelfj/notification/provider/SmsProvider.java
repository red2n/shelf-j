package com.shelfj.notification.provider;

/**
 * The seam between "send a text" and whoever carries it (13.7). One implementation is simulated and
 * always available; the real ones need an account, which is configuration a deployment supplies —
 * the same shape as the payment and the fiscal providers.
 */
public interface SmsProvider {

  String name();

  boolean isConfigured();

  /**
   * Sends one text.
   *
   * @param to the recipient in E.164 form
   * @param body the text
   * @return the provider's id for the message
   * @throws ProviderException when the provider refused or could not be reached
   */
  String send(String to, String body);
}
