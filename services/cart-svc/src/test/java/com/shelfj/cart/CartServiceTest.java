package com.shelfj.cart;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.cart.domain.Domain.Cart;
import com.shelfj.cart.domain.Domain.CartItem;
import com.shelfj.cart.dto.Dtos.AddItemRequest;
import com.shelfj.cart.dto.Dtos.CreateCartRequest;
import com.shelfj.cart.repo.CartRepository;
import com.shelfj.cart.service.CartService;
import com.shelfj.service.StoreStatusRepository;
import com.shelfj.service.TenantStatusRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CartService core rules. Repositories are replaced with simple stubs so no DB is
 * needed here; the Testcontainers integration test covers the real DB path.
 */
class CartServiceTest {

  private final UUID TENANT = UUID.randomUUID();
  private final UUID STORE = UUID.randomUUID();
  private final UUID CUSTOMER = UUID.randomUUID();

  private CartService service;

  // Simple in-memory stubs ──────────────────────────────────────────────────

  private boolean tenantActive = true;
  private boolean storeActive = true;
  private Cart insertedCart = null;
  private CartItem insertedItem = null;

  @BeforeEach
  void setUp() {
    TenantStatusRepository tenantRepo =
        new TenantStatusRepository() {
          @Override
          public boolean isActive(UUID tenantId) {
            return tenantActive;
          }

          @Override
          public void upsertTenantStatus(UUID t, String s, Instant i) {}
        };

    StoreStatusRepository storeRepo =
        new StoreStatusRepository() {
          @Override
          public boolean isActive(UUID storeId) {
            return storeActive;
          }

          @Override
          public void upsertStoreStatus(UUID s, UUID t, String st, Instant i) {}
        };

    CartRepository cartRepo =
        new CartRepository() {
          @Override
          public Cart insert(Cart c) {
            insertedCart = c;
            return c;
          }

          @Override
          public Optional<Cart> findActiveByCustomer(UUID tenantId, UUID customerId) {
            return Optional.empty();
          }

          @Override
          public Optional<Cart> findActiveBySession(UUID tenantId, String sessionId) {
            return Optional.empty();
          }

          @Override
          public Optional<Cart> findById(UUID tenantId, UUID cartId) {
            return insertedCart != null ? Optional.of(insertedCart) : Optional.empty();
          }

          @Override
          public List<CartItem> findItemsByCart(UUID tenantId, UUID cartId) {
            return insertedItem != null ? List.of(insertedItem) : List.of();
          }

          @Override
          public CartItem upsertItem(CartItem item) {
            insertedItem = item;
            return item;
          }
        };

    service = new CartService();
    // Inject stubs via field assignment (CDI not needed for unit tests).
    setField(service, "repo", cartRepo);
    setField(service, "tenantStatusRepo", tenantRepo);
    setField(service, "storeStatusRepo", storeRepo);
  }

  // ── Flow guard ────────────────────────────────────────────────────────────

  @Test
  void createCart_blockedWhenTenantSuspended() {
    tenantActive = false;
    var ctx = ctx(TENANT, CUSTOMER);
    var req = new CreateCartRequest(STORE.toString(), null);

    ApiException ex = assertThrows(ApiException.class, () -> service.createOrGetCart(ctx, req));
    assertThat(ex.code(), is("TENANT_NOT_OPERATIONAL"));
  }

  @Test
  void createCart_blockedWhenStoreClosed() {
    storeActive = false;
    var ctx = ctx(TENANT, CUSTOMER);
    var req = new CreateCartRequest(STORE.toString(), null);

    ApiException ex = assertThrows(ApiException.class, () -> service.createOrGetCart(ctx, req));
    assertThat(ex.code(), is("STORE_NOT_OPERATIONAL"));
  }

  @Test
  void addItem_blockedWhenTenantSuspended() {
    // Pre-create cart in active state.
    insertedCart =
        new Cart(
            UUID.randomUUID(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    tenantActive = false;
    var ctx = ctx(TENANT, CUSTOMER);
    var req =
        new AddItemRequest(
            insertedCart.id().toString(), UUID.randomUUID().toString(), BigDecimal.ONE, null, null);

    ApiException ex = assertThrows(ApiException.class, () -> service.addItem(ctx, req));
    assertThat(ex.code(), is("TENANT_NOT_OPERATIONAL"));
  }

  // ── Happy path ────────────────────────────────────────────────────────────

  @Test
  void createCart_createsNewCartForAuthenticatedCustomer() {
    var ctx = ctx(TENANT, CUSTOMER);
    var req = new CreateCartRequest(STORE.toString(), null);

    var response = service.createOrGetCart(ctx, req);

    assertThat(response.status(), is(Cart.STATUS_ACTIVE));
    assertThat(response.storeId(), is(STORE.toString()));
  }

  // ── IDOR guard (finding #4) ──────────────────────────────────────────────

  @Test
  void addItem_blockedForCustomerWhoDoesNotOwnTheCart() {
    insertedCart =
        new Cart(
            UUID.randomUUID(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    UUID otherCustomer = UUID.randomUUID();
    var ctx = ctx(TENANT, otherCustomer);
    var req =
        new AddItemRequest(
            insertedCart.id().toString(), UUID.randomUUID().toString(), BigDecimal.ONE, null, null);

    ApiException ex = assertThrows(ApiException.class, () -> service.addItem(ctx, req));
    assertThat(ex.code(), is("CART_NOT_FOUND"));
  }

  @Test
  void addItem_allowedForStaffOnAnyCustomersCart() {
    insertedCart =
        new Cart(
            UUID.randomUUID(),
            TENANT,
            CUSTOMER,
            null,
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    var ctx = ctx(TENANT, UUID.randomUUID(), Set.of("CASHIER"));
    var req =
        new AddItemRequest(
            insertedCart.id().toString(), UUID.randomUUID().toString(), BigDecimal.ONE, null, null);

    // must not throw — staff may operate on any cart in the tenant
    service.addItem(ctx, req);
  }

  @Test
  void addItem_guestCartRequiresMatchingSessionId() {
    insertedCart =
        new Cart(
            UUID.randomUUID(),
            TENANT,
            null,
            "guest-session-abc",
            STORE,
            Cart.STATUS_ACTIVE,
            Instant.now(),
            Instant.now());
    var ctx = ctx(TENANT, null);
    var wrongSession =
        new AddItemRequest(
            insertedCart.id().toString(),
            UUID.randomUUID().toString(),
            BigDecimal.ONE,
            null,
            "not-the-right-session");

    ApiException ex = assertThrows(ApiException.class, () -> service.addItem(ctx, wrongSession));
    assertThat(ex.code(), is("CART_NOT_FOUND"));

    var rightSession =
        new AddItemRequest(
            insertedCart.id().toString(),
            UUID.randomUUID().toString(),
            BigDecimal.ONE,
            null,
            "guest-session-abc");
    service.addItem(ctx, rightSession); // must not throw
  }

  @Test
  void createCart_mintsHighEntropySessionForGuest() {
    var ctx = ctx(TENANT, null); // guest: no customer identity
    var req = new CreateCartRequest(STORE.toString(), null); // no client session supplied

    var response = service.createOrGetCart(ctx, req);

    // Server minted a session token (256-bit → 43-char base64url) and stored it on the cart.
    assertThat(response.customerId(), is((String) null));
    org.junit.jupiter.api.Assertions.assertNotNull(response.sessionId());
    org.junit.jupiter.api.Assertions.assertTrue(
        response.sessionId().length() >= 40, "session token must be high-entropy");
    assertThat(insertedCart.sessionId(), is(response.sessionId()));
  }

  @Test
  void createCart_ignoresClientChosenSessionWhenCreatingNewGuestCart() {
    var ctx = ctx(TENANT, null);
    // A client tries to name its own (weak, guessable) session. No cart exists for it
    // (stub findActiveBySession returns empty), so the server must NOT create one under
    // that id — it mints its own instead.
    var req = new CreateCartRequest(STORE.toString(), "weak-guessable-123");

    var response = service.createOrGetCart(ctx, req);

    org.junit.jupiter.api.Assertions.assertNotEquals("weak-guessable-123", response.sessionId());
    org.junit.jupiter.api.Assertions.assertTrue(response.sessionId().length() >= 40);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static TenantContext ctx(UUID tenantId, UUID userId) {
    return ctx(tenantId, userId, Set.of());
  }

  private static TenantContext ctx(UUID tenantId, UUID userId, Set<String> roles) {
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        return tenantId;
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }

      @Override
      public UUID userId() {
        return userId;
      }

      @Override
      public boolean hasRole(String role) {
        return roles.contains(role);
      }
    };
  }

  private static void setField(Object target, String name, Object value) {
    try {
      var f = findField(target.getClass(), name);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("cannot inject field " + name, e);
    }
  }

  private static java.lang.reflect.Field findField(Class<?> cls, String name) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      for (var f : c.getDeclaredFields()) {
        if (f.getName().equals(name)) return f;
      }
    }
    throw new RuntimeException("field not found: " + name);
  }
}
