package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.shelfj.ids.Ids;
import com.shelfj.notification.service.Notifier;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UserRegisteredHandler parses the event and sends a WELCOME notification to the email carried on
 * it; a missing email or malformed payload is skipped without throwing. A capturing Notifier stands
 * in (no mocking framework here).
 */
class UserRegisteredHandlerTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();

  private static final class CapturingNotifier extends Notifier {
    int calls;
    UUID eventId;
    String type;
    UUID subjectId;
    String recipient;

    @Override
    public void notifyOnce(
        UUID eventId,
        String type,
        UUID tenantId,
        UUID subjectId,
        String recipient,
        String subject,
        String body) {
      this.calls++;
      this.eventId = eventId;
      this.type = type;
      this.subjectId = subjectId;
      this.recipient = recipient;
    }
  }

  private CapturingNotifier notifier;
  private UserRegisteredHandler handler;

  @BeforeEach
  void setUp() {
    notifier = new CapturingNotifier();
    handler = new UserRegisteredHandler();
    handler.notifier = notifier;
  }

  @Test
  void sendsWelcomeToTheRegisteredEmail() {
    UUID user = Ids.newId();
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"UserRegistered\",\"tenantId\":\""
            + TENANT
            + "\",\"aggregateId\":\""
            + user
            + "\",\"email\":\"newuser@example.com\",\"type\":\"CUSTOMER\"}";

    handler.handle(json);

    assertEquals(1, notifier.calls);
    assertEquals(EVENT, notifier.eventId);
    assertEquals("WELCOME", notifier.type);
    assertEquals("newuser@example.com", notifier.recipient);
    // SJ-D43: the welcome records whose account it was, so deleting the account can erase it.
    assertEquals(user, notifier.subjectId);
  }

  @Test
  void missingEmailIsSkipped() {
    String json =
        "{\"eventId\":\"" + EVENT + "\",\"eventType\":\"UserRegistered\",\"type\":\"CUSTOMER\"}";

    handler.handle(json);

    assertEquals(0, notifier.calls);
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    handler.handle("{not valid json");

    assertEquals(0, notifier.calls);
  }
}
