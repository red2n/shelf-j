package com.shelfj.notification.provider;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Which SMS and push provider this deployment uses (13.7): every implementation CDI knows, chosen
 * by name from configuration, simulated unless told otherwise. A name that is configured but not
 * ready (a real provider without its credentials) is reported as such rather than used.
 */
@ApplicationScoped
public class Providers {

  @Inject Instance<SmsProvider> smsDiscovered;
  @Inject Instance<PushProvider> pushDiscovered;

  @Inject
  @ConfigProperty(
      name = "shelfj.notification.sms.provider",
      defaultValue = SimulatedSmsProvider.NAME)
  String smsName;

  @Inject
  @ConfigProperty(
      name = "shelfj.notification.push.provider",
      defaultValue = SimulatedPushProvider.NAME)
  String pushName;

  private final Map<String, SmsProvider> sms = new HashMap<>();
  private final Map<String, PushProvider> push = new HashMap<>();

  @PostConstruct
  void init() {
    for (SmsProvider p : smsDiscovered) sms.put(p.name().toUpperCase(Locale.ROOT), p);
    for (PushProvider p : pushDiscovered) push.put(p.name().toUpperCase(Locale.ROOT), p);
  }

  /** The configured SMS provider, or null when the name is unknown. */
  public SmsProvider sms() {
    return sms.get(smsName.trim().toUpperCase(Locale.ROOT));
  }

  /** The configured push provider, or null when the name is unknown. */
  public PushProvider push() {
    return push.get(pushName.trim().toUpperCase(Locale.ROOT));
  }

  public List<String> smsAvailable() {
    return sms.values().stream()
        .filter(SmsProvider::isConfigured)
        .map(SmsProvider::name)
        .sorted()
        .toList();
  }

  public List<String> pushAvailable() {
    return push.values().stream()
        .filter(PushProvider::isConfigured)
        .map(PushProvider::name)
        .sorted()
        .toList();
  }
}
