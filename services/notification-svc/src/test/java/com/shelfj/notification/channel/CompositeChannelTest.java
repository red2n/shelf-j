package com.shelfj.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CompositeChannelTest {

  private static final class CountingChannel implements NotificationChannel {
    final String id;
    int sends;
    boolean fail;

    CountingChannel(String id) {
      this.id = id;
    }

    @Override
    public String name() {
      return id;
    }

    @Override
    public void send(String recipient, String subject, String body) {
      if (fail) throw new IllegalStateException(id + " boom");
      sends++;
    }
  }

  @Test
  void sendsInAppThenExternalAndReportsExternalName() {
    CountingChannel app = new CountingChannel("APP");
    CountingChannel smtp = new CountingChannel("SMTP");
    CompositeChannel composite = new CompositeChannel(app, smtp);

    assertEquals("SMTP", composite.name());
    composite.send("a@b.com", "Hi", "body");
    assertEquals(1, app.sends);
    assertEquals(1, smtp.sends);
  }

  @Test
  void externalFailurePropagatesAfterInApp() {
    CountingChannel app = new CountingChannel("APP");
    CountingChannel smtp = new CountingChannel("SMTP");
    smtp.fail = true;
    CompositeChannel composite = new CompositeChannel(app, smtp);

    assertThrows(IllegalStateException.class, () -> composite.send("a@b.com", "Hi", "body"));
    assertEquals(1, app.sends, "in-app still ran before external failure");
    assertEquals(0, smtp.sends);
  }
}
