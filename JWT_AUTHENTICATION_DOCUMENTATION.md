# JWT Authentication & Authorization — Technical Documentation

**Component:** `JwtAuthFilter`, `JwtService`, `TokenBlacklistService`  
**Module:** `contract-management`  
**Standard:** RFC 7519 (JSON Web Tokens), OWASP Authentication Cheat Sheet  
**Last Updated:** 2026-06-23

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Components](#3-components)
   - 3.1 [JwtAuthFilter](#31-jwtauthfilter)
   - 3.2 [JwtService](#32-jwtservice)
   - 3.3 [TokenBlacklistService](#33-tokenblacklistservice)
   - 3.4 [AuthService](#34-authservice)
   - 3.5 [SecurityConfig](#35-securityconfig)
4. [Token Lifecycle](#4-token-lifecycle)
5. [Request Processing Flow](#5-request-processing-flow)
6. [Token Structure](#6-token-structure)
7. [Endpoint Access Control](#7-endpoint-access-control)
8. [Configuration Reference](#8-configuration-reference)
9. [Error Responses](#9-error-responses)
10. [API Reference](#10-api-reference)
11. [Security Considerations](#11-security-considerations)
12. [Testing](#12-testing)

---

## 1. Overview

This system implements **stateless JWT-based authentication** for the Contract Management API. Every protected request must carry a signed JWT in the `Authorization: Bearer <token>` header. The server does not maintain sessions — all identity information is encoded within the token itself.

**Logout** is implemented via a **JTI (JWT ID) blacklist** stored in MongoDB. When a user logs out, the token's unique identifier is saved to the `revoked_tokens` collection. The filter checks this blacklist on every request and rejects any token whose JTI is found there, even if its cryptographic signature is still valid.

**Key design decisions:**

| Decision | Choice | Reason |
|---|---|---|
| Session strategy | Stateless (no `HttpSession`) | Horizontal scaling — no session affinity required |
| Token algorithm | HMAC-SHA-256 | Symmetric, fast, sufficient for server-to-server trust |
| Logout mechanism | MongoDB JTI blacklist | No Redis in stack; MongoDB TTL index provides automatic cleanup |
| Token TTL | Configurable (`jwt.expiration`) | Default 24 h; aligned with `expiresAt` in `revoked_tokens` |
| Role encoding | Custom `role` claim | Avoids overhead of Spring's `UserDetails` lookup on every request |

---

## 2. Architecture

```
HTTP Request
     │
     ▼
┌─────────────────────────────────────────────┐
│              SecurityFilterChain             │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │           JwtAuthFilter              │   │
│  │   (OncePerRequestFilter)             │   │
│  │                                      │   │
│  │  1. Extract Bearer token             │   │
│  │  2. JwtService.isTokenValid()        │   │
│  │  3. TokenBlacklistService.isRevoked()│   │
│  │  4. Set SecurityContextHolder        │   │
│  └──────────────────────────────────────┘   │
│                    │                         │
│  ┌─────────────────▼──────────────────────┐ │
│  │   UsernamePasswordAuthenticationFilter  │ │
│  │          (disabled — JWT only)          │ │
│  └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
     │
     ▼
Controller / Resource
```

```
┌──────────────┐     generateToken()    ┌──────────────┐
│  AuthService │ ──────────────────────▶│  JwtService  │
│  (login)     │                        │              │
└──────────────┘                        │ - sign token │
                                        │ - verify sig │
┌──────────────┐     revokeToken()      │ - extract    │
│AuthController│ ──────────────────────▶│   claims     │
│  (logout)    │                        └──────────────┘
└──────────────┘          │
                           │ extractJti / extractExpiration
                           ▼
                ┌─────────────────────┐
                │ TokenBlacklistService│
                └──────────┬──────────┘
                           │ save / existsByJti
                           ▼
                ┌─────────────────────┐
                │     MongoDB         │
                │  revoked_tokens     │
                │  (TTL auto-cleanup) │
                └─────────────────────┘
```

---

## 3. Components

### 3.1 JwtAuthFilter

**File:** `src/main/java/.../filter/JwtAuthFilter.java`  
**Extends:** `OncePerRequestFilter` (Spring Security)  
**Registered before:** `UsernamePasswordAuthenticationFilter`

#### Responsibility

Intercepts every incoming HTTP request exactly once, extracts the JWT from the `Authorization` header, validates it, checks the blacklist, and — when valid — populates the `SecurityContext` so downstream controllers can read the authenticated principal.

#### Decision Logic

```
Request arrives
    │
    ├─ No "Authorization" header?          ──▶  pass through (unauthenticated)
    ├─ Header does not start with "Bearer "?──▶  pass through (unauthenticated)
    │
    ├─ JwtService.isTokenValid() == false?  ──▶  pass through (unauthenticated)
    │                                            (never checks blacklist — short-circuit)
    ├─ TokenBlacklistService.isRevoked()?   ──▶  pass through (unauthenticated)
    │
    └─ Token valid + not revoked            ──▶  set SecurityContext → pass through
```

> **Why always call `filterChain.doFilter()`?**  
> The filter does not reject requests itself. It either sets or does not set the `SecurityContext`. Spring Security's access control layer (configured in `SecurityConfig`) performs the actual rejection with a `401 Unauthorized` for protected routes.

#### Constructor Dependencies

| Dependency | Type | Purpose |
|---|---|---|
| `jwtService` | `JwtService` | Validates signature and extracts claims |
| `tokenBlacklistService` | `TokenBlacklistService` | Checks whether the token's JTI has been revoked |

#### SecurityContext Population

When a token passes both checks, the filter sets:

```java
UsernamePasswordAuthenticationToken(
    email,          // principal  — accessible via SecurityContextHolder
    null,           // credentials — cleared (token is the credential)
    List.of(new SimpleGrantedAuthority("ROLE_" + role))  // e.g. ROLE_USER, ROLE_ADMIN
)
```

---

### 3.2 JwtService

**File:** `src/main/java/.../service/JwtService.java`  
**Library:** `io.jsonwebtoken` (jjwt)  
**Algorithm:** HMAC-SHA-256

#### Configuration

| Property (`application.properties`) | Type | Description |
|---|---|---|
| `jwt.secret` | `String` (64 hex chars = 32 bytes) | Signing key for HMAC-SHA-256 |
| `jwt.expiration` | `long` (milliseconds) | Token validity window from issuance |

#### Methods

| Method | Returns | Description |
|---|---|---|
| `generateToken(email, role)` | `String` | Builds and signs a JWT with `sub`, `role`, `iat`, `exp`, and a random `jti` |
| `extractEmail(token)` | `String` | Reads the `sub` (subject) claim |
| `extractRole(token)` | `String` | Reads the custom `role` claim |
| `extractJti(token)` | `String` | Reads the `jti` (JWT ID) claim — used for blacklisting |
| `extractExpiration(token)` | `Date` | Reads the `exp` claim — used to set TTL on `RevokedToken` |
| `isTokenValid(token)` | `boolean` | Parses and verifies signature + expiry; returns `false` on any exception |

#### Token Generation Details

```
jti  = UUID.randomUUID()       ← unique per token; required for blacklisting
sub  = email                   ← user identity
role = "USER" | "ADMIN"        ← custom claim
iat  = now
exp  = now + jwt.expiration
sig  = HMAC-SHA256(header.payload, secret)
```

---

### 3.3 TokenBlacklistService

**File:** `src/main/java/.../service/TokenBlacklistService.java`  
**Storage:** MongoDB collection `revoked_tokens`

#### Responsibility

Maintains the server-side revocation list that bridges stateless JWT with logout semantics. A token whose JTI is present in this collection is treated as invalid even if its cryptographic signature is correct and it has not yet expired.

#### Methods

**`revokeToken(String token)`**

Adds a token's JTI to the blacklist. Called by `AuthController.logout()`.

```
1. jwtService.extractJti(token)        → jti (String)
2. jwtService.extractExpiration(token) → expiresAt (Date)
3. revokedTokenRepository.save(RevokedToken{jti, expiresAt})
```

The `expiresAt` field drives a MongoDB TTL index — the document is automatically deleted once the original token would have expired naturally. This prevents unbounded growth of the collection.

**`isRevoked(String token)`**

Checks whether a token has been revoked. Called by `JwtAuthFilter` on every authenticated request.

```
1. jwtService.extractJti(token) → jti
2. revokedTokenRepository.existsByJti(jti) → boolean
   └─ Any exception during extraction → returns false (fail-open for safety)
```

> **Fail-open rationale:** An exception in `isRevoked` typically means the token is malformed or already rejected by `isTokenValid`. Since `JwtAuthFilter` calls `isTokenValid` first and short-circuits on failure, `isRevoked` is only reached for structurally valid tokens. A failure here is treated as "not revoked" to prevent outages due to transient DB errors from locking all users out.

#### RevokedToken Document

```java
@Document(collection = "revoked_tokens")
public class RevokedToken {
    @Id String id;

    @Indexed(unique = true)
    String jti;           // prevents double-insert for same token

    @Indexed(expireAfterSeconds = 0)
    Date expiresAt;       // MongoDB TTL — document auto-deleted at this time
}
```

> **Prerequisite:** MongoDB auto-index creation must be enabled for the TTL index to be created automatically:
> ```properties
> spring.data.mongodb.auto-index-creation=true
> ```

---

### 3.4 AuthService

**File:** `src/main/java/.../service/AuthService.java`

Handles both registration and login through a single `authenticate()` method. If the email is new, the user is registered automatically (auto-registration). If the email exists, the password is validated with BCrypt.

| Outcome | HTTP | Description |
|---|---|---|
| New email | `200` | User registered, token issued, `isNewUser: true` |
| Existing email + correct password | `200` | Token issued, `isNewUser: false` |
| Existing email + wrong password | `500` → wrapped as `401` | `RuntimeException("The password you entered is incorrect...")` |

**Role assignment:** The email `admin@gmail.com` receives `ADMIN`; all others receive `USER`.

---

### 3.5 SecurityConfig

**File:** `src/main/java/.../config/SecurityConfig.java`

Defines the Spring Security filter chain, CORS policy, and access rules.

**Session policy:** `STATELESS` — no `HttpSession` is created or used.

**Filter registration:**
```java
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
```

**CORS:** Allowed origins read from `cors.allowed-origins` in `application.properties`. Exposes the `Authorization` response header so browser clients can read the token.

---

## 4. Token Lifecycle

```
 ┌──────────┐         ┌──────────┐         ┌─────────────────┐
 │  Client  │         │  Server  │         │    MongoDB       │
 └────┬─────┘         └────┬─────┘         └────────┬────────┘
      │                    │                         │
      │  POST /auth/login  │                         │
      │───────────────────▶│                         │
      │                    │  validate / register    │
      │                    │  generateToken()        │
      │◀───────────────────│                         │
      │   {token, email,   │                         │
      │    role, isNewUser}│                         │
      │                    │                         │
      │  GET /contracts     │                         │
      │  Authorization:     │                         │
      │  Bearer <token>    │                         │
      │───────────────────▶│                         │
      │                    │  isTokenValid()         │
      │                    │  isRevoked()  ──────────▶ existsByJti()
      │                    │◀────────────────────────│ false
      │                    │  setSecurityContext()   │
      │◀───────────────────│                         │
      │   200 + data       │                         │
      │                    │                         │
      │  POST /auth/logout │                         │
      │  Authorization:     │                         │
      │  Bearer <token>    │                         │
      │───────────────────▶│                         │
      │                    │  extractJti()           │
      │                    │  extractExpiration()    │
      │                    │  save(RevokedToken) ────▶ insert {jti, expiresAt}
      │◀───────────────────│                         │
      │  {message: "Logged │                         │
      │   out successfully"}│                        │
      │                    │                         │
      │  GET /contracts     │                         │
      │  Authorization:     │                         │
      │  Bearer <token>    │                         │  (same token)
      │───────────────────▶│                         │
      │                    │  isTokenValid() → true  │
      │                    │  isRevoked()  ──────────▶ existsByJti() → true
      │                    │  SecurityContext NOT set │
      │◀───────────────────│                         │
      │   401 Unauthorized │                         │
      │                    │                         │
      │  (token expires)   │                         │
      │                    │                 TTL index auto-deletes
      │                    │                 RevokedToken document
```

---

## 5. Request Processing Flow

Every protected API call passes through this decision tree inside `JwtAuthFilter.doFilterInternal()`:

```
Step 1: Read header
  authHeader = request.getHeader("Authorization")
  ├─ null → skip to filterChain.doFilter()
  └─ does not start with "Bearer " → skip to filterChain.doFilter()

Step 2: Extract raw token
  token = authHeader.substring(7)

Step 3: Validate signature + expiry
  jwtService.isTokenValid(token)
  ├─ false → skip to filterChain.doFilter()   [401 enforced by Spring Security]
  └─ true  → continue

Step 4: Check blacklist
  tokenBlacklistService.isRevoked(token)
  ├─ true  → skip to filterChain.doFilter()   [401 enforced by Spring Security]
  └─ false → continue

Step 5: Populate SecurityContext
  email = jwtService.extractEmail(token)
  role  = jwtService.extractRole(token)
  SecurityContextHolder.setAuthentication(
    UsernamePasswordAuthenticationToken(email, null, [ROLE_<role>])
  )

Step 6: Continue
  filterChain.doFilter(request, response)
```

---

## 6. Token Structure

The JWT is a standard three-part `header.payload.signature` string, Base64URL-encoded.

### Header

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### Payload (Claims)

| Claim | RFC 7519 Name | Source | Example |
|---|---|---|---|
| `jti` | JWT ID | `UUID.randomUUID()` | `"550e8400-e29b-41d4-a716-446655440000"` |
| `sub` | Subject | User's email address | `"alice@example.com"` |
| `role` | *(custom)* | User's role at login | `"USER"` or `"ADMIN"` |
| `iat` | Issued At | `new Date()` | Unix timestamp |
| `exp` | Expiration | `iat + jwt.expiration` | Unix timestamp |

### Signature

```
HMAC-SHA256(
  Base64URL(header) + "." + Base64URL(payload),
  HexDecode(jwt.secret)
)
```

---

## 7. Endpoint Access Control

Defined in `SecurityConfig.securityFilterChain()`:

| Path Pattern | Method | Access |
|---|---|---|
| `/auth/**` | ANY | Public — no token required |
| `/swagger-ui/**`, `/v3/api-docs/**` | ANY | Public |
| `/sign-requests/*` | GET | Public (signing link is the credential) |
| `/sign-requests/*/file-url` | GET | Public |
| `/sign-requests/*/upload/**` | GET, POST | Public |
| `/sign-requests/*/viewed` | PATCH | Public |
| `/templates/**` | GET | Authenticated (`USER` or `ADMIN`) |
| `/categories/**` | GET | Authenticated (`USER` or `ADMIN`) |
| `/templates/**` | POST/PUT/DELETE | `ADMIN` only |
| `/categories/**` | POST/PUT/DELETE | `ADMIN` only |
| `/admin/**` | ANY | `ADMIN` only |
| `/folders/**` | ANY | Authenticated |
| `/contracts/**` | ANY | Authenticated |
| `/**` (catch-all) | ANY | Authenticated |

**Response codes:**

| Scenario | Code |
|---|---|
| Missing or invalid token on protected route | `401 Unauthorized` |
| Valid token with insufficient role | `403 Forbidden` |

---

## 8. Configuration Reference

All values belong in `application.properties` (or environment-specific overrides):

```properties
# ── JWT ─────────────────────────────────────────────────────────────────────
# 64 hex characters (32 bytes) — minimum for HMAC-SHA-256
jwt.secret=<64-hex-char-secret>

# Token validity in milliseconds — 86400000 = 24 hours
jwt.expiration=86400000

# ── MongoDB ──────────────────────────────────────────────────────────────────
# Required for the TTL index on revoked_tokens.expiresAt to be created
spring.data.mongodb.auto-index-creation=true

# ── CORS ─────────────────────────────────────────────────────────────────────
cors.allowed-origins=http://localhost:3000,https://yourapp.com
```

> **Security note on `jwt.secret`:** Never commit the secret to source control. Use environment variables or a secrets manager in production:
> ```properties
> jwt.secret=${JWT_SECRET}
> ```

---

## 9. Error Responses

### 401 Unauthorized

Returned when:
- `Authorization` header is missing on a protected route
- Token signature is invalid or tampered
- Token has expired
- Token JTI is in the `revoked_tokens` blacklist (logged-out token)

```json
HTTP/1.1 401 Unauthorized

Unauthorized
```

### 403 Forbidden

Returned when the token is valid but the user's role does not satisfy the route's access rule (e.g., a `USER` accessing `/admin/**`):

```json
HTTP/1.1 403 Forbidden

Forbidden
```

### 401 on Login (Wrong Password)

```json
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "status": 401,
  "error": "The password you entered is incorrect. Please try again."
}
```

---

## 10. API Reference

### POST `/auth/login`

Auto-registers new users or authenticates existing ones.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "yourpassword"
}
```

**Response `200 OK`:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "email": "user@example.com",
  "role": "USER",
  "newUser": false
}
```

**Response `401` — wrong password:**
```json
{
  "status": 401,
  "error": "The password you entered is incorrect. Please try again."
}
```

---

### POST `/auth/logout`

Invalidates the caller's JWT by adding its JTI to the blacklist.

**Headers:**
```
Authorization: Bearer <token>
```

**Response `200 OK`:**
```json
{
  "message": "Logged out successfully"
}
```

> The response is always `200` regardless of whether the `Authorization` header is present. If the header is absent, the endpoint is a no-op.

**Subsequent requests with the same token:**
```
HTTP/1.1 401 Unauthorized
```

---

### Using the Token

Include the token in the `Authorization` header for every protected request:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**cURL examples:**

```bash
# Login
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"secret123"}'

# Authenticated request
curl -X GET http://localhost:8080/contracts \
  -H "Authorization: Bearer <token>"

# Logout
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer <token>"

# Verify token is revoked (expect 401)
curl -X GET http://localhost:8080/contracts \
  -H "Authorization: Bearer <token>"
```

---

## 11. Security Considerations

### Token Storage (Client Side)

- Store tokens in **memory** (JavaScript variable) or **`httpOnly` cookies** — not `localStorage` (XSS risk).
- The server exposes `Authorization` in `Access-Control-Expose-Headers` so browser clients can read it from responses.

### Token Expiration

Tokens expire after `jwt.expiration` milliseconds from issuance. Expired tokens are rejected by `jwtService.isTokenValid()` before the blacklist is ever checked — this is efficient and correct.

### Blacklist Scalability

The `revoked_tokens` collection grows proportionally to the number of active logout operations between token expiry windows. The MongoDB TTL index ensures documents are automatically deleted once their corresponding token would have expired. Under normal load this collection stays small.

For high-throughput systems, consider migrating `isRevoked()` to Redis for O(1) in-memory lookup. The interface (`TokenBlacklistService`) is already isolated so the storage backend can be swapped without touching `JwtAuthFilter`.

### Signing Key Requirements

The `jwt.secret` must be at least 32 bytes (64 hex characters) for HMAC-SHA-256. Shorter keys are rejected by the jjwt library at startup.

### CSRF

CSRF protection is disabled (`AbstractHttpConfigurer::disable`) because the API is stateless — tokens are transmitted in `Authorization` headers, not cookies, so browser-based CSRF attacks are not applicable.

### Fail-Open in `isRevoked()`

`TokenBlacklistService.isRevoked()` catches all exceptions and returns `false`. This is intentional: a transient MongoDB failure should not lock all users out of the system. The risk is small — `isTokenValid()` already verifies the cryptographic integrity before `isRevoked()` is called, so only structurally valid tokens reach the blacklist check.

---

## 12. Testing

### Test Classes

| Test File | Tests | Coverage |
|---|---|---|
| `JwtServiceTest` | 18 | Token generation, claim extraction (`email`, `role`, `jti`, `expiration`), validation |
| `JwtAuthFilterTest` | 16 | No header, valid token, invalid token, revoked token |
| `TokenBlacklistServiceTest` | 12 | `revokeToken` saves correct JTI + expiry; `isRevoked` returns correct values; exception safety |

### Running Authentication Tests

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
mvn test -Dtest="JwtServiceTest,JwtAuthFilterTest,TokenBlacklistServiceTest"
```

### Running All Tests

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
mvn test -Dtest="ProfileServiceTest,CategoryServiceTest,FolderServiceTest,ContractServicePresignedUrlTest,SignatureServicePresignedUrlTest,ContractWorkflowServiceTest,JwtServiceTest,JwtAuthFilterTest,TokenBlacklistServiceTest"
```

### Key Test Scenarios

| Scenario | Expected Behaviour |
|---|---|
| Valid token, not revoked | SecurityContext set with `email` as principal and `ROLE_<role>` authority |
| Valid token, blacklisted | SecurityContext NOT set; filter chain still proceeds; downstream returns 401 |
| Invalid/expired token | `isRevoked()` never called (short-circuit); SecurityContext NOT set |
| No `Authorization` header | All JWT processing skipped; SecurityContext NOT set |
| `extractJti()` throws in `isRevoked()` | Returns `false`; no repository call; no exception propagated |
| `revokeToken()` | Saves `RevokedToken` with correct `jti` and `expiresAt` |
