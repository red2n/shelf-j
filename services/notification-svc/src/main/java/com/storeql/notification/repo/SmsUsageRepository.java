package com.storeql.notification.repo;

import com.storeql.events.EventPayload;
import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * Announces each text sent, by the parts the carrier charges for, so tenant-svc can meter it
 * against the business's plan (21.10). Through the outbox this service already relays, like its
 * retention runs: the count leaves once, and a relay that retries delivers the same event id, which
 * tenant-svc counts once.
 */
@ApplicationScoped
public class SmsUsageRepository extends BaseOutboxRepository {

  public static final String TOPIC = "storeql.notification.sms-sent";

  /** One text sent for a business, in {@code parts} parts. */
  public void announce(UUID tenantId, int parts) {
    UUID textId = Ids.newId();
    inTx(
        c -> {
          insertOutbox(
              c,
              new OutboxRow(
                  "SmsSent",
                  TOPIC,
                  tenantId,
                  textId,
                  EventPayload.base("SmsSent", tenantId, textId) + ",\"parts\":" + parts + "}"));
          return true;
        },
        "announce a text sent");
  }
}
