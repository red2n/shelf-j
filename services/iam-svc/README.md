# IAM Service (iam-svc)

**Type:** Business Service  
**Port (dev):** 8001  
**Repository:** `services/iam-svc/`  
**Role:** User management, authentication (register/login), JWT token issuance, role management

---

## Quick Overview

| Aspect | Details |
|--------|---------|
| **Responsibility** | Create users, authenticate them, issue JWTs, manage roles per tenant |
| **Primary Domain** | Identity & Access Management |
| **Key Tables** | `users`, `user_roles`, `user_tenants`, `password_reset_tokens` |
| **Events Published** | `UserRegistered`, `UserLoggedIn`, `UserRoleGranted`, `UserRoleRevoked` |
| **Events Consumed** | `TenantCreated` (to record user-tenant relationship), `UserRoleGranted` (from tenant-svc) |
| **Sync Dependencies** | None (doesn't call other services; other services call it for role info) |
| **Technology** | Helidon MP, Auth0 JWT, BCrypt password hashing |

---

## 1. How Requests Reach IAM Service

### 1.1 Request Flow for Authentication

```
Client (Storefront/Admin/POS)
  │
  ├─ Request 1: POST /api/iam-svc/auth/register
  │   ├─ Headers: Content-Type: application/json
  │   ├─ Body: { email, password, phone }
  │   │   (NO Authorization required - public endpoint)
  │   │
  │   ↓ (Through Gateway, no JWT validation - public path)
  │
  │   IAM Service
  │   ├─ Validate: Email format, password strength
  │   ├─ Hash: Password using BCrypt
  │   ├─ Insert: user row with hashed password
  │   ├─ Generate: JWT with user ID (no tenant yet)
  │   └─ Return: { accessToken, refreshToken }
  │
  ├─ Request 2: POST /api/iam-svc/auth/login
  │   ├─ Headers: Content-Type: application/json
  │   ├─ Body: { email, password }
  │   │   (NO Authorization required - public endpoint)
  │   │
  │   ↓
  │
  │   IAM Service
  │   ├─ Lookup: user by email
  │   ├─ Validate: BCrypt hash matches password
  │   ├─ Check: Account not locked (brute force protection)
  │   ├─ Generate: JWT with latest roles/tenant from DB
  │   └─ Return: { accessToken, refreshToken }
  │
  ├─ Request 3: POST /api/iam-svc/auth/refresh
  │   ├─ Headers: Authorization: Bearer {refreshToken}
  │   │   (NO X-Tenant-Id needed - token contains everything)
  │   │
  │   ↓
  │
  │   IAM Service
  │   ├─ Validate: Refresh token signature
  │   ├─ Lookup: user by ID in token
  │   ├─ Fetch: Latest roles, tenant from DB
  │   ├─ Generate: NEW access token with current roles
  │   └─ Return: { accessToken }
```

### 1.2 Public vs Protected IAM Endpoints

**Public (no JWT required):**
- `POST /api/iam-svc/auth/register` — Create account
- `POST /api/iam-svc/auth/login` — Login
- `POST /api/iam-svc/auth/refresh` — Refresh token

**Protected (JWT required):**
- `POST /api/iam-svc/auth/logout` — Logout (invalidate token)
- `POST /api/iam-svc/auth/change-password` — Change password
- `GET /api/iam-svc/auth/me` — Get current user info
- `POST /api/iam-svc/users/{id}/roles` — Grant role (PLATFORM_ADMIN only)
- `GET /api/iam-svc/users/{id}/roles` — List user roles

### 1.3 Multi-tenancy in IAM

**Key insight:** Users are global; roles are per-tenant

```
User Alice
├─ Email: alice@example.com
├─ Tenant 1: OWNER (can manage tenant 1)
├─ Tenant 2: STAFF (limited access in tenant 2)
├─ Tenant 3: (no role = no access)
└─ JWT contains: userId + tenant (if there's a "current" tenant) + roles

When Alice calls /api/tenant-svc/admin/stores:
├─ Gateway extracts: X-Tenant-Id from JWT
├─ If tenant matches one where Alice is OWNER
│  └─ Allowed ✓
└─ If tenant where Alice is STAFF
   └─ May be blocked depending on endpoint ✗
```

---

## 2. Request Dispatching

### 2.1 Controller Layer (api/)

```java
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
  @Inject AuthService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/register")
  public Response register(RegisterRequest req) {
    // Public endpoint - no JWT validation
    Validations.validate(req);
    
    var token = service.register(req.email(), req.password(), req.phone());
    
    return Response.status(201)
        .entity(ApiResponse.ok(new TokenResponse(token.accessToken(), token.refreshToken())))
        .build();
  }

  @POST
  @Path("/login")
  public Response login(LoginRequest req) {
    // Public endpoint - no JWT validation
    Validations.validate(req);
    
    var token = service.login(req.email(), req.password());
    
    return Response.status(200)
        .entity(ApiResponse.ok(new TokenResponse(token.accessToken(), token.refreshToken())))
        .build();
  }

  @POST
  @Path("/refresh")
  public Response refresh(@HeaderParam("Authorization") String authHeader) {
    // Authorization header required, but processed specially
    String refreshToken = authHeader.substring(7);
    
    var token = service.refresh(refreshToken);
    
    return Response.ok()
        .entity(ApiResponse.ok(new TokenResponse(token.accessToken(), null)))
        .build();
  }

  @GET
  @Path("/me")
  public ApiResponse<UserResponse> getCurrentUser() {
    // Protected endpoint - requires valid JWT
    UUID userId = ctx.requireUserId();
    
    var user = service.getUser(userId);
    
    return ApiResponse.ok(Mappers.toUser(user));
  }
}
```

### 2.2 Service Layer (service/)

```java
@ApplicationScoped
public class AuthService {
  @Inject UserRepository repo;
  @Inject PasswordHasher hasher;
  @Inject JwtIssuer jwt;

  public TokenPair register(String email, String password, String phone) {
    // 1. Validate input
    if (email == null || !email.contains("@")) {
      throw ApiException.badRequest("INVALID_EMAIL", "Invalid email format");
    }
    if (password.length() < 8) {
      throw ApiException.badRequest("WEAK_PASSWORD", "Password must be >=8 chars");
    }

    // 2. Check user doesn't already exist
    if (repo.findByEmail(email).isPresent()) {
      throw ApiException.conflict("USER_EXISTS", "Email already registered");
    }

    // 3. Create user
    UUID userId = UUID.randomUUID();
    String hashedPassword = hasher.hash(password);
    var user = new User(userId, email, hashedPassword, phone, "ACTIVE", Instant.now());
    
    // 4. Publish event (for audit/analytics)
    var event = new OutboxRow(
        "UserRegistered",
        "shelfj.iam.user-registered",
        null,  // No tenant yet
        userId,
        Events.userRegistered(userId, email));
    
    repo.createUserWithOutbox(user, event);

    // 5. Issue JWT (no tenant claim yet)
    String accessToken = jwt.issue(userId, null, List.of("CUSTOMER"));
    String refreshToken = jwt.issueRefresh(userId);

    return new TokenPair(accessToken, refreshToken);
  }

  public TokenPair login(String email, String password) {
    // 1. Look up user
    var user = repo.findByEmail(email)
        .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password incorrect"));

    // 2. Validate password
    if (!hasher.verify(password, user.hashedPassword())) {
      repo.recordFailedLogin(user.id());  // Brute force protection
      throw ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password incorrect");
    }

    // 3. Clear failed login attempts
    repo.clearFailedLogins(user.id());

    // 4. Fetch latest roles/tenant
    var userTenant = repo.findCurrentTenant(user.id());  // May be null
    var roles = repo.findUserRoles(user.id(), userTenant);  // Empty list if no tenant

    // 5. Issue JWT with current state
    String accessToken = jwt.issue(user.id(), userTenant, roles);
    String refreshToken = jwt.issueRefresh(user.id());

    return new TokenPair(accessToken, refreshToken);
  }

  public TokenPair refresh(String refreshToken) {
    // 1. Validate refresh token
    DecodedJWT decoded = jwt.verifyRefresh(refreshToken);
    UUID userId = UUID.fromString(decoded.getSubject());

    // 2. Check user still exists and is active
    var user = repo.findUser(userId)
        .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User no longer exists"));

    if (!User.STATUS_ACTIVE.equals(user.status())) {
      throw ApiException.unauthorized("USER_INACTIVE", "User account is deactivated");
    }

    // 3. Fetch latest roles (may have changed since access token issued)
    var userTenant = repo.findCurrentTenant(user.id());
    var roles = repo.findUserRoles(user.id(), userTenant);

    // 4. Issue new access token
    String accessToken = jwt.issue(user.id(), userTenant, roles);

    return new TokenPair(accessToken, null);  // Don't return new refresh token
  }
}
```

### 2.3 Repository Layer (repo/)

```java
@ApplicationScoped
public class UserRepository extends BaseOutboxRepository {
  
  // CRUD operations
  public User createUserWithOutbox(User u, OutboxRow event) {
    return inTx(c -> {
      insertUser(c, u);
      insertOutbox(c, event);
      return u;
    }, "create user");
  }

  public Optional<User> findByEmail(String email) {
    return query(
      "SELECT id, email, hashed_password, phone, status, created_at FROM users WHERE email = ?",
      ps -> ps.setString(1, email),
      UserRepository::mapUser,
      "find user by email"
    ).stream().findFirst();
  }

  // Role management
  public List<String> findUserRoles(UUID userId, UUID tenantId) {
    // If no tenant, roles are empty
    if (tenantId == null) return List.of();
    
    return query(
      "SELECT role FROM user_roles WHERE user_id = ? AND tenant_id = ?",
      ps -> {
        ps.setObject(1, userId);
        ps.setObject(2, tenantId);
      },
      rs -> rs.getString("role"),
      "find user roles"
    );
  }

  public void grantRole(UUID userId, UUID tenantId, String role) {
    inTx(c -> {
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO user_roles (user_id, tenant_id, role, created_at) VALUES (?, ?, ?, ?)")) {
        ps.setObject(1, userId);
        ps.setObject(2, tenantId);
        ps.setString(3, role);
        ps.setObject(4, Instant.now().atOffset(ZoneOffset.UTC));
        ps.executeUpdate();
      }
      return null;
    }, "grant role");
  }

  // Brute force protection
  public void recordFailedLogin(UUID userId) {
    // Increment failed_login_count
  }

  public void clearFailedLogins(UUID userId) {
    // Reset failed_login_count to 0
  }
}
```

---

## 3. IAM Service Role & Responsibilities

### 3.1 What IAM Owns

**Database Tables:**
```sql
users (user_id, email, hashed_password, phone, status, created_at)
user_roles (user_id, tenant_id, role, created_at)  -- user may have different roles in different tenants
user_tenants (user_id, tenant_id, current_tenant, created_at)  -- track which tenants user belongs to
password_reset_tokens (token, user_id, expires_at)
failed_logins (user_id, count, last_attempt)  -- for brute force protection
```

**Responsibilities:**
1. User registration (email + password)
2. User authentication (login)
3. JWT token generation & refresh
4. Role management (grant/revoke roles to users)
5. Password management (hash, reset, change)
6. Brute force protection (lock account after N failures)
7. Account status management (ACTIVE, SUSPENDED, DELETED)

### 3.2 What IAM Does NOT Own

**Other services own:**
- Tenant data (tenant-svc)
- User profiles (customer-svc)
- Role definitions (each service defines what roles mean)
- Permissions (each service checks roles)

### 3.3 Events Published

```
UserRegistered
├─ When: User signs up
├─ Topic: shelfj.iam.user-registered
└─ Payload: { userId, email, registeredAt }

UserLoggedIn
├─ When: User logs in
├─ Topic: shelfj.iam.user-logged-in
└─ Payload: { userId, loginAt, tenantId }

UserRoleGranted
├─ When: Role assigned to user for tenant
├─ Topic: shelfj.iam.user-role-granted
└─ Payload: { userId, tenantId, role, grantedAt }

UserRoleRevoked
├─ When: Role removed from user
├─ Topic: shelfj.iam.user-role-revoked
└─ Payload: { userId, tenantId, role, revokedAt }
```

### 3.4 Events Consumed

```
TenantCreated (from tenant-svc)
├─ When: Tenant is created
├─ Action: Create user_tenant record linking creator to new tenant
└─ Make idempotent by: Dedup on (userId, tenantId)

UserRoleGranted (from tenant-svc, via outbox)
├─ When: Tenant service publishes this (e.g., user creates tenant → auto-grant OWNER)
├─ Action: Insert into user_roles table
└─ Make idempotent by: Check if role already granted before insert
```

---

## 4. Technical Architecture

### 4.1 Password Security

```java
// Registration: hash password with BCrypt (10 rounds)
String password = "MyPassword123!";
String hashed = BCrypt.hashpw(password, BCrypt.gensalt(10));
// Result: $2a$10$...... (60 chars, stores salt + hash)

// Login: compare entered password with stored hash
boolean matches = BCrypt.checkpw(password, hashedFromDB);

// Database storage:
INSERT INTO users (id, email, hashed_password, ...)
VALUES (?, ?, '$2a$10$...', ...)
```

**Never store:** Plain text passwords, MD5, SHA (unsalted)

### 4.2 JWT Token Generation

```java
// Access token (short-lived, 1 hour)
String accessToken = JWT.create()
    .withIssuer("shelfj")
    .withSubject(userId.toString())  // user ID
    .withClaim("tenant", tenantId)   // May be null
    .withClaim("roles", roles)       // e.g., ["OWNER", "STAFF"]
    .withIssuedAt(Instant.now())
    .withExpiresAt(Instant.now().plus(1, HOURS))
    .sign(Algorithm.HMAC256(secret));

// Refresh token (long-lived, 30 days)
String refreshToken = JWT.create()
    .withIssuer("shelfj")
    .withSubject(userId.toString())
    .withClaim("type", "refresh")
    .withIssuedAt(Instant.now())
    .withExpiresAt(Instant.now().plus(30, DAYS))
    .sign(Algorithm.HMAC256(secret));
```

### 4.3 Brute Force Protection

```
User attempts to login
├─ Attempt 1-4: Failed password
│   └─ Record failed attempt
├─ Attempt 5: Failed password
│   └─ LOCK account for 15 minutes
│       └─ Return: 403 ACCOUNT_LOCKED
├─ User tries again after 15 min
│   └─ Account unlocked
│   └─ Can try again (counter resets on successful login)
└─ Successful login
    └─ Clear all failed attempts
```

### 4.4 Health Checks

```
GET /health/started → OK (started)

GET /health/live → OK (responding)

GET /health/ready
├─ Database connection: OK
├─ Configuration loaded: OK
└─ JWT secret present: OK
```

---

## 5. API Endpoints

### 5.1 Authentication Endpoints

```
POST /api/iam-svc/auth/register
├─ Public: Yes
├─ Body: { email, password, phone }
├─ Returns: 201 { accessToken, refreshToken }
└─ Errors: 400 (invalid email), 409 (user exists)

POST /api/iam-svc/auth/login
├─ Public: Yes
├─ Body: { email, password }
├─ Returns: 200 { accessToken, refreshToken }
└─ Errors: 401 (invalid credentials), 403 (account locked)

POST /api/iam-svc/auth/refresh
├─ Public: No (requires Bearer token = refresh token)
├─ Headers: Authorization: Bearer {refreshToken}
├─ Returns: 200 { accessToken }
└─ Errors: 401 (expired/invalid token)

POST /api/iam-svc/auth/logout
├─ Public: No (requires Bearer token)
├─ Headers: Authorization: Bearer {accessToken}
├─ Returns: 204 (no content)
└─ Note: Logs event but doesn't invalidate token (stateless)

POST /api/iam-svc/auth/change-password
├─ Public: No
├─ Headers: Authorization: Bearer {accessToken}
├─ Body: { currentPassword, newPassword }
├─ Returns: 200 { success: true }
└─ Errors: 401 (current password wrong), 400 (password weak)

GET /api/iam-svc/auth/me
├─ Public: No
├─ Headers: Authorization: Bearer {accessToken}
├─ Returns: 200 { userId, email, phone, roles, currentTenant }
└─ Note: Returns user's current role(s)
```

### 5.2 User Management Endpoints (PLATFORM_ADMIN only)

```
GET /api/iam-svc/users
├─ Requires: PLATFORM_ADMIN role
├─ Returns: 200 { users: [...] }
└─ Filters: Optional ?email=, ?status=

GET /api/iam-svc/users/{id}
├─ Requires: PLATFORM_ADMIN role
├─ Returns: 200 { user details }
└─ Errors: 404 (user not found)

POST /api/iam-svc/users/{id}/roles
├─ Requires: PLATFORM_ADMIN role
├─ Body: { tenantId, role }
├─ Returns: 201 { role granted }
└─ Errors: 404 (user/tenant not found), 409 (role already granted)

DELETE /api/iam-svc/users/{id}/roles/{tenantId}
├─ Requires: PLATFORM_ADMIN role
├─ Returns: 204 (role revoked)
└─ Errors: 404 (role not found)

PATCH /api/iam-svc/users/{id}/status
├─ Requires: PLATFORM_ADMIN role
├─ Body: { status: "ACTIVE" | "SUSPENDED" | "DELETED" }
├─ Returns: 200 { user with new status }
└─ Note: SUSPENDED blocks logins; DELETED is permanent
```

---

## 6. Event-Driven Architecture

### 6.1 Consuming TenantCreated Event

```java
@ApplicationScoped
public class TenantEventConsumer {
  @Inject UserRepository repo;

  @Incoming("shelfj.tenant.tenant-created")
  public void onTenantCreated(Message<String> msg) {
    // Parse event: { tenantId, ownerUserId, name, ... }
    var event = parseJson(msg.getPayload());
    
    // Idempotency: Check if we already processed this event
    if (repo.isEventProcessed(event.eventId())) {
      msg.ack();
      return;
    }

    try {
      // Link user to tenant
      repo.createUserTenant(
        event.ownerUserId(),
        event.tenantId(),
        true  // current_tenant = true
      );

      // Mark event as processed
      repo.markEventProcessed(event.eventId());
      
      msg.ack();
    } catch (Exception e) {
      // Let Kafka retry (no ACK)
      // After max retries, message goes to DLQ
      log.error("Failed to process TenantCreated event", e);
    }
  }
}
```

### 6.2 Publishing UserRoleGranted Event

When tenant-svc publishes UserRoleGranted, IAM consumes it:

```java
@Incoming("shelfj.iam.user-role-granted")
public void onUserRoleGranted(Message<String> msg) {
  var event = parseJson(msg.getPayload());
  
  if (repo.roleAlreadyExists(event.userId(), event.tenantId(), event.role())) {
    msg.ack();  // Already processed
    return;
  }

  // Grant the role
  repo.grantRole(event.userId(), event.tenantId(), event.role());
  
  msg.ack();
}
```

---

## 7. Testing Strategy

### 7.1 Unit Tests

```java
@Test
void shouldRegisterUser_whenValidEmail() {
  // Setup
  var req = new RegisterRequest("alice@example.com", "Password123", "1234567890");
  
  // Execute
  var tokens = service.register(req.email(), req.password(), req.phone());
  
  // Assert
  assertThat(tokens.accessToken()).isNotBlank();
  assertThat(tokens.refreshToken()).isNotBlank();
  
  // Verify JWT contains userId (no tenant yet)
  var decoded = JWT.decode(tokens.accessToken());
  assertThat(decoded.getSubject()).isNotNull();
  assertThat(decoded.getClaim("tenant").asString()).isNull();
}

@Test
void shouldRejectWeakPassword() {
  // Setup
  var req = new RegisterRequest("bob@example.com", "short", "0987654321");
  
  // Execute & Assert
  assertThatThrownBy(() -> service.register(req.email(), req.password(), req.phone()))
      .isInstanceOf(ApiException.class)
      .hasMessageContaining("WEAK_PASSWORD");
}

@Test
void shouldLockAccountAfter5FailedLogins() {
  // Setup
  createUser("charlie@example.com", "Password123");
  
  // Execute: 5 failed login attempts
  for (int i = 0; i < 5; i++) {
    assertThatThrownBy(() -> service.login("charlie@example.com", "WrongPassword"))
        .hasMessageContaining("INVALID_CREDENTIALS");
  }
  
  // Assert: 6th attempt is blocked
  assertThatThrownBy(() -> service.login("charlie@example.com", "Password123"))
      .hasMessageContaining("ACCOUNT_LOCKED");
}
```

### 7.2 Integration Tests (with Testcontainers)

```java
@Testcontainers
class AuthIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

  @Test
  void shouldPublishUserRegisteredEvent() {
    // Setup: Real database
    var req = new RegisterRequest("diana@example.com", "Password123", "1111111111");
    
    // Execute
    var tokens = service.register(req.email(), req.password(), req.phone());
    
    // Assert: Event published to outbox
    var outbox = repo.findOutboxByEventType("UserRegistered");
    assertThat(outbox).hasSize(1);
    assertThat(outbox.get(0).payload()).contains("\"email\":\"diana@example.com\"");
  }
}
```

---

## 8. Common Tasks

### 8.1 Adding a New Role Type

1. **Discuss:** What does this role do? Which services check for it?
2. **Update service code:** Add role check logic
3. **Update IAM:** Just track it in user_roles table (no code change needed)
4. **Test:** Unit test for role grant/revoke

### 8.2 Handling Password Reset

```
User calls: POST /api/iam-svc/auth/forgot-password
├─ Body: { email }
├─ IAM generates: Reset token (64-char random)
├─ Store: In password_reset_tokens table (expires in 1 hour)
├─ Email: Reset link with token
└─ Return: { success: true }

User clicks link: POST /api/iam-svc/auth/reset-password
├─ Body: { token, newPassword }
├─ IAM validates: Token exists and not expired
├─ Update: User's hashed_password
├─ Delete: Reset token (one-time use)
└─ Return: { accessToken, refreshToken }
```

### 8.3 Tracking Login Events

```java
// Log every login attempt (for audit)
@Outgoing("shelfj.iam.user-logged-in")
public void publishLoginEvent(UUID userId, UUID tenantId) {
  var event = new OutboxRow(
      "UserLoggedIn",
      "shelfj.iam.user-logged-in",
      tenantId,
      userId,
      Events.userLoggedIn(userId, tenantId, Instant.now()));
  
  repo.insertOutbox(event);
}
```

---

## 9. Security Considerations

### 9.1 Token Security

| Threat | Defense |
|--------|---------|
| Token interception | Use HTTPS only (in production) |
| Token expiration | Access tokens: 1 hour; Refresh tokens: 30 days |
| Refresh token theft | Refresh tokens can only be used at refresh endpoint |
| Token tampering | Signed with HMAC256 (secret from config) |
| Replay attacks | Each token has issued-at + expiration (can't reuse old token) |

### 9.2 Password Security

| Threat | Defense |
|--------|---------|
| Brute force | Account locked after 5 failed attempts (15 min) |
| Rainbow tables | BCrypt with salt (each hash includes salt) |
| Weak passwords | Enforce minimum 8 characters + complexity |
| Password logging | Never log passwords (only hashes) |

---

## 10. Deployment

### 10.1 Migration: Create Tables

```sql
CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  hashed_password VARCHAR(255) NOT NULL,  -- BCrypt hash (60 chars)
  phone VARCHAR(20),
  status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED, DELETED
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE user_roles (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  tenant_id UUID NOT NULL,  -- Not a foreign key (multi-tenant, not owned by IAM)
  role VARCHAR(50) NOT NULL,  -- OWNER, STAFF, MANAGER, CASHIER, CUSTOMER, etc.
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE(user_id, tenant_id, role)
);

CREATE TABLE user_tenants (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  tenant_id UUID NOT NULL,
  current_tenant BOOLEAN DEFAULT false,  -- Which is their "home" tenant
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE failed_logins (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  count INT DEFAULT 0,
  last_attempt TIMESTAMPTZ,
  locked_until TIMESTAMPTZ
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_tenant_id ON user_roles(tenant_id);
```

---

## 11. References

- [CLAUDE.md](../../CLAUDE.md) — Multi-tenancy rules
- [AuthResource.java](src/main/java/com/shelfj/iam/api/AuthResource.java) — Controllers
- [AuthService.java](src/main/java/com/shelfj/iam/service/AuthService.java) — Business logic

---

**Last Updated:** 2026-06-18  
**Service Version:** v1  
**Maintainer:** Platform Team
