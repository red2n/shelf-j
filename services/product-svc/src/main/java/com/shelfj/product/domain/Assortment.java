package com.shelfj.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Range: which stores carry a line, and the review that decides (07.18).
 *
 * <p>Both halves of this had something already. {@code product_stores} has recorded which stores
 * carry a product since V13, and the item lifecycle has launched, discontinued and reinstated lines
 * against a date since V24. Neither is replaced here.
 *
 * <p>What was missing was the same thing twice: <b>the decision, with a date and a reason and the
 * comparison it was made against.</b> A line was ranged or dropped store by store, on somebody's
 * judgement, and nothing recorded why or what it was weighed against — so nobody could answer "who
 * took this out of the Scottish shops, and on what evidence" six months later.
 *
 * <p>Two consequences run through the design. A change is recorded <em>before</em> it takes effect,
 * so a range is planned rather than typed on the morning it happens — which makes {@code
 * product_stores} the state and this the intent. And a review's figures are a <b>snapshot</b>:
 * sales belong to order-svc and reporting-svc, product-svc reads neither, and a snapshot is the
 * better record anyway, because a report re-run next year shows different numbers and makes an old
 * decision look arbitrary.
 */
public final class Assortment {

  private Assortment() {}

  public static final String ACTIVE = "ACTIVE";
  public static final String RETIRED = "RETIRED";

  /**
   * A named group of stores to range against.
   *
   * <p>The practical complaint about per-store assortment was never that it could not express a
   * range — it is that expressing one costs a row per store. A chain ranges by type: city
   * convenience, superstore, the ten shops with a fish counter. A store may belong to several
   * clusters, deliberately: "Scotland" and "has a bakery" are both true of the same shop, and one
   * grouping would make one of them unsayable.
   */
  public record Cluster(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      String note,
      String status,
      Instant createdAt,
      Instant updatedAt,
      List<UUID> storeIds) {

    public Cluster {
      storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }

    public boolean active() {
      return ACTIVE.equals(status);
    }
  }

  public static final String LIST = "LIST";
  public static final String DELIST = "DELIST";
  public static final Set<String> ACTIONS = Set.of(LIST, DELIST);

  /**
   * A dated, reasoned decision about where a line is ranged.
   *
   * @param storeId set when the change is aimed at one store; null when it is aimed at a cluster
   * @param clusterId set when it is aimed at a group; exactly one of the two
   * @param appliedAt when the change was actually pushed into {@code product_stores}. Null means it
   *     is still intent — which is the whole point of having a date
   * @param reviewId the review that produced it, when it came from one rather than a single
   *     decision
   */
  public record Change(
      UUID id,
      UUID tenantId,
      UUID productId,
      UUID storeId,
      UUID clusterId,
      String action,
      LocalDate effectiveFrom,
      String reason,
      UUID decidedBy,
      Instant createdAt,
      Instant appliedAt,
      UUID reviewId) {

    public boolean applied() {
      return appliedAt != null;
    }

    /** Whether this change is due: dated on or before the day asked about, and not yet applied. */
    public boolean due(LocalDate asOf) {
      return !applied() && !effectiveFrom.isAfter(asOf);
    }
  }

  // ── range review ────────────────────────────────────────────────────────────

  public static final String OPEN = "OPEN";
  public static final String DECIDED = "DECIDED";
  public static final String ABANDONED = "ABANDONED";

  public static final String KEEP = "KEEP";
  public static final String INTRODUCE = "INTRODUCE";
  public static final Set<String> DECISIONS = Set.of(KEEP, DELIST, INTRODUCE);

  /** A category looked at over a trading period. */
  public record Review(
      UUID id,
      UUID tenantId,
      UUID categoryId,
      String name,
      LocalDate periodFrom,
      LocalDate periodTo,
      String status,
      String note,
      Instant createdAt,
      Instant decidedAt,
      List<Line> lines) {

    public Review {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean open() {
      return OPEN.equals(status);
    }

    /** How many lines still have no decision — what stops a review being closed. */
    public long undecided() {
      return lines.stream().filter(l -> l.decision() == null).count();
    }

    /** The lines a closing review will act on: the ones marked to drop or to bring in. */
    public List<Line> actionable() {
      return lines.stream()
          .filter(l -> l.decision() != null && !KEEP.equals(l.decision()))
          .toList();
    }
  }

  /**
   * One line under review, with the figures it was judged on.
   *
   * @param ownBrand recorded on the row rather than looked up later, because own-brand status
   *     changes and the review should show the reason as it applied on the day
   * @param rankInCategory where it came on whatever the buyer ranked by; 1 is best
   */
  public record Line(
      UUID id,
      UUID tenantId,
      UUID reviewId,
      UUID variantId,
      BigDecimal unitsSold,
      BigDecimal revenue,
      BigDecimal margin,
      String currency,
      Integer rankInCategory,
      String decision,
      String decisionNote,
      boolean ownBrand) {}

  /**
   * Whether dropping this line needs somebody to say more than "it sold badly".
   *
   * <p>An own-brand line is the business's own margin and its own shelf presence, so a review that
   * drops one on rank alone is usually a mistake — the remedy for a poor own-brand line is more
   * often a reformulation or a price than a de-list. Not a refusal: a buyer may well be right. It
   * asks for a note, which is the difference between a decision and a reflex.
   */
  public static boolean needsJustification(Line line) {
    return DELIST.equals(line.decision()) && line.ownBrand();
  }

  // ── from variant decisions to a product's range ─────────────────────────────

  /** A range action derived for one product from the decisions taken on its variants. */
  public record ProductAction(UUID productId, String action) {}

  /** A product a closing review deliberately did not touch, and why. */
  public record LeftAlone(UUID productId, String reason) {}

  /** What closing a review comes to: the changes it produces, and what it left alone. */
  public record RangeOutcome(List<ProductAction> actions, List<LeftAlone> leftAlone) {

    public RangeOutcome {
      actions = actions == null ? List.of() : List.copyOf(actions);
      leftAlone = leftAlone == null ? List.of() : List.copyOf(leftAlone);
    }
  }

  static final String KEPT_SIBLING =
      "Another variant of this product is kept, so the product stays ranged";
  static final String UNREVIEWED_SIBLING =
      "The review did not cover every variant of this product, so de-listing it would drop a line"
          + " nobody looked at";

  /**
   * Turns variant-level review decisions into product-level range actions.
   *
   * <p>The two levels do not line up, and pretending they do is the bug worth avoiding: a review
   * reads <em>variants</em>, because that is what sells and what gets ranked, while {@code
   * product_stores} ranges a <em>product</em>. Three rules bridge them, and each one exists because
   * the naive reading loses a line somebody is still selling.
   *
   * <ol>
   *   <li><b>Bringing one in wins.</b> If any variant is marked INTRODUCE the product is listed,
   *       even where a sibling is being dropped — a new flavour replacing an old one must not
   *       de-list the product on its way in.
   *   <li><b>Dropping needs every variant.</b> A product is de-listed only when every one of its
   *       variants was reviewed and every one was marked DELIST. Otherwise the drop would take
   *       lines the buyer never looked at off the shelf with it.
   *   <li><b>KEEP does nothing.</b> The line is already ranged; a change per untouched line would
   *       fill the log with no-ops and hide the decisions that matter.
   * </ol>
   *
   * @param productByVariant which product each reviewed variant belongs to
   * @param variantsPerProduct how many live variants each product has <em>now</em> — the count that
   *     decides whether the review covered all of them
   */
  public static RangeOutcome rangeActions(
      List<Line> lines, Map<UUID, UUID> productByVariant, Map<UUID, Integer> variantsPerProduct) {
    Map<UUID, List<String>> byProduct = new LinkedHashMap<>();
    for (Line l : lines) {
      if (l.decision() == null) continue;
      UUID productId = productByVariant.get(l.variantId());
      if (productId == null) continue;
      byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(l.decision());
    }

    List<ProductAction> actions = new ArrayList<>();
    List<LeftAlone> leftAlone = new ArrayList<>();
    for (Map.Entry<UUID, List<String>> e : byProduct.entrySet()) {
      List<String> decisions = e.getValue();
      if (decisions.contains(INTRODUCE)) {
        actions.add(new ProductAction(e.getKey(), LIST));
      } else if (decisions.stream().allMatch(DELIST::equals)) {
        int live = variantsPerProduct.getOrDefault(e.getKey(), decisions.size());
        if (decisions.size() >= live) {
          actions.add(new ProductAction(e.getKey(), DELIST));
        } else {
          leftAlone.add(new LeftAlone(e.getKey(), UNREVIEWED_SIBLING));
        }
      } else if (decisions.contains(DELIST)) {
        leftAlone.add(new LeftAlone(e.getKey(), KEPT_SIBLING));
      }
    }
    return new RangeOutcome(actions, leftAlone);
  }
}
