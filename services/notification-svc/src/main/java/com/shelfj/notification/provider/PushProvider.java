package com.shelfj.notification.provider;

import java.util.Map;

/**
 * The seam between "push to a device" and whoever delivers it (13.7). One implementation is
 * simulated and always available; the real one needs a project and its credentials.
 */
public interface PushProvider {

  String name();

  boolean isConfigured();

  /**
   * Pushes one message to one device.
   *
   * @param token the provider's device token
   * @param title the notification title
   * @param body the notification body
   * @param data key-value payload for the app
   * @return the provider's id for the message
   * @throws ProviderException with {@link ProviderException#UNREGISTERED} when the provider no
   *     longer knows the device, which the caller takes as "forget it"
   */
  String send(String token, String title, String body, Map<String, String> data);
}
