package com.shelfj.notification.service;

import com.shelfj.notification.channel.NotificationChannel;
import com.shelfj.notification.repo.NotificationRepository;

/**
 * {@link Notifier}'s injection points are package-private by design (only {@link NotifierTest}
 * needs them directly). Tests for other classes that need a real, fake-backed {@link Notifier}
 * (e.g. a messaging handler test) go through this factory instead of widening those fields.
 */
public final class NotifierTestSupport {

  private NotifierTestSupport() {}

  public static Notifier notifierOf(NotificationChannel channel, NotificationRepository repo) {
    Notifier notifier = new Notifier();
    notifier.channel = channel;
    notifier.repo = repo;
    return notifier;
  }
}
