# Contract API Documentation

**Version:** 1.3.0  
**Base URL:** `http://localhost:8080`  
**Last Updated:** 2026-06-22

---

## Table of Contents

1. [Overview](#1-overview)
2. [Authentication](#2-authentication)
3. [Error Handling](#3-error-handling)
4. [Data Models](#4-data-models)
   - 4.1 [Contract Object](#41-contract-object)
   - 4.2 [ContractListItem Object](#42-contractlistitem-object)
   - 4.3 [Party Object](#43-party-object)
   - 4.4 [ContractStatus Enum](#44-contractstatus-enum)
5. [Endpoints](#5-endpoints)
   - 5.1 [Create Contract](#51-create-contract)
   - 5.2 [List Contracts](#52-list-contracts)
   - 5.3 [Get Contract](#53-get-contract)
   - 5.4 [Update Contract](#54-update-contract)
   - 5.5 [Upload File (Single-Shot)](#55-upload-file-single-shot)
   - 5.6 [Get View URL](#56-get-view-url)
   - 5.7 [Initiate Chunked Upload](#57-initiate-chunked-upload)
   - 5.8 [Get Presigned Part URL](#58-get-presigned-part-url)
   - 5.9 [Complete Chunked Upload](#59-complete-chunked-upload)
   - 5.10 [Abort Chunked Upload](#510-abort-chunked-upload)
   - 5.11 [Terminate Contract](#511-terminate-contract)
6. [End-to-End Flows](#6-end-to-end-flows)
   - 6.1 [Metadata Only](#61-metadata-only)
   - 6.2 [Small File — Single-Shot Upload](#62-small-file--single-shot-upload)
   - 6.3 [Large File — Chunked Upload](#63-large-file--chunked-upload)
   - 6.4 [Terminate a Contract](#64-terminate-a-contract)
7. [Business Rules](#7-business-rules)
8. [Automatic Cleanup](#8-automatic-cleanup)
9. [Endpoint Summary](#9-endpoint-summary)

---

## 1. Overview

The Contract API manages the full lifecycle of a contract — from creation and file upload through viewing and metadata updates.

### Architecture

```
Frontend
   │
   ├── POST /contracts                   → Create contract record (returns id)
   ├── PATCH /contracts/{id}             → Update metadata or persist xfdfData
   ├── GET  /contracts                   → List contracts (lightweight)
   ├── GET  /contracts/{id}              → Full contract detail
   │
   ├── PUT  /contracts/{id}/file         → Direct upload for files < 30 MB
   │
   ├── POST /contracts/{id}/file/initiate   ┐
   ├── GET  /contracts/{id}/file/presign    ├ Chunked upload for files ≥ 30 MB
   ├── POST /contracts/{id}/file/complete   │
   ├── POST /contracts/{id}/file/abort      ┘
   │
   └── GET  /contracts/{id}/file/view-url  → Time-limited MinIO view link
```

### Key Principles

- Contracts are **user-scoped** — a user can only see and modify their own contracts.
- Ownership is derived from the **JWT token**, never from the request body.
- Contract titles are **unique per user** (case-sensitive).
- Every contract starts in **DRAFT** status.
- The contract **metadata record is created first**, then the file is uploaded separately using the returned `id`.
- The file is stored in **MinIO** at path `contracts/{id}.pdf`. The app server never buffers large files.
- Presigned URLs are generated for both upload (PUT) and viewing (GET), and expire in **15 minutes**.

---

## 2. Authentication

All `/contracts/**` endpoints require a valid JWT token.

```
Authorization: Bearer <token>
```

Obtain a token from `POST /auth/login`. Tokens expire in **24 hours**.

### Role Requirements

| Role  | Permissions                     |
|-------|---------------------------------|
| USER  | Full access to all contract endpoints |
| ADMIN | Full access to all contract endpoints |

---

## 3. Error Handling

All error responses follow this structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "A contract with this title already exists"
}
```

### Status Code Reference

| Code | Error                 | Common Cause                                              |
|------|-----------------------|-----------------------------------------------------------|
| 400  | Bad Request           | Validation failed or business rule violated               |
| 401  | Unauthorized          | Token missing or expired                                  |
| 403  | Forbidden             | Authenticated but insufficient role                      |
| 404  | Not Found             | Contract ID does not exist or belongs to another user     |
| 500  | Internal Server Error | Unexpected server-side failure                            |

> **Security note:** 404 is returned for both "not found" and "belongs to another user" to prevent leaking other users' contract IDs.

---

## 4. Data Models

### 4.1 Contract Object

Returned by `POST /contracts`, `GET /contracts/{id}`, and `PATCH /contracts/{id}`.  
Includes all fields — suitable for the detail/editor view.

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "title": "Service Agreement Q3",
  "client": "Acme Corp",
  "description": "Quarterly service contract for Acme Corp",
  "category": "Services",
  "status": "DRAFT",
  "startDate": "2026-06-03",
  "endDate": "2027-06-03",
  "expiresInDays": 365,
  "templateId": "template_abc123",
  "templateName": "Standard Service Agreement",
  "templateFileName": "service-agreement.pdf",
  "xfdfData": "<xfdf>...</xfdf>",
  "fieldValues": {
    "clientName": "Acme Corp",
    "contractValue": "10000"
  },
  "formFields": [
    { "name": "clientName", "type": "text", "value": "Acme Corp" }
  ],
  "hasFormFields": true,
  "parties": [
    { "id": "p1", "label": "Service Provider", "color": "#4A90E2", "order": 1 },
    { "id": "p2", "label": "Client",           "color": "#E24A4A", "order": 2 }
  ],
  "fileUploaded": false,
  "folderId": "683a1f2c9d4e5b0087654321",
  "createdBy": "user@company.com",
  "createdAt": "2026-06-03T10:00:00",
  "updatedAt": "2026-06-03T10:00:00",
  "terminatedAt": null,
  "terminatedBy": null,
  "renewalStatus": null,
  "renewedContractId": null,
  "renewedFromId": null,
  "renewalStartDate": null,
  "renewalNotes": null
}
```

| Field            | Type              | Description                                                                 |
|------------------|-------------------|-----------------------------------------------------------------------------|
| id               | String            | MongoDB auto-generated ID                                                   |
| title            | String            | Contract display name. Unique per user.                                     |
| client           | String            | Name of the counterparty                                                    |
| description      | String            | Auto-generated from templateName if not provided                            |
| category         | String            | Contract category (e.g. Services, NDA, Employment). Max 50 chars.           |
| status           | ContractStatus    | See [ContractStatus Enum](#44-contractstatus-enum). Always `DRAFT` on create. For finalized contracts, returns a computed value (`ACTIVE`, `EXPIRING`, or `EXPIRED`) based on dates — see note below. |
| startDate        | LocalDate         | Defaults to today if not provided                                           |
| endDate          | LocalDate         | Defaults to `startDate + 1 year` if not provided                            |
| expiresInDays    | Long              | Days until endDate from today. Negative = already expired                   |
| templateId       | String            | ID of the template used to create this contract                             |
| templateName     | String            | Display name of the template                                                |
| templateFileName | String            | Original PDF filename of the template                                       |
| xfdfData         | String            | Apryse/WebViewer annotation XML. Persisted via `PATCH /{id}`                |
| fieldValues      | Map<String,String>| Key-value pairs of filled form field data                                   |
| formFields       | List<Object>      | Full form field definitions from the template                               |
| hasFormFields    | Boolean           | `true` if this contract has interactive PDF form fields                     |
| parties          | List<Party>       | Signing parties assigned to this contract                                   |
| fileUploaded     | Boolean           | `true` once a PDF has been successfully uploaded to MinIO                   |
| folderId         | String            | Optional. ID of the folder this contract belongs to                         |
| createdBy        | String            | Email of the owning user. Set from JWT — never from request body            |
| createdAt        | DateTime          | Creation timestamp. Set by server                                           |
| updatedAt        | DateTime          | Last modification timestamp. Set by server                                  |
| terminatedAt     | DateTime \| null  | Timestamp when the contract was terminated. `null` until terminated.        |
| terminatedBy     | String \| null    | Email of the user who terminated the contract. `null` until terminated.     |
| renewalStatus    | String \| null    | `"in_progress"` when a renewal draft is active. `null` otherwise. Cleared on termination. |
| renewedContractId | String \| null   | ID of the renewal draft contract created from this one. `null` until a renewal is created. Cleared on termination. |
| renewedFromId    | String \| null    | ID of the original contract this was renewed from. `null` if this is not a renewal. |
| renewalStartDate | String \| null    | ISO date of the pending renewal's start date. Used for UI tooltip display.  |
| renewalNotes     | String \| null    | Optional notes entered when the renewal was created.                        |

> **Status computation note:** Once a contract reaches the `SIGNED` state, the `status` field in every response is computed at read time from `startDate` and `endDate`. The database always stores `SIGNED` — the API returns `ACTIVE`, `EXPIRING`, or `EXPIRED` based on current date logic. See [ContractStatus Enum](#44-contractstatus-enum) for the full rules.

---

### 4.2 ContractListItem Object

Returned by `GET /contracts`. Lightweight — excludes heavy fields (`xfdfData`, `fieldValues`, `formFields`, `parties`) to keep list responses fast.

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "title": "Service Agreement Q3",
  "client": "Acme Corp",
  "description": "Quarterly service contract",
  "category": "Services",
  "status": "DRAFT",
  "startDate": "2026-06-03",
  "endDate": "2027-06-03",
  "expiresInDays": 365,
  "templateId": "template_abc123",
  "templateName": "Standard Service Agreement",
  "hasFormFields": true,
  "fileUploaded": false,
  "folderId": "683a1f2c9d4e5b0087654321",
  "createdBy": "user@company.com",
  "createdAt": "2026-06-03T10:00:00",
  "updatedAt": "2026-06-03T10:00:00"
}
```

> `xfdfData`, `fieldValues`, `formFields`, and `parties` are **not included** in list responses. Use `GET /contracts/{id}` to fetch these for a specific contract.

> **Status field:** For contracts in a post-finalization state, `status` is computed at read time and will be `ACTIVE`, `EXPIRING`, or `EXPIRED` — never the raw `SIGNED` value stored in the database. Use the returned `status` directly; do not re-derive it from `startDate`/`endDate` on the frontend.

---

### 4.3 Party Object

```json
{
  "id": "p1",
  "label": "Service Provider",
  "color": "#4A90E2",
  "order": 1
}
```

| Field | Type    | Description                                    |
|-------|---------|------------------------------------------------|
| id    | String  | Client-assigned identifier for the party       |
| label | String  | Display name (e.g. "Client", "Service Provider") |
| color | String  | Hex color code for UI rendering                |
| order | Integer | Sequence order for signing                     |

---

### 4.4 ContractStatus Enum

#### Workflow Statuses — stored in the database

These values are written by the application workflow and are returned as-is in every API response.

| Status | Description |
|---|---|
| `DRAFT` | Default on creation. Contract metadata is entered but not yet submitted for review. |
| `IN_REVIEW` | Submitted for internal review. Reviewers can approve or reject. |
| `IN_APPROVAL` | Passed review and is now under approval. Approvers can approve or reject. |
| `READY_FOR_SIGNATURE` | Approved and ready to be sent out for e-signatures. |
| `IN_SIGNATURE` | Signature round in progress — at least one signer has been notified. |
| `SIGNED_BY_EVERYONE` | All required parties have signed. Awaiting contractor finalization. |
| `SIGNED` | Contractor has finalized the contract. Final PDF generated and sent to all parties. **This is the stored DB value for all post-finalization states — it is never returned directly in a response once dates are set; see computed statuses below.** |
| `TERMINATED` | Manually terminated before the natural `endDate`. Never recomputed. |
| `REJECTED_BY_REVIEWER` | Reviewer rejected the contract during `IN_REVIEW`. |
| `REJECTED_BY_APPROVER` | Approver rejected the contract during `IN_APPROVAL`. |

#### Computed Statuses — derived at read time, never stored

Once a contract is `SIGNED`, the API computes the effective status from `startDate`, `endDate`, and the current date on every read. The database continues to store `SIGNED`; these values only appear in API responses.

| Status | Condition | Description |
|---|---|---|
| `ACTIVE` | `startDate ≤ today` AND `endDate > today + 30 days` | Contract is in force and not approaching expiry. |
| `EXPIRING` | `endDate` is within **30 days** (inclusive) from today | Contract is running but approaching its end date. Show expiry warning UI. |
| `EXPIRED` | `endDate < today` | Contract has passed its end date. |

> **Computation priority:** EXPIRED is checked first, then EXPIRING (≤ 30 days), then ACTIVE (startDate ≤ today). If `endDate` is null, the status stays `SIGNED`. If `startDate` is in the future (and endDate > 30 days), the status stays `SIGNED` — the contract is finalized but not yet in force.

> **Do not filter** `GET /contracts?status=ACTIVE` or `?status=EXPIRING` or `?status=EXPIRED` — these values are never stored in the database. The query will return zero results. Apply post-finalization status filtering client-side using the `status` field from the response.

> Status transitions are managed by the application workflow. The `status` field in `PATCH /{id}` is not writable — status changes occur only through workflow actions.

---

## 5. Endpoints

### 5.1 Create Contract

Creates the contract metadata record. Returns the `id` to use for subsequent file uploads.

```
POST /contracts
```

**Headers**

| Header        | Required | Value              |
|---------------|----------|--------------------|
| Authorization | Yes      | `Bearer <token>`   |
| Content-Type  | Yes      | `application/json` |

**Request Body**

```json
{
  "title": "Service Agreement Q3",
  "client": "Acme Corp",
  "templateId": "template_abc123",
  "templateName": "Standard Service Agreement",
  "templateFileName": "service-agreement.pdf",
  "description": "Quarterly service contract for Acme Corp",
  "category": "Services",
  "startDate": "2026-06-03",
  "endDate": "2027-06-03",
  "folderId": "683a1f2c9d4e5b0087654321",
  "hasFormFields": true,
  "fieldValues": {
    "clientName": "Acme Corp",
    "contractValue": "10000"
  },
  "formFields": [
    { "name": "clientName", "type": "text", "value": "Acme Corp" }
  ],
  "parties": [
    { "id": "p1", "label": "Service Provider", "color": "#4A90E2", "order": 1 },
    { "id": "p2", "label": "Client",           "color": "#E24A4A", "order": 2 }
  ]
}
```

**Field Reference**

| Field            | Type    | Required | Rules                                                                       |
|------------------|---------|----------|-----------------------------------------------------------------------------|
| title            | String  | Yes      | Min 1 char after trim. Max 50 chars. Must be unique for this user.          |
| client           | String  | Yes      | Min 1 char after trim. Max 50 chars.                                        |
| templateId       | String  | Yes      | Must reference an existing template ID                                      |
| templateName     | String  | No       | Display name stored for reference                                           |
| templateFileName | String  | No       | Original PDF filename stored for reference                                  |
| description      | String  | No       | Max 500 chars. Auto-filled as `"Contract based on {templateName}"` if omitted |
| category         | String  | No       | Free-text category label. Max 50 chars.                                     |
| startDate        | Date    | No       | `YYYY-MM-DD`. Defaults to today if omitted                                  |
| endDate          | Date    | No       | `YYYY-MM-DD`. Must be ≥ startDate. Defaults to `startDate + 1 year`         |
| folderId         | String  | No       | ID of the folder to assign this contract to                                 |
| hasFormFields    | Boolean | No       | Pass `true` if the template has interactive PDF form fields                 |
| fieldValues      | Object  | No       | Map of form field names to their filled values                              |
| formFields       | Array   | No       | Full form field definitions from the template                               |
| parties          | Array   | No       | Signing party definitions. See [Party Object](#43-party-object)            |
| xfdfData         | String  | No       | Apryse annotation XML (usually sent via `PATCH` after PDF editing)         |

**Response — 201 Created**

Returns the full [Contract Object](#41-contract-object) with `status: "DRAFT"` and `fileUploaded: false`.

**Error Responses**

| Status | Message                                   | Cause                                     |
|--------|-------------------------------------------|-------------------------------------------|
| 400    | Contract title is required                   | `title` is blank or missing               |
| 400    | Contract title must be 50 characters or less | `title` exceeds 50 chars                  |
| 400    | Client name is required                      | `client` is blank or missing              |
| 400    | Client name must be 50 characters or less    | `client` exceeds 50 chars                 |
| 400    | Template ID is required                      | `templateId` is blank or missing          |
| 400    | Description must be 500 characters or less   | `description` exceeds 500 chars           |
| 400    | Category must be 50 characters or less       | `category` exceeds 50 chars               |
| 400    | End date must be on or after start date      | `endDate` is before `startDate`           |
| 400    | A contract with this title already exists    | Duplicate title for this user             |
| 401    | Unauthorized                                 | Token missing or expired                  |

**curl**

```bash
curl -X POST http://localhost:8080/contracts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Service Agreement Q3",
    "client": "Acme Corp",
    "templateId": "template_abc123",
    "templateName": "Standard Service Agreement",
    "templateFileName": "service-agreement.pdf",
    "startDate": "2026-06-03",
    "endDate": "2027-06-03"
  }'
```

---

### 5.2 List Contracts

Returns all contracts owned by the authenticated user, sorted by creation date descending (newest first). Supports optional filtering by folder and/or status.

```
GET /contracts
```

**Headers**

| Header        | Required | Value            |
|---------------|----------|------------------|
| Authorization | Yes      | `Bearer <token>` |

**Query Parameters**

| Parameter | Type   | Required | Description                                                              |
|-----------|--------|----------|--------------------------------------------------------------------------|
| folderId  | String | No       | Filter by folder ID. Returns only contracts assigned to this folder.     |
| status    | String | No       | Filter by status. Case-insensitive. Matches the value **stored in the database**. See [ContractStatus Enum](#44-contractstatus-enum). **Do not use `ACTIVE`, `EXPIRING`, or `EXPIRED` here** — these are computed values, never stored. Use `SIGNED` to retrieve all finalized contracts and filter client-side. |

**Response — 200 OK**

Returns an array of [ContractListItem](#42-contractlistitem-object) objects.  
Returns `[]` if the user has no contracts matching the filters.

**Error Responses**

| Status | Message      | Cause                     |
|--------|--------------|---------------------------|
| 400    | Bad Request  | Invalid `status` value     |
| 401    | Unauthorized | Token missing or expired   |

**curl — All contracts**

```bash
curl -X GET http://localhost:8080/contracts \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**curl — Filter by folder**

```bash
curl -X GET "http://localhost:8080/contracts?folderId=683a1f2c9d4e5b0087654321" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**curl — Filter by status**

```bash
curl -X GET "http://localhost:8080/contracts?status=DRAFT" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**curl — Filter by both**

```bash
curl -X GET "http://localhost:8080/contracts?folderId=683a1f2c9d4e5b0087654321&status=SIGNED" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

> To retrieve all finalized contracts regardless of computed status (`ACTIVE`, `EXPIRING`, `EXPIRED`), filter by `status=SIGNED`. The response will include the computed status for each contract — filter further client-side if needed.

---

### 5.3 Get Contract

Returns the full contract including `xfdfData`, `fieldValues`, `formFields`, and `parties`.

```
GET /contracts/{id}
```

**Headers**

| Header        | Required | Value            |
|---------------|----------|------------------|
| Authorization | Yes      | `Bearer <token>` |

**Path Parameters**

| Parameter | Description                  |
|-----------|------------------------------|
| id        | MongoDB ID of the contract   |

**Response — 200 OK**

Returns the full [Contract Object](#41-contract-object).

**Error Responses**

| Status | Message             | Cause                                           |
|--------|---------------------|-------------------------------------------------|
| 401    | Unauthorized        | Token missing or expired                        |
| 404    | Contract not found  | ID doesn't exist or belongs to another user     |

**curl**

```bash
curl -X GET http://localhost:8080/contracts/CONTRACT_ID \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

### 5.4 Update Contract

Partial update (PATCH) — only fields present in the request body are updated. All other fields remain unchanged. Use this to update metadata fields or to persist `xfdfData` after the user edits the PDF in WebViewer.

```
PATCH /contracts/{id}
```

**Headers**

| Header        | Required | Value              |
|---------------|----------|--------------------|
| Authorization | Yes      | `Bearer <token>`   |
| Content-Type  | Yes      | `application/json` |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Request Body**

Send only the fields you want to change. All fields are optional.

```json
{
  "title": "Service Agreement Q3 — Revised",
  "client": "Acme Corp Ltd",
  "description": "Updated scope of services",
  "category": "Services",
  "startDate": "2026-06-03",
  "endDate": "2027-12-31",
  "folderId": "683a1f2c9d4e5b0087654321",
  "xfdfData": "<xfdf>...</xfdf>",
  "fieldValues": { "contractValue": "15000" },
  "formFields": [],
  "parties": [
    { "id": "p1", "label": "Provider", "color": "#4A90E2", "order": 1 }
  ]
}
```

**Field Rules for PATCH**

| Field     | Rule                                                                 |
|-----------|----------------------------------------------------------------------|
| title     | If provided: must be unique for this user (excluding this contract). Max 50 chars. |
| endDate   | If provided: must be ≥ startDate (uses new startDate if also provided, otherwise existing) |
| All others | Same validation as create. Nulls are ignored — field is left unchanged |

**Response — 200 OK**

Returns the full updated [Contract Object](#41-contract-object).

**Error Responses**

| Status | Message                                   | Cause                                          |
|--------|-------------------------------------------|------------------------------------------------|
| 400    | A contract with this title already exists | Title conflict with another contract           |
| 400    | End date must be on or after start date   | Invalid date range                             |
| 401    | Unauthorized                              | Token missing or expired                       |
| 404    | Contract not found                        | ID doesn't exist or belongs to another user    |

**curl — Update title only**

```bash
curl -X PATCH http://localhost:8080/contracts/CONTRACT_ID \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "Service Agreement Q3 — Revised"}'
```

**curl — Persist xfdfData after PDF editing**

```bash
curl -X PATCH http://localhost:8080/contracts/CONTRACT_ID \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"xfdfData": "<xfdf>...</xfdf>"}'
```

---

### 5.5 Upload File (Single-Shot)

Uploads the completed PDF directly to MinIO in a single request. For files **under 30 MB only**. For larger files use the [Chunked Upload](#57-initiate-chunked-upload) flow.

```
PUT /contracts/{id}/file
```

**Headers**

| Header         | Required | Value                      |
|----------------|----------|----------------------------|
| Authorization  | Yes      | `Bearer <token>`           |
| Content-Type   | Yes      | `application/pdf`          |
| Content-Length | Yes      | File size in bytes         |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Request Body**

Raw PDF binary — no multipart encoding. Send the file bytes directly.

**Response — 200 OK**

Empty body. The contract's `fileUploaded` field is set to `true` in MongoDB.

**Error Responses**

| Status | Message                                                                     | Cause                                       |
|--------|-----------------------------------------------------------------------------|---------------------------------------------|
| 400    | Content-Length header is required                                           | Missing `Content-Length` header             |
| 400    | File is 30MB or larger — use the chunked upload endpoints instead           | File too large for single-shot              |
| 400    | File is not a valid PDF                                                     | File does not start with `%PDF` magic bytes |
| 401    | Unauthorized                                                                | Token missing or expired                    |
| 404    | Contract not found                                                          | ID doesn't exist or belongs to another user |

**curl**

```bash
curl -X PUT http://localhost:8080/contracts/CONTRACT_ID/file \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/pdf" \
  -H "Content-Length: $(wc -c < contract.pdf)" \
  --data-binary @contract.pdf
```

---

### 5.6 Get View URL

Generates a time-limited presigned MinIO URL for viewing the uploaded PDF in Apryse WebViewer. The URL is valid for **15 minutes**.

```
GET /contracts/{id}/file/view-url
```

**Headers**

| Header        | Required | Value            |
|---------------|----------|------------------|
| Authorization | Yes      | `Bearer <token>` |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Response — 200 OK**

```json
{
  "url": "http://localhost:9000/contract-management/contracts/CONTRACT_ID.pdf?X-Amz-Algorithm=..."
}
```

| Field | Type   | Description                                      |
|-------|--------|--------------------------------------------------|
| url   | String | Presigned MinIO GET URL. Valid for 15 minutes.   |

**Error Responses**

| Status | Message                             | Cause                                           |
|--------|-------------------------------------|-------------------------------------------------|
| 400    | File not yet uploaded for this contract | `fileUploaded` is still `false`             |
| 401    | Unauthorized                        | Token missing or expired                        |
| 404    | Contract not found                  | ID doesn't exist or belongs to another user     |

**curl**

```bash
curl -X GET http://localhost:8080/contracts/CONTRACT_ID/file/view-url \
  -H "Authorization: Bearer YOUR_TOKEN"
```

> The returned `url` goes directly to MinIO — pass it to `WebViewer({ url: "..." })` on the frontend. Do not add authentication headers when calling MinIO directly; the signature is already embedded in the URL.

---

### 5.7 Initiate Chunked Upload

Starts a MinIO multipart upload session. Returns an `uploadId` that is required for all subsequent chunk operations. The `uploadId` is also persisted in MongoDB so the cleanup scheduler can abort abandoned sessions.

```
POST /contracts/{id}/file/initiate
```

**Headers**

| Header        | Required | Value            |
|---------------|----------|------------------|
| Authorization | Yes      | `Bearer <token>` |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Response — 200 OK**

```json
{
  "uploadId": "abc123xyz...",
  "templateId": "683a1f2c9d4e5b0012345678"
}
```

| Field      | Type   | Description                                                  |
|------------|--------|--------------------------------------------------------------|
| uploadId   | String | MinIO multipart upload session ID. Use in all chunk calls.   |
| templateId | String | The contract ID (mirrors back for client convenience)        |

**Error Responses**

| Status | Message            | Cause                                           |
|--------|--------------------|-------------------------------------------------|
| 401    | Unauthorized       | Token missing or expired                        |
| 404    | Contract not found | ID doesn't exist or belongs to another user     |

**curl**

```bash
curl -X POST http://localhost:8080/contracts/CONTRACT_ID/file/initiate \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

### 5.8 Get Presigned Part URL

Generates a presigned MinIO PUT URL for uploading a single chunk directly to MinIO. Call once per chunk, incrementing `partNumber` for each. URLs expire in **15 minutes**.

```
GET /contracts/{id}/file/presign
```

**Headers**

| Header        | Required | Value            |
|---------------|----------|------------------|
| Authorization | Yes      | `Bearer <token>` |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Query Parameters**

| Parameter  | Type    | Required | Description                                             |
|------------|---------|----------|---------------------------------------------------------|
| uploadId   | String  | Yes      | The `uploadId` returned from Initiate                   |
| partNumber | Integer | Yes      | 1-based part index. Must be sequential starting from 1. |

**Response — 200 OK**

```json
{
  "url": "http://localhost:9000/contract-management/contracts/CONTRACT_ID.pdf?uploadId=abc&partNumber=1&X-Amz-...",
  "partNumber": 1
}
```

| Field      | Type    | Description                                                    |
|------------|---------|----------------------------------------------------------------|
| url        | String  | Presigned MinIO PUT URL for this specific part. Valid 15 min.  |
| partNumber | Integer | Echoes back the requested part number                          |

**Error Responses**

| Status | Message            | Cause                                           |
|--------|--------------------|-------------------------------------------------|
| 401    | Unauthorized       | Token missing or expired                        |
| 404    | Contract not found | ID doesn't exist or belongs to another user     |

**curl**

```bash
curl -X GET "http://localhost:8080/contracts/CONTRACT_ID/file/presign?uploadId=UPLOAD_ID&partNumber=1" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

### 5.9 Complete Chunked Upload

Instructs MinIO to assemble all uploaded parts into the final PDF. Must supply the `uploadId` and the `partNumber` + `ETag` for every part uploaded. After assembly, the server reads the first 4 bytes of the assembled file to validate it is a valid PDF. If validation fails, the assembled file is deleted from MinIO and a 400 is returned. On success, the contract's `fileUploaded` is set to `true`.

```
POST /contracts/{id}/file/complete
```

**Headers**

| Header        | Required | Value              |
|---------------|----------|--------------------|
| Authorization | Yes      | `Bearer <token>`   |
| Content-Type  | Yes      | `application/json` |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Request Body**

```json
{
  "uploadId": "abc123xyz...",
  "parts": [
    { "partNumber": 1, "eTag": "82defed77857275fb91a831cbbbfc53f" },
    { "partNumber": 2, "eTag": "a1b2c3d4e5f678901234567890abcdef" },
    { "partNumber": 3, "eTag": "deadbeef12345678deadbeef12345678" }
  ]
}
```

| Field             | Type    | Required | Description                                                                              |
|-------------------|---------|----------|------------------------------------------------------------------------------------------|
| uploadId          | String  | Yes      | The `uploadId` from Initiate                                                             |
| parts             | Array   | Yes      | List of all uploaded parts. Must include every part.                                     |
| parts[].partNumber | Integer | Yes     | 1-based part index, matching the number used when uploading                              |
| parts[].eTag      | String  | Yes      | ETag value returned by MinIO in the response headers of the PUT. **Without quotes.**     |

> **ETag format:** MinIO returns ETags wrapped in double quotes (e.g. `"82defed77857275fb91a831cbbbfc53f"`). Strip the quotes before sending — pass only the hash string itself.

**Response — 200 OK**

Empty body. Contract record is updated: `fileUploaded: true`, `uploadId: null`.

**Error Responses**

| Status | Message            | Cause                                           |
|--------|--------------------|-------------------------------------------------|
| 400    | File is not a valid PDF | Assembled file does not start with `%PDF` — file deleted from MinIO |
| 400    | Bad Request             | Missing parts, wrong ETag format, or MinIO assembly error            |
| 401    | Unauthorized            | Token missing or expired                                             |
| 404    | Contract not found      | ID doesn't exist or belongs to another user                          |

**curl**

```bash
curl -X POST http://localhost:8080/contracts/CONTRACT_ID/file/complete \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "uploadId": "UPLOAD_ID",
    "parts": [
      { "partNumber": 1, "eTag": "82defed77857275fb91a831cbbbfc53f" }
    ]
  }'
```

---

### 5.10 Abort Chunked Upload

Cancels an in-progress multipart upload and deletes all partial chunks from MinIO. The contract metadata record is preserved. Call this on upload failure to avoid orphaned MinIO data.

```
POST /contracts/{id}/file/abort
```

**Headers**

| Header        | Required | Value            |
|---------------|----------|------------------|
| Authorization | Yes      | `Bearer <token>` |

**Path Parameters**

| Parameter | Description                |
|-----------|----------------------------|
| id        | MongoDB ID of the contract |

**Query Parameters**

| Parameter | Type   | Required | Description                           |
|-----------|--------|----------|---------------------------------------|
| uploadId  | String | Yes      | The `uploadId` from Initiate          |

**Response — 200 OK**

Empty body. MinIO session is cancelled, `uploadId` is cleared in MongoDB.

**Error Responses**

| Status | Message            | Cause                                           |
|--------|--------------------|-------------------------------------------------|
| 401    | Unauthorized       | Token missing or expired                        |
| 404    | Contract not found | ID doesn't exist or belongs to another user     |

**curl**

```bash
curl -X POST "http://localhost:8080/contracts/CONTRACT_ID/file/abort?uploadId=UPLOAD_ID" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

### 5.11 Terminate Contract

Permanently terminates a contract that has expired (past its `endDate`). This action is **irreversible** — once terminated, the status cannot be changed back. The acting user is identified from the JWT token; no request body is required.

```
POST /contracts/{id}/terminate
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the contract to terminate |

**Request Body**

None. This endpoint requires no body. The `terminatedBy` value is derived from the JWT token.

**Prerequisites — the contract must be:**
- Owned by the authenticated user
- In a computed `EXPIRED` state — meaning stored status is `SIGNED` AND `endDate` is before today
- Not already in a `TERMINATED` state (if already terminated, the call is idempotent — see below)
- Not blocked by an active renewal (`renewalStatus != "in_progress"`)

**Response — 200 OK (terminated successfully)**

```json
{
  "success": true,
  "alreadyTerminated": false
}
```

**Response — 200 OK (already terminated — idempotent)**

Calling terminate on a contract that is already `TERMINATED` returns success without making any changes to the database.

```json
{
  "success": true,
  "alreadyTerminated": true
}
```

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | `Cannot terminate a contract with status "<status>". Only expired contracts can be terminated.` | Contract is not EXPIRED. The quoted status is the computed value the user sees (e.g. `"ACTIVE"`, `"EXPIRING"`, `"DRAFT"`). |
| 401 | Unauthorized | Token missing or expired |
| 404 | Contract not found | ID does not exist or belongs to another user |
| 409 | Cannot terminate a contract that has an active renewal in progress. Cancel or complete the renewal first. | `renewalStatus == "in_progress"` |

**What happens in the database on success:**

| Field | Value set |
|---|---|
| `status` | `TERMINATED` |
| `terminatedAt` | Server timestamp (`LocalDateTime.now()`) |
| `terminatedBy` | Email from JWT token |
| `renewalStatus` | `null` (cleared) |
| `renewedContractId` | `null` (cleared) |
| `updatedAt` | Server timestamp |

**curl**

```bash
curl -X POST http://localhost:8080/contracts/CONTRACT_ID/terminate \
  -H "Authorization: Bearer YOUR_TOKEN"
```

---

## 6. End-to-End Flows

### 6.1 Metadata Only

Use this when creating a contract that does not require a file upload (e.g. recording contract info for tracking purposes).

```
Step 1   POST /auth/login
         ← { token }

Step 2   POST /contracts
         Body: { title, client, templateId, ... }
         ← { id, status: "DRAFT", fileUploaded: false, ... }

Step 3   PATCH /contracts/{id}           (optional — update fields)
         Body: { xfdfData, fieldValues, parties, ... }
         ← updated contract

Step 4   GET /contracts                  (optional — verify in list)
         ← [ { id, title, status, fileUploaded: false, ... } ]
```

---

### 6.2 Small File — Single-Shot Upload

Use when the PDF file is **under 30 MB**.

```
Step 1   POST /auth/login
         ← { token }

Step 2   POST /contracts
         Body: { title, client, templateId, ... }
         ← { id, fileUploaded: false, ... }

Step 3   PUT /contracts/{id}/file
         Headers: Content-Type: application/pdf
                  Content-Length: <file size in bytes>
         Body: raw PDF bytes
         ← 200 OK  (fileUploaded is now true)

Step 4   GET /contracts/{id}/file/view-url
         ← { url: "http://minio:9000/...?X-Amz-..." }
         → Pass url directly to Apryse WebViewer
```

---

### 6.3 Large File — Chunked Upload

Use when the PDF file is **30 MB or larger**. The file is split into chunks on the client and each chunk is uploaded directly to MinIO via a presigned URL — the app server is bypassed for the actual data transfer.

```
Step 1   POST /auth/login
         ← { token }

Step 2   POST /contracts
         Body: { title, client, templateId, ... }
         ← { id, fileUploaded: false, ... }

Step 3   POST /contracts/{id}/file/initiate
         ← { uploadId, templateId }

Step 4   Split file into chunks on client
         — Minimum chunk size: 5 MB (except the last chunk)
         — Recommended chunk size: 10 MB

Step 5   For each chunk (repeat for partNumber = 1, 2, 3, ...):
         a. GET /contracts/{id}/file/presign?uploadId=...&partNumber=N
            ← { url, partNumber }
         b. PUT {url}   ← direct to MinIO, no Authorization header
            Body: raw chunk bytes
            → Save ETag from response headers (strip surrounding quotes)

Step 6   POST /contracts/{id}/file/complete
         Body: { uploadId, parts: [{ partNumber, eTag }, ...] }
         ← 200 OK  (fileUploaded is now true)

Step 7   GET /contracts/{id}/file/view-url
         ← { url: "http://minio:9000/...?X-Amz-..." }

─── On any error in Step 5 or 6 ───────────────────────────────────

Step X   POST /contracts/{id}/file/abort?uploadId=...
         ← 200 OK  (MinIO cleaned up, contract metadata preserved)
         → User can retry from Step 3
```

**Chunking decision tree for the frontend:**

```
fileSize < 30 MB  →  PUT /contracts/{id}/file           (single-shot)
fileSize ≥ 30 MB  →  initiate → presign → PUT to MinIO → complete
```

---

### 6.4 Terminate a Contract

Use when a contract has passed its `endDate` and must be formally closed in the system.

```
Step 1   POST /auth/login
         ← { token }

Step 2   GET /contracts
         ← [ { id, status: "EXPIRED", endDate: "...", ... }, ... ]
         → Identify the contract with status "EXPIRED"

Step 3   POST /contracts/{id}/terminate
         Headers: Authorization: Bearer <token>
         (no body)
         ←  { "success": true, "alreadyTerminated": false }

Step 4   GET /contracts/{id}              (optional — verify termination)
         ← { status: "TERMINATED", terminatedAt: "...", terminatedBy: "..." }
```

**Notes:**

- The `status` in Step 2 is a **computed** value. The database stores `SIGNED`; the API returns `EXPIRED` based on the current date and `endDate`.
- If Step 3 is called again (retry), it returns `{ "success": true, "alreadyTerminated": true }` — no error.
- If `renewalStatus` is `"in_progress"` when Step 3 is called, a 409 is returned. The renewal must be cancelled or completed first.
- After termination, `GET /contracts?status=TERMINATED` will match this contract (since `TERMINATED` is stored directly in the database).

---

## 7. Business Rules

| Rule | Detail |
|------|--------|
| Title uniqueness | Contract titles are unique per user (case-sensitive). Two different users may have the same title. |
| Ownership | All operations are scoped to the authenticated user. Accessing another user's contract returns 404, not 403. |
| Status on create | Always `DRAFT`. Cannot be set in the request body. |
| startDate default | Defaults to today if not provided. |
| endDate default | Defaults to `startDate + 1 year` if not provided. |
| Date validation | `endDate` must be ≥ `startDate`. Rejected with 400 otherwise. |
| description default | Auto-generated as `"Contract based on {templateName}"` if not provided. |
| category | Free text. Max 50 characters. |
| **Stored status** | The database always stores `SIGNED` for finalized contracts. `ACTIVE`, `EXPIRING`, and `EXPIRED` are never written to the database. |
| **Computed status** | For any contract whose stored status is `SIGNED`, the `status` field in every API response is computed at request time from `startDate`, `endDate`, and the current date. |
| **EXPIRED rule** | If `endDate` is before today, status is `EXPIRED`. |
| **EXPIRING rule** | If `endDate` is within 30 days from today (inclusive), status is `EXPIRING`. The 30-day threshold is inclusive — exactly 30 days remaining is `EXPIRING`. |
| **ACTIVE rule** | If `startDate ≤ today` and `endDate` is more than 30 days away, status is `ACTIVE`. |
| **SIGNED (pending start)** | If `startDate` is in the future (and `endDate` > 30 days away), status stays `SIGNED`. The contract is finalized but not yet in force. |
| **No endDate** | If `endDate` is null, no computation runs. The status stays `SIGNED`. |
| **Status filter limitation** | `GET /contracts?status=ACTIVE/EXPIRING/EXPIRED` returns zero results — these values are never stored. Use `?status=SIGNED` to retrieve all finalized contracts, then filter client-side. |
| **Termination eligibility** | Only contracts with a computed status of `EXPIRED` (stored `SIGNED` + `endDate` before today) can be terminated. All other statuses — including `ACTIVE`, `EXPIRING`, `DRAFT`, workflow statuses — return 400. |
| **Termination is irreversible** | Once a contract is `TERMINATED`, no endpoint changes the status back. `TERMINATED` is a terminal state. |
| **Termination is idempotent** | Calling `POST /{id}/terminate` on an already-terminated contract returns `{ "success": true, "alreadyTerminated": true }` with HTTP 200 — no error, no DB write. |
| **terminatedBy from JWT** | The `terminatedBy` field stored on termination is taken from the JWT token, not the request body. No body is sent by the caller. |
| **terminatedAt from server** | The termination timestamp is always set server-side (`LocalDateTime.now()`). The client never provides it. |
| **Renewal blocks termination** | If `renewalStatus == "in_progress"`, termination returns 409. Cancel or complete the renewal first. |
| **Renewal linkage cleared** | On successful termination, `renewalStatus` and `renewedContractId` are set to `null`. |
| **TERMINATED passes through status computation** | `TERMINATED` is never recomputed from dates — it is returned exactly as stored. |
| Single-shot limit | Files ≥ 30 MB must use chunked upload. Single-shot returns 400 if `Content-Length ≥ 30MB`. |
| Content-Length required | The `PUT /{id}/file` endpoint requires `Content-Length` in the request headers. |
| PDF validation | Both upload paths validate the `%PDF` magic bytes. Single-shot validates before storing. Chunked complete validates after assembly — if invalid, the assembled file is deleted from MinIO and 400 is returned. |
| MinIO chunk minimum | Each part (except the last) must be at least **5 MB** — MinIO requirement. |
| Presigned URL TTL | All presigned URLs (upload parts and view) expire in **15 minutes**. |
| fileUploaded flag | Set to `true` only after a successful single-shot upload or chunked complete. |
| View URL guard | `GET /{id}/file/view-url` returns 400 if `fileUploaded` is `false`. |
| PATCH semantics | Only fields present in the request body are updated. `null` values are ignored. |
| folderId optional | Contracts can exist without a folder (root level). |

---

## 8. Automatic Cleanup

A background scheduler runs every day at **02:00 AM** to clean up abandoned chunked upload sessions.

| Condition | Action |
|-----------|--------|
| Contract has `fileUploaded: false` AND `uploadInitiatedAt` is older than **24 hours** | MinIO multipart session is aborted (chunks deleted). The **contract metadata record is kept** — the user can retry the upload. |

> This means if a chunked upload is interrupted, the user has up to 24 hours to retry before the session is automatically aborted. After abort, they must call `POST /{id}/file/initiate` again to start a new session.

---

## 9. Endpoint Summary

| Method | Path                              | Auth Role     | Description                                         |
|--------|-----------------------------------|---------------|-----------------------------------------------------|
| POST   | `/contracts`                      | Authenticated | Create contract metadata                            |
| GET    | `/contracts`                      | Authenticated | List contracts (filter by folderId and/or status)   |
| GET    | `/contracts/{id}`                 | Authenticated | Get full contract detail                            |
| PATCH  | `/contracts/{id}`                 | Authenticated | Partial update — metadata or xfdfData               |
| PUT    | `/contracts/{id}/file`            | Authenticated | Single-shot file upload (< 30 MB only)              |
| GET    | `/contracts/{id}/file/view-url`   | Authenticated | Get 15-min presigned MinIO URL for PDF viewing      |
| POST   | `/contracts/{id}/file/initiate`   | Authenticated | Start chunked upload session                        |
| GET    | `/contracts/{id}/file/presign`    | Authenticated | Get presigned PUT URL for a single chunk            |
| POST   | `/contracts/{id}/file/complete`   | Authenticated | Assemble all chunks into final PDF                  |
| POST   | `/contracts/{id}/file/abort`      | Authenticated | Cancel chunked upload and clean up MinIO            |
| POST   | `/contracts/{id}/terminate`       | Owner only    | Permanently terminate an expired contract           |
