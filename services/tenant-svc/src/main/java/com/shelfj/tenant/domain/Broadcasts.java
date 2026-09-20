package com.shelfj.tenant.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What management tells the shop floor, and who has read it (store operations & workforce).
 *
 * <p>A shop runs on notices as much as on lists — the price change on Monday, the recall on the
 * counter, the new closing procedure. Until now the only way to tell every store was a message
 * outside the platform, and the only way to know who had read it was to ask.
 *
 * <p>A notice is <b>never edited</b>: what staff acknowledged is the text they saw, and a notice
 * that changed under its acknowledgements would make every one of them meaningless. A correction
 * withdraws and publishes again, and both stay.
 */
public final class Broadcasts {

  private Broadcasts() {}

  public static final String INFO = "INFO";
  public static final String IMPORTANT = "IMPORTANT";
  public static final String URGENT = "URGENT";
  public static final List<String> PRIORITIES = List.of(INFO, IMPORTANT, URGENT);

  public static final String PUBLISHED = "PUBLISHED";
  public static final String WITHDRAWN = "WITHDRAWN";

  /** How long a notice may be. A notice is not a manual; a manual is a document somewhere else. */
  public static final int MAX_BODY = 4000;

  /**
   * A notice.
   *
   * @param storeId null for every store the business has
   * @param role null for everybody on the store's staff; else the assignment's role or tier
   * @param expiresAt null pins it until withdrawn
   */
  public record Broadcast(
      UUID id,
      UUID tenantId,
      String title,
      String body,
      String priority,
      UUID storeId,
      String role,
      boolean requiresAck,
      Instant publishedAt,
      Instant expiresAt,
      String status,
      UUID createdBy,
      Instant withdrawnAt,
      UUID withdrawnBy,
      String withdrawnReason) {

    public boolean published() {
      return PUBLISHED.equals(status);
    }

    /** Whether the notice still has something to say at a moment. */
    public boolean currentAt(Instant now) {
      return published() && (expiresAt == null || now.isBefore(expiresAt));
    }

    /**
     * Whether the notice is addressed to somebody: at that store, with that role or tier.
     *
     * @param roles the person's roles and tiers at the store, as the staff assignments name them
     */
    public boolean addressedTo(UUID atStore, List<String> roles) {
      if (storeId != null && !storeId.equals(atStore)) return false;
      if (role == null) return true;
      return roles != null && roles.stream().anyMatch(role::equalsIgnoreCase);
    }

    /** Only an urgent notice wakes the store's devices; the rest wait to be read. */
    public boolean wakesDevices() {
      return URGENT.equals(priority);
    }
  }

  /** One person's acknowledgement of a notice. */
  public record Ack(
      UUID id, UUID tenantId, UUID broadcastId, UUID userId, UUID storeId, Instant ackedAt) {}

  /**
   * What a manager sees of a notice at one store: how many of its staff have acknowledged, and who
   * has not.
   *
   * @param outstanding the people on the store's staff the notice is addressed to who have not
   *     acknowledged it — named, because "three of nine" is a number and a manager needs a name
   */
  public record Reach(UUID storeId, int addressed, int acknowledged, List<UUID> outstanding) {

    public Reach {
      outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }

    public boolean complete() {
      return outstanding.isEmpty();
    }
  }

  /**
   * What is wrong with a notice as somebody asked for it, or null when nothing is.
   *
   * <p>An expiry before publication is refused rather than clamped: a notice that expires the
   * moment it is published tells nobody anything and reads as having been sent.
   */
  public static String problem(
      String title, String body, String priority, Instant publishedAt, Instant expiresAt) {
    if (title == null || title.isBlank()) return "a notice needs a title";
    if (body == null || body.isBlank()) return "a notice needs something to say";
    if (body.length() > MAX_BODY) {
      return "a notice says it in "
          + MAX_BODY
          + " characters or fewer; a longer thing is a document";
    }
    if (priority == null || !PRIORITIES.contains(priority)) {
      return "a notice is INFO, IMPORTANT or URGENT";
    }
    if (expiresAt != null && publishedAt != null && !expiresAt.isAfter(publishedAt)) {
      return "a notice expires after it is published, or it tells nobody anything";
    }
    return null;
  }
}
