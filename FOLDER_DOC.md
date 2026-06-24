# Folder API Documentation

**Version:** 1.1.0  
**Base URL:** `http://localhost:8080`  
**Last Updated:** 2026-06-24

---

## Table of Contents

1. [Overview](#1-overview)
2. [Authentication](#2-authentication)
3. [Error Handling](#3-error-handling)
4. [Data Model](#4-data-model)
5. [Endpoints](#5-endpoints)
   - 5.1 [Create Folder](#51-create-folder)
   - 5.2 [List Folders](#52-list-folders)
   - 5.3 [Rename Folder](#53-rename-folder)
   - 5.4 [Delete Folder](#54-delete-folder)
6. [Validation Reference](#6-validation-reference)
7. [End-to-End Flow](#7-end-to-end-flow)
8. [Endpoint Summary](#8-endpoint-summary)

---

## 1. Overview

A **Folder** is a named container that organises contracts. Users can create folders to group related contracts together.

### Key Principles

- Folders are **user-scoped** — each user manages their own folders independently. One user cannot see or modify another user's folders.
- Folder ownership is derived from the **JWT token** — the `createdBy` field is never accepted from the request body.
- A folder **cannot be deleted** if it contains contracts. All contracts inside must be moved or removed first.
- Folder names are **unique per user** (case-sensitive). Two different users may have folders with the same name.

### Folder–Contract Relationship

```
Folder (1) ─────────────────────── Contract (many)
 _id  ◄──────────────────────────── folderId
```

| Rule | Detail |
|---|---|
| One folder → many contracts | A folder is a container for contracts |
| `contract.folderId` can be `null` | Contracts can exist without a folder (root level) |
| No cascading delete | Deleting a folder with contracts is blocked — HTTP 400 |
| No cross-user access | Folders are private to their creator |

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
| USER | Full access to all folder endpoints |
| ADMIN | Full access to all folder endpoints |

> Folder operations are **not restricted to ADMIN**. Any authenticated user can create and manage their own folders.

---

## 3. Error Handling

All error responses follow this structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "A folder with this name already exists"
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
| 404 | Not Found | Folder ID does not exist or belongs to another user |
| 500 | Internal Server Error | Unexpected server-side failure |

---

## 4. Data Model

**MongoDB Collection:** `folders`

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
| name | String | Folder display name. Max 50 characters. Unique per user. |
| createdBy | String | Email of the owning user. Sourced from JWT token — never from request body. |
| createdAt | DateTime | Timestamp when the folder was created. Set by server. |
| updatedAt | DateTime | Timestamp of the last update. Set by server. |

---

## 5. Endpoints

### 5.1 Create Folder

Creates a new folder owned by the authenticated user.

```
POST /folders
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
| 400 | Folder name is required | `name` is blank or missing |
| 400 | Folder name must be 50 characters or less | `name` exceeds 50 characters |
| 400 | A folder with this name already exists | Duplicate name for this user |
| 401 | Unauthorized | Token missing or expired |

**curl**

```bash
curl -X POST http://localhost:8080/folders \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Legal\"}"
```

---

### 5.2 List Folders

Returns all folders owned by the authenticated user, sorted by creation date descending (newest first).

```
GET /folders
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

Returns an empty array `[]` if the user has no folders.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Unauthorized | Token missing or expired |

**curl**

```bash
curl -X GET http://localhost:8080/folders \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

### 5.3 Rename Folder

Updates the name of an existing folder. All other fields remain unchanged.

```
PUT /folders/{id}
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the folder to rename |

**Request Body**

```json
{
  "name": "Legal & Compliance"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| name | String | Yes | Min 1 character after trim. Max 50 characters. Must be unique for this user (excluding the folder being renamed). |

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
| 400 | Folder name is required | `name` is blank or missing |
| 400 | Folder name must be 50 characters or less | `name` exceeds 50 characters |
| 400 | A folder with this name already exists | Another folder of this user already has this name |
| 401 | Unauthorized | Token missing or expired |
| 404 | Folder not found | ID does not exist or belongs to another user |

**curl**

```bash
curl -X PUT http://localhost:8080/folders/FOLDER_ID \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Legal & Compliance\"}"
```

---

### 5.4 Delete Folder

Permanently deletes a folder. Blocked if the folder contains any contracts.

```
DELETE /folders/{id}
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the folder to delete |

**Response — 204 No Content**

Empty body. Folder is permanently removed from MongoDB.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Cannot delete: this folder contains N contract(s) | Folder has contracts — remove them first |
| 401 | Unauthorized | Token missing or expired |
| 404 | Folder not found | ID does not exist or belongs to another user |

**curl**

```bash
curl -X DELETE http://localhost:8080/folders/FOLDER_ID \
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
| Folder has 0 contracts | 204 — deleted |
| Folder has 1 or more contracts | 400 — "Cannot delete: this folder contains N contract(s)" |
| Folder ID doesn't exist | 404 — "Folder not found" |
| Folder ID belongs to another user | 404 — "Folder not found" (same error to prevent leaking other users' data) |

---

## 7. End-to-End Flow

```
Step 1   POST /auth/login
         Body: { email, password }
         ← { token }

Step 2   POST /folders
         Headers: Authorization: Bearer <token>
         Body: { name: "Legal" }
         ← { id, name, createdBy, createdAt, updatedAt }

Step 3   GET /folders
         ← [ { id, name, ... }, ... ]   (all folders for this user)

Step 4   PUT /folders/{id}                (optional — rename)
         Body: { name: "Legal & Compliance" }
         ← { id, name (updated), updatedAt (updated), ... }

Step 5   DELETE /folders/{id}            (optional — delete when empty)
         ← 204 No Content
```

---

## 8. Endpoint Summary

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/folders` | Authenticated | Create a new folder |
| GET | `/folders` | Authenticated | List all folders owned by the current user |
| PUT | `/folders/{id}` | Authenticated | Rename a folder |
| DELETE | `/folders/{id}` | Authenticated | Delete a folder (blocked if it has contracts) |
