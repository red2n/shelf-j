package com.storeql.notification.channel;

import com.storeql.ids.Ids;
import com.storeql.notification.domain.Domain.PushDevice;
import com.storeql.notification.provider.ProviderException;
import com.storeql.notification.provider.Providers;
import com.storeql.notification.provider.PushProvider;
import com.storeql.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Push (13.7). The recipient is a login; the message goes to every device that login registered
 * with this shop. A device the provider no longer knows is forgotten on the spot, the way every
 * push integration has to, or the dead tokens pile up and every send pays for them.
 */
@ApplicationScoped
@Typed(PushChannel.class)
public class PushChannel implements NotificationChannel {

  private static final Logger LOG = System.getLogger(PushChannel.class.getName());

  /** Thrown when the login has no device to push to: nothing was sent, and nothing could be. */
  public static final class NoDeviceException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    NoDeviceException(UUID userId) {
      super("no device registered for login " + userId);
    }
  }

  @Inject Providers providers;
  @Inject NotificationRepository repo;

  @Override
  public String name() {
    return "PUSH";
  }

  public PushProvider provider() {
    return providers.push();
  }

  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    UUID userId = Ids.parse(recipient);
    List<PushDevice> devices = repo.devicesFor(tenantId, userId);
    if (devices.isEmpty()) {
      throw new NoDeviceException(userId);
    }
    PushProvider p = provider();
    if (p == null || !p.isConfigured()) {
      throw new IllegalStateException("no push provider is configured");
    }
    int delivered = 0;
    ProviderException last = null;
    for (PushDevice d : devices) {
      try {
        p.send(d.token(), subject, body, Map.of("tenantId", tenantId.toString()));
        delivered++;
      } catch (ProviderException e) {
        if (ProviderException.UNREGISTERED.equals(e.code())) {
          repo.forgetDevice(tenantId, d.id());
          LOG.log(Level.INFO, "Device {0} forgotten: the provider no longer knows it", d.id());
        } else {
          last = e;
          LOG.log(Level.WARNING, "Push to device {0} failed: {1}", d.id(), e.getMessage());
        }
      }
    }
    if (delivered == 0) {
      if (last != null) throw last;
      throw new NoDeviceException(userId);
    }
  }
}
