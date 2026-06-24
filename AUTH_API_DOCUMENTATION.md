# Authentication API Documentation

**Project:** Contract Management System
**Version:** 1.0.0
**Base URL:** `http://localhost:8080`
**Last Updated:** 2026-06-23

---

## Table of Contents

1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [Swagger UI](#swagger-ui)
4. [Authentication Flow](#authentication-flow)
5. [JWT Token](#jwt-token)
6. [API Endpoints](#api-endpoints)
7. [Request & Response Schemas](#request--response-schemas)
8. [Error Handling](#error-handling)
9. [Role-Based Access Control](#role-based-access-control)
10. [Security Considerations](#security-considerations)
11. [Testing Guide](#testing-guide)

---

## Overview

This API uses a **stateless JWT-based authentication** system. The `/auth` module exposes two endpoints:

- **`POST /auth/login`** — handles both registration and login:
  - If the email does not exist → the user is **automatically registered** and a token is returned.
  - If the email already exists → the password is **validated** and a token is returned on success.
- **`POST /auth/logout`** — **invalidates** the caller's current JWT by adding its JTI to a MongoDB blacklist. Any subsequent request using the same token is rejected with `401 Unauthorized`, even if the token's cryptographic signature is still valid and it has not yet expired.

No separate `/register` endpoint is needed. Logout is achieved without session storage — a lightweight **JTI (JWT ID) blacklist** in MongoDB bridges stateless JWT with logout semantics. MongoDB's TTL index automatically cleans up blacklist entries once the original token would have expired.

---

## Technology Stack

| Component         | Technology                                          |
|-------------------|-----------------------------------------------------|
| Framework         | Spring Boot 4.0.6                                   |
| Security          | Spring Security 7.x                                 |
| Token Standard    | JWT (JSON Web Token) — JJWT 0.12.6                  |
| Algorithm         | HMAC-SHA256 (HS256)                                 |
| Password Hashing  | BCrypt                                              |
| Database          | MongoDB                                             |
| File Storage      | MinIO                                               |
| Validation        | Jakarta Validation (spring-boot-starter-validation) |
| Token Blacklist   | MongoDB `revoked_tokens` collection (JTI-based)     |
| API Documentation | SpringDoc OpenAPI 2.8.8 (Swagger UI)                |

---

## Swagger UI

### Overview

The API is documented using **SpringDoc OpenAPI 2.8.8** which generates an interactive Swagger UI. It allows you to explore, read, and test all endpoints directly from the browser without needing Postman or curl.

### Access URLs

| URL | Purpose |
|-----|---------|
| `http://localhost:8080/swagger-ui/index.html` | Interactive Swagger UI |
| `http://localhost:8080/api-docs` | Raw OpenAPI JSON spec |

### Configuration

Configured in `application.properties`:

```properties
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.api-docs.path=/api-docs
springdoc.swagger-ui.operationsSorter=alpha
springdoc.swagger-ui.tagsSorter=alpha
springdoc.swagger-ui.display-request-duration=true
```

| Property | Value | Description |
|----------|-------|-------------|
| `swagger-ui.path` | `/swagger-ui.html` | Entry point for Swagger UI |
| `api-docs.path` | `/api-docs` | Path where OpenAPI JSON spec is served |
| `operationsSorter` | `alpha` | Sorts endpoints alphabetically |
| `tagsSorter` | `alpha` | Sorts tag groups alphabetically |
| `display-request-duration` | `true` | Shows response time for each request |

### SwaggerConfig Bean

Defined in `config/SwaggerConfig.java`:

```java
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Contract Management API")
                        .version("1.0.0"));
    }
}
```

### Security — Public vs Protected Endpoints

The following paths are **publicly accessible** (no token required) — configured in `SecurityConfig.java`:

```java
.requestMatchers("/auth/**").permitAll()
.requestMatchers(
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/v3/api-docs/**",
    "/api-docs/**",
    "/api-docs",
    "/webjars/**"
).permitAll()
```

All other endpoints require a valid JWT token.

### How to Use Swagger UI with Authentication

**Step 1 — Get a token**

In Swagger UI, open `POST /auth/login`, click **Try it out**, enter your email and password, and click **Execute**. Copy the `token` value from the response.

**Step 2 — Authorize**

Click the **Authorize** button at the top right of Swagger UI. In the dialog enter:

```
Bearer eyJhbGciOiJIUzI1NiJ9...
```

Click **Authorize** → **Close**.

**Step 3 — Call protected endpoints**

All subsequent requests from Swagger UI will automatically include the `Authorization: Bearer <token>` header.

### Swagger Annotations Used

| Annotation | Location | Purpose |
|------------|----------|---------|
| `@Tag` | `AuthController` | Groups endpoints under "Authentication" label |
| `@Operation` | `/auth/login` method | Describes the endpoint behaviour and all cases |
| `@ApiResponses` + `@ApiResponse` | `/auth/login` method | Documents 200, 400, 401 responses with examples |
| `@ExampleObject` | Response content | Provides real JSON examples for each response case |
| `@SecurityRequirements` | `/auth/login` method | Marks login as public (overrides global JWT requirement) |
| `@SecurityRequirement` | `/auth/logout` method | Marks logout as requiring a Bearer token in Swagger UI |
| `@Schema` | `AuthRequest`, `AuthResponse` | Documents each field with description, example, and constraints |

---

## Authentication Flow

```
┌─────────┐     ┌──────────────┐    ┌───────────┐    ┌──────────────────────┐
│  Client │     │AuthController│    │AuthService│    │       MongoDB        │
└────┬────┘     └──────┬───────┘    └─────┬─────┘    └──────────┬───────────┘
     │                 │                  │                      │
     │─── POST /auth/login ──────────────>│                      │
     │   {email, password}                │  findByEmail() ─────>│
     │                 │                  │<──────────────────────│
     │                 │           ┌──────┴──────┐               │
     │                 │           │ User found? │               │
     │                 │           │  NO → register + BCrypt     │
     │                 │           │  YES → BCrypt.matches()     │
     │                 │           │        fail → 401           │
     │                 │           └──────┬──────┘               │
     │                 │                  │ generateToken()       │
     │                 │                  │  (sub=email,          │
     │                 │                  │   role, jti=UUID)     │
     │<── 200 {token, email, role, newUser} ────────────────────  │
     │                 │                  │                      │
     │                 │                  │                      │
     │─── POST /auth/logout ─────────────>│                      │
     │   Authorization: Bearer <token>    │  extractJti()         │
     │                 │                  │  extractExpiration()  │
     │                 │                  │  save(RevokedToken) ─>│ insert
     │<── 200 {message: "Logged out"} ──  │                      │ {jti, expiresAt}
     │                 │                  │                      │
     │                 │                  │                      │
     │─── GET /contracts (same token) ──> │                      │
     │         JwtAuthFilter:             │                      │
     │         1. isTokenValid() → true   │                      │
     │         2. isRevoked() ────────────────────────────────── >│ existsByJti()
     │                 │         ← true ─────────────────────────│
     │         → SecurityContext NOT set  │                      │
     │<── 401 Unauthorized ───────────────│                      │
     │                 │                  │                      │
     │         (token TTL expires)        │                      │
     │                 │                  │             MongoDB TTL auto-deletes
     │                 │                  │             RevokedToken document

Protected Request (with valid non-revoked token):
     │─── GET /contracts ─────────────── >│                      │
     │   Authorization: Bearer <token>    │                      │
     │         JwtAuthFilter:             │                      │
     │         1. isTokenValid() → true   │                      │
     │         2. isRevoked() ────────────────────────────────── >│ existsByJti()
     │                 │         ← false ────────────────────────│
     │         3. extractEmail() + extractRole()                  │
     │         4. SecurityContext set     │                      │
     │<── 200 OK + data ─────────────────│                      │
```

---

## JWT Token

### Structure

A JWT token has three Base64-encoded parts separated by dots:

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJ1c2VyQGdtYWlsLmNvbSIsInJvbGUiOiJVU0VSIn0 . SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
      HEADER                          PAYLOAD                                  SIGNATURE
```

### Header

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### Payload (Claims)

```json
{
  "jti": "550e8400-e29b-41d4-a716-446655440000",
  "sub": "user@gmail.com",
  "role": "USER",
  "iat": 1716100800,
  "exp": 1716187200
}
```

| Claim  | RFC 7519 Name | Source              | Description                                          |
|--------|---------------|---------------------|------------------------------------------------------|
| `jti`  | JWT ID        | `UUID.randomUUID()` | Unique token identifier — used for blacklisting on logout |
| `sub`  | Subject       | User's email        | User's email address                                 |
| `role` | *(custom)*    | Role at login time  | `USER` or `ADMIN`                                    |
| `iat`  | Issued At     | `new Date()`        | Unix timestamp of token creation                     |
| `exp`  | Expiration    | `iat + expiration`  | Unix timestamp after which the token is rejected     |

> **Why `jti`?** RFC 7519 defines the `jti` claim as a unique identifier for a JWT. Because each token gets a random UUID as its `jti` at generation time, the server can blacklist a specific token on logout without invalidating all tokens issued for the same user.

### Configuration

Configured in `application.properties`:

```properties
jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
jwt.expiration=86400000
```

| Property         | Value      | Description                   |
|------------------|------------|-------------------------------|
| `jwt.secret`     | 64-char hex| 256-bit HMAC-SHA256 signing key|
| `jwt.expiration` | 86400000   | Token lifetime — 24 hours (ms) |

---

## API Endpoints

### POST /auth/login

Handles both user registration and login via a single endpoint.

**URL:** `POST /auth/login`
**Auth Required:** No
**Content-Type:** `application/json`

---

#### Case 1 — New User (Auto-Registration)

**Request:**

```http
POST /auth/login
Content-Type: application/json

{
  "email": "user@gmail.com",
  "password": "secret123"
}
```

**Response — `200 OK`:**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyQGdtYWlsLmNvbSIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzE2MTAwODAwLCJleHAiOjE3MTYxODcyMDB9.signature",
  "email": "user@gmail.com",
  "newUser": true,
  "role": "USER"
}
```

---

#### Case 2 — Existing User (Login)

**Request:**

```http
POST /auth/login
Content-Type: application/json

{
  "email": "user@gmail.com",
  "password": "secret123"
}
```

**Response — `200 OK`:**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyQGdtYWlsLmNvbSIsInJvbGUiOiJVU0VSIiwiaWF0IjoxNzE2MTAwOTAwLCJleHAiOjE3MTYxODczMDB9.signature",
  "email": "user@gmail.com",
  "newUser": false,
  "role": "USER"
}
```

---

#### Case 3 — Admin Login

**Request:**

```http
POST /auth/login
Content-Type: application/json

{
  "email": "admin@gmail.com",
  "password": "adminpass123"
}
```

**Response — `200 OK`:**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "email": "admin@gmail.com",
  "newUser": true,
  "role": "ADMIN"
}
```

---

---

### POST /auth/logout

Invalidates the caller's current JWT by recording its JTI in the `revoked_tokens` MongoDB collection. All subsequent requests using the same token will be rejected with `401 Unauthorized` by `JwtAuthFilter`, even if the token has not expired yet.

**URL:** `POST /auth/logout`  
**Auth Required:** Yes — `Authorization: Bearer <token>`  

**Request:**

```http
POST /auth/logout
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

No request body is required.

**Response — `200 OK`:**

```json
{
  "message": "Logged out successfully"
}
```

> If the `Authorization` header is absent the endpoint returns `200` as a no-op — no error is raised.

**After logout — any request with the same token:**

```
HTTP/1.1 401 Unauthorized
Unauthorized
```

---

### Using the Token (Protected Endpoints)

Pass the token in the `Authorization` header for all protected routes:

```http
POST /test/upload
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: multipart/form-data
```

---

## Request & Response Schemas

### AuthRequest

```json
{
  "email": "string (required, valid email format)",
  "password": "string (required, minimum 4 characters)"
}
```

| Field      | Type   | Required | Validation                        |
|------------|--------|----------|-----------------------------------|
| `email`    | String | Yes      | Must be a valid email format      |
| `password` | String | Yes      | Minimum 4 characters              |

### AuthResponse

```json
{
  "token": "string (JWT token)",
  "email": "string",
  "newUser": "boolean",
  "role": "string (USER | ADMIN)"
}
```

| Field     | Type    | Description                                      |
|-----------|---------|--------------------------------------------------|
| `token`   | String  | JWT token to use in subsequent requests          |
| `email`   | String  | Authenticated user's email                       |
| `newUser` | Boolean | `true` if auto-registered, `false` if logged in  |
| `role`    | String  | `USER` or `ADMIN`                                |

### User (MongoDB Document)

Stored in the `users` collection:

```json
{
  "_id": "ObjectId",
  "email": "string (unique index)",
  "password": "string (BCrypt hash)",
  "role": "USER | ADMIN",
  "createdAt": "ISODate"
}
```

> **Note:** The raw `password` is never stored or returned. Only the BCrypt hash is persisted.

### RevokedToken (MongoDB Document)

Stored in the `revoked_tokens` collection. Created on every `/auth/logout` call. Automatically deleted by MongoDB's TTL mechanism once `expiresAt` passes.

```json
{
  "_id": "ObjectId",
  "jti": "string (unique index)",
  "expiresAt": "ISODate (TTL index — auto-deleted after this date)"
}
```

| Field       | Index              | Description                                                   |
|-------------|--------------------|---------------------------------------------------------------|
| `jti`       | Unique             | The JWT ID extracted from the revoked token                   |
| `expiresAt` | TTL (`expireAfterSeconds: 0`) | Mirrors the token's `exp` claim — document is auto-deleted when the original token would have expired |

> **Required configuration** for the TTL index to be created automatically:
> ```properties
> spring.data.mongodb.auto-index-creation=true
> ```

---

## Error Handling

All errors follow a consistent structure:

```json
{
  "status": 400,
  "error": "error description"
}
```

### Error Reference Table

| HTTP Status | Scenario                           | Response Body                                                                 |
|-------------|------------------------------------|-------------------------------------------------------------------------------|
| `400`       | Invalid email format               | `{ "status": 400, "error": "Validation Failed", "fields": { "email": "Invalid email format" } }` |
| `400`       | Password too short                 | `{ "status": 400, "error": "Validation Failed", "fields": { "password": "Password must be at least 4 characters" } }` |
| `400`       | Missing required fields            | `{ "status": 400, "error": "Validation Failed", "fields": { "email": "Email is required" } }` |
| `401`       | Wrong password for existing user   | `{ "status": 401, "error": "Invalid password" }`                              |
| `401`       | Missing or expired JWT token       | `401 Unauthorized`                                                            |
| `401`       | Token has been revoked (logged out)| `401 Unauthorized`                                                            |
| `403`       | Valid token but insufficient role  | `403 Forbidden`                                                               |
| `500`       | Unexpected server error            | `{ "status": 500, "error": "Internal server error" }`                         |

### Validation Error Example

**Request:**
```json
{
  "email": "notanemail",
  "password": "123"
}
```

**Response — `400 Bad Request`:**
```json
{
  "status": 400,
  "error": "Validation Failed",
  "fields": {
    "email": "Invalid email format",
    "password": "Password must be at least 4 characters"
  }
}
```

---

## Role-Based Access Control

### Roles

| Role    | Assigned When                        | Access Level               |
|---------|--------------------------------------|----------------------------|
| `USER`  | Any email except `admin@gmail.com`   | Standard protected routes  |
| `ADMIN` | Email is exactly `admin@gmail.com`   | All routes including `/admin/**` |

### Route Access Matrix

| Route                          | Public | USER | ADMIN |
|--------------------------------|--------|------|-------|
| `POST /auth/login`             | Yes    | Yes  | Yes   |
| `POST /auth/logout`            | No     | Yes  | Yes   |
| `GET /swagger-ui/index.html`   | Yes    | Yes  | Yes   |
| `GET /api-docs`                | Yes    | Yes  | Yes   |
| `GET /sign-requests/*`         | Yes    | Yes  | Yes   |
| `POST /sign-requests/*/upload/**` | Yes | Yes | Yes  |
| `GET /templates/**`            | No     | Yes  | Yes   |
| `GET /categories/**`           | No     | Yes  | Yes   |
| `POST/PUT/DELETE /templates/**`| No     | No   | Yes   |
| `POST/PUT/DELETE /categories/**`| No    | No   | Yes   |
| `GET /admin/**`                | No     | No   | Yes   |
| `/teams/**`                    | No     | Yes  | Yes   |
| `/contracts/**`                | No     | Yes  | Yes   |

### How Role is Enforced

1. On login → role is embedded in the JWT token as a claim
2. On each request → `JwtAuthFilter` extracts the role from token
3. Role is set as `SimpleGrantedAuthority("ROLE_ADMIN")` or `SimpleGrantedAuthority("ROLE_USER")` in Spring Security context
4. `SecurityConfig` enforces `.requestMatchers("/admin/**").hasRole("ADMIN")`

---

## Security Considerations

| Concern                  | Implementation                                                              |
|--------------------------|-----------------------------------------------------------------------------|
| Password storage         | BCrypt hashing — passwords are never stored in plain text                   |
| Token signing            | HMAC-SHA256 with a 256-bit secret key — tokens cannot be forged             |
| Token expiry             | 24 hours — limits the window of a stolen token                              |
| Stateless sessions       | No server-side session storage — scales horizontally                        |
| Logout / token revocation| JTI blacklist in MongoDB `revoked_tokens` — `JwtAuthFilter` checks blacklist on every request after signature validation |
| Blacklist auto-cleanup   | MongoDB TTL index on `expiresAt` — revoked token documents are deleted automatically when the original token would have expired; collection stays small |
| Unique token identity    | Each token gets a random `jti` (UUID) at generation — logout revokes only that specific token, not all tokens for the user |
| CSRF                     | Disabled — not needed for stateless token-based APIs                        |
| Transport security       | Use HTTPS in production to prevent token interception                       |
| Secret key management    | Move `jwt.secret` to environment variables in production — never commit secrets to git |
| Swagger in production    | Disable Swagger UI in production using `springdoc.swagger-ui.enabled=false` |

### Production Checklist

- [ ] Replace `jwt.secret` in `application.properties` with an environment variable (`${JWT_SECRET}`)
- [ ] Use HTTPS — configure SSL certificate on the server
- [ ] Reduce `jwt.expiration` to a shorter value (e.g., 3600000 = 1 hour) and implement refresh tokens
- [ ] Restrict admin assignment to a secure process instead of hardcoded email check
- [ ] Add rate limiting on `/auth/login` to prevent brute-force attacks
- [ ] Add request logging for audit trail
- [ ] Disable Swagger UI in production: `springdoc.swagger-ui.enabled=false`
- [ ] Restrict `/api-docs` access in production if not needed publicly
- [ ] Ensure `spring.data.mongodb.auto-index-creation=true` is set so the TTL index on `revoked_tokens.expiresAt` is created on startup
- [ ] For high-throughput systems, consider migrating the JTI blacklist from MongoDB to Redis for O(1) in-memory lookup (`TokenBlacklistService` is already isolated so the storage backend can be swapped without touching `JwtAuthFilter`)

---

## Testing Guide

### Prerequisites

- Application running on `http://localhost:8080`
- MongoDB running on `localhost:27017`
- MinIO running on `localhost:9000`

---

### Testing via Swagger UI

**Step 1** — Open `http://localhost:8080/swagger-ui/index.html`

**Step 2** — Expand **Authentication** → `POST /auth/login` → click **Try it out**

**Step 3** — Enter request body and click **Execute**:
```json
{
  "email": "user@gmail.com",
  "password": "secret123"
}
```

**Step 4** — Copy the `token` from the response

**Step 5** — Click **Authorize** (top right) → enter `Bearer <paste_token_here>` → click **Authorize**

**Step 6** — Now test any protected endpoint directly from Swagger UI — the token will be sent automatically

---

### Testing via curl

#### 1. Register / Login as New User

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"user@gmail.com\", \"password\": \"secret123\"}"
```

**Expected:** `200 OK` with `"newUser": true`, `"role": "USER"`

---

#### 2. Login as Existing User

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"user@gmail.com\", \"password\": \"secret123\"}"
```

**Expected:** `200 OK` with `"newUser": false`, `"role": "USER"`

---

#### 3. Login as Admin

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"admin@gmail.com\", \"password\": \"adminpass123\"}"
```

**Expected:** `200 OK` with `"role": "ADMIN"`

---

#### 4. Wrong Password

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"user@gmail.com\", \"password\": \"wrongpass\"}"
```

**Expected:** `401 Unauthorized` — `{ "status": 401, "error": "Invalid password" }`

---

#### 5. Validation Failure

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"notanemail\", \"password\": \"123\"}"
```

**Expected:** `400 Bad Request` with field-level validation errors

---

#### 6. Access Protected Route Without Token

```bash
curl -X POST http://localhost:8080/test/upload \
  -F "file=@/path/to/file.pdf"
```

**Expected:** `401 Unauthorized`

---

#### 7. Access Protected Route With Token

```bash
curl -X POST http://localhost:8080/test/upload \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -F "file=@/path/to/file.pdf"
```

**Expected:** `200 OK` with MinIO and MongoDB success confirmation

---

#### 8. Access Admin Route as Regular User

```bash
curl -X GET http://localhost:8080/admin/dashboard \
  -H "Authorization: Bearer USER_JWT_TOKEN_HERE"
```

**Expected:** `403 Forbidden`

---

#### 9. Logout

```bash
curl -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

**Expected:** `200 OK`
```json
{ "message": "Logged out successfully" }
```

---

#### 10. Use Revoked Token After Logout

```bash
# Use the exact same token from step 9
curl -X GET http://localhost:8080/contracts \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

**Expected:** `401 Unauthorized` — token is blacklisted even though its signature is still cryptographically valid

---

#### 11. Logout Without a Token (No-Op)

```bash
curl -X POST http://localhost:8080/auth/logout
```

**Expected:** `200 OK` — no error raised; endpoint is a no-op when the header is absent

---

### Testing via Swagger UI — Logout

**Step 1** — Authorize in Swagger UI as described above (get a token, click **Authorize**)

**Step 2** — Expand **Authentication** → `POST /auth/logout` → click **Try it out** → **Execute**

**Step 3** — You should see `200 { "message": "Logged out successfully" }`

**Step 4** — Without removing the token from the **Authorize** dialog, try any protected endpoint (e.g., `GET /contracts`)

**Step 5** — You should see `401 Unauthorized` — the token has been blacklisted

---

*Documentation generated for Contract Management System — Authentication Module v1.1.0*