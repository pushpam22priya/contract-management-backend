# Team API Documentation

**Version:** 1.0.0  
**Base URL:** `http://localhost:8080`  
**Last Updated:** 2026-06-03

---

## Table of Contents

1. [Overview](#1-overview)
2. [Authentication](#2-authentication)
3. [Error Handling](#3-error-handling)
4. [Data Model](#4-data-model)
5. [Endpoints](#5-endpoints)
   - 5.1 [Create Team](#51-create-team)
   - 5.2 [List Teams](#52-list-teams)
   - 5.3 [Rename Team](#53-rename-team)
   - 5.4 [Delete Team](#54-delete-team)
6. [Validation Reference](#6-validation-reference)
7. [End-to-End Flow](#7-end-to-end-flow)
8. [Endpoint Summary](#8-endpoint-summary)

---

## 1. Overview

A **Team** is a named folder that organises contracts. Before creating a contract, a user must create at least one team to assign it to.

### Key Principles

- Teams are **user-scoped** — each user manages their own teams independently. One user cannot see or modify another user's teams.
- Team ownership is derived from the **JWT token** — the `createdBy` field is never accepted from the request body.
- A team **cannot be deleted** if it contains contracts. All contracts inside must be removed first.
- Team names are **unique per user** (case-sensitive). Two different users may have teams with the same name.

### Team–Contract Relationship

```
Team (1) ──────────────────────── Contract (many)
 _id  ◄──────────────────────────  teamId
```

| Rule | Detail |
|---|---|
| One team → many contracts | A team is a folder for contracts |
| `contract.teamId` can be `null` | Contracts can exist without a team (root level) |
| No cascading delete | Deleting a team with contracts is blocked — HTTP 400 |
| No cross-user access | Teams are private to their creator |

---

## 2. Authentication

All endpoints require a valid JWT token in the `Authorization` header.

```
Authorization: Bearer <token>
```

Obtain a token from `POST /auth/login`. Tokens expire in **24 hours**.

### Role Requirements

| Role | Permissions |
|---|---|
| USER | Full access to all team endpoints |
| ADMIN | Full access to all team endpoints |

> Unlike templates, team operations are **not restricted to ADMIN**. Any authenticated user can create and manage their own teams.

---

## 3. Error Handling

All error responses follow this structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "A team with this name already exists"
}
```

| Field | Type | Description |
|---|---|---|
| status | Integer | HTTP status code |
| error | String | Short error category |
| message | String | Human-readable description |

### Status Code Reference

| Code | Error | Common Cause |
|---|---|---|
| 400 | Bad Request | Validation failed or business rule violated |
| 401 | Unauthorized | Token missing or expired |
| 404 | Not Found | Team ID does not exist or belongs to another user |
| 500 | Internal Server Error | Unexpected server-side failure |

---

## 4. Data Model

**MongoDB Collection:** `teams`

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "name": "Legal",
  "createdBy": "user@company.com",
  "createdAt": "2026-06-02T10:00:00",
  "updatedAt": "2026-06-02T10:00:00"
}
```

| Field | Type | Description |
|---|---|---|
| id | String | MongoDB auto-generated ID |
| name | String | Team display name. Max 50 characters. Unique per user. |
| createdBy | String | Email of the owning user. Sourced from JWT token — never from request body. |
| createdAt | DateTime | Timestamp when the team was created. Set by server. |
| updatedAt | DateTime | Timestamp of the last update. Set by server. |

---

## 5. Endpoints

### 5.1 Create Team

Creates a new team owned by the authenticated user.

```
POST /teams
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Request Body**

```json
{
  "name": "Legal"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| name | String | Yes | Min 1 character after trim. Max 50 characters. Must be unique for this user. |

> `createdBy` is always taken from the JWT token. Do not include it in the request body — it will be ignored.

**Response — 201 Created**

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "name": "Legal",
  "createdBy": "user@company.com",
  "createdAt": "2026-06-02T10:00:00",
  "updatedAt": "2026-06-02T10:00:00"
}
```

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Team name is required | `name` is blank or missing |
| 400 | Team name must be 50 characters or less | `name` exceeds 50 characters |
| 400 | A team with this name already exists | Duplicate name for this user |
| 401 | Unauthorized | Token missing or expired |

**curl**

```bash
curl -X POST http://localhost:8080/teams \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Legal\"}"
```

---

### 5.2 List Teams

Returns all teams owned by the authenticated user, sorted by creation date descending (newest first).

```
GET /teams
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Response — 200 OK**

```json
[
  {
    "id": "683a1f2c9d4e5b0012345678",
    "name": "Legal",
    "createdBy": "user@company.com",
    "createdAt": "2026-06-02T10:00:00",
    "updatedAt": "2026-06-02T10:00:00"
  },
  {
    "id": "683a1f2c9d4e5b0087654321",
    "name": "HR",
    "createdBy": "user@company.com",
    "createdAt": "2026-05-15T08:30:00",
    "updatedAt": "2026-05-15T08:30:00"
  }
]
```

Returns an empty array `[]` if the user has no teams.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Unauthorized | Token missing or expired |

**curl**

```bash
curl -X GET http://localhost:8080/teams \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

### 5.3 Rename Team

Updates the name of an existing team. All other fields remain unchanged.

```
PUT /teams/{id}
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the team to rename |

**Request Body**

```json
{
  "name": "Legal & Compliance"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| name | String | Yes | Min 1 character after trim. Max 50 characters. Must be unique for this user (excluding the team being renamed). |

**Response — 200 OK**

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "name": "Legal & Compliance",
  "createdBy": "user@company.com",
  "createdAt": "2026-06-02T10:00:00",
  "updatedAt": "2026-06-02T11:30:00"
}
```

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Team name is required | `name` is blank or missing |
| 400 | Team name must be 50 characters or less | `name` exceeds 50 characters |
| 400 | A team with this name already exists | Another team of this user already has this name |
| 401 | Unauthorized | Token missing or expired |
| 404 | Team not found | ID does not exist or belongs to another user |

**curl**

```bash
curl -X PUT http://localhost:8080/teams/TEAM_ID \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Legal & Compliance\"}"
```

---

### 5.4 Delete Team

Permanently deletes a team. Blocked if the team contains any contracts.

```
DELETE /teams/{id}
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the team to delete |

**Response — 204 No Content**

Empty body. Team is permanently removed from MongoDB.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Cannot delete: this team contains N contract(s) | Team has contracts — remove them first |
| 401 | Unauthorized | Token missing or expired |
| 404 | Team not found | ID does not exist or belongs to another user |

**curl**

```bash
curl -X DELETE http://localhost:8080/teams/TEAM_ID \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

## 6. Validation Reference

### Name Uniqueness Rules

| Scenario | Allowed |
|---|---|
| User A creates "Legal", User B creates "Legal" | Yes — uniqueness is per user |
| User A creates "Legal", User A creates "Legal" again | No — 400 |
| User A creates "Legal", User A renames it to "Legal" | Yes — renaming to the same name is a no-op |
| User A creates "Legal", User A renames "HR" to "Legal" | No — 400 |

### Delete Rules

| Scenario | Result |
|---|---|
| Team has 0 contracts | 204 — deleted |
| Team has 1 or more contracts | 400 — "Cannot delete: this team contains N contract(s)" |
| Team ID doesn't exist | 404 — "Team not found" |
| Team ID belongs to another user | 404 — "Team not found" (same error to prevent leaking other users' data) |

---

## 7. End-to-End Flow

```
Step 1   POST /auth/login
         Body: { email, password }
         ← { token }

Step 2   POST /teams
         Headers: Authorization: Bearer <token>
         Body: { name: "Legal" }
         ← { id, name, createdBy, createdAt, updatedAt }

Step 3   GET /teams
         ← [ { id, name, ... }, ... ]   (all teams for this user)

Step 4   PUT /teams/{id}                (optional — rename)
         Body: { name: "Legal & Compliance" }
         ← { id, name (updated), updatedAt (updated), ... }

Step 5   DELETE /teams/{id}            (optional — delete when empty)
         ← 204 No Content
```

---

## 8. Endpoint Summary

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/teams` | Authenticated | Create a new team |
| GET | `/teams` | Authenticated | List all teams owned by the current user |
| PUT | `/teams/{id}` | Authenticated | Rename a team |
| DELETE | `/teams/{id}` | Authenticated | Delete a team (blocked if it has contracts) |
