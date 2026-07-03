# Unified Workflow — Technical Documentation

**Version:** 1.0.0
**Last Updated:** 2026-06-30
**Base URL:** `http://localhost:8080`
**Status:** Production Ready

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architecture & Design Principles](#2-architecture--design-principles)
3. [Authentication & Security](#3-authentication--security)
4. [Core Concepts & Terminology](#4-core-concepts--terminology)
5. [Contract Status State Machine](#5-contract-status-state-machine)
6. [Data Models](#6-data-models)
7. [Business Rules & Validations](#7-business-rules--validations)
8. [API Reference](#8-api-reference)
9. [Multipart PDF Upload Guide](#9-multipart-pdf-upload-guide)
10. [Org-Field Gate](#10-org-field-gate)
11. [Resubmission After Rejection](#11-resubmission-after-rejection)
12. [Error Reference](#12-error-reference)
13. [End-to-End Integration Walkthrough](#13-end-to-end-integration-walkthrough)
14. [Frontend Integration Guide](#14-frontend-integration-guide)
15. [Test Coverage](#15-test-coverage)

---

## 1. Introduction

### 1.1 What Is the Unified Workflow?

The Unified Workflow is a **new, parallel service** that merges the internal review, internal approval (with PDF signing), and optional external client signing into a **single sequential chain**. It replaces the need to run separate review → approval → signature flows in disconnected steps.

The key distinction from the legacy `ContractWorkflowService`:
- **Legacy flow**: Review, Approval, and Signature were three separate, loosely-coupled processes.
- **Unified flow**: All internal participants (reviewers and approvers) are assigned upfront with a global sequential order. After all internal work is done the contract automatically advances to `READY_FOR_SIGNATURE` and the owner triggers external signing in one action.

### 1.2 What the System Does

- Assigns a sequential list of **reviewers** and **approvers** to a contract in a single submit call
- Unlocks each participant only after all participants at the previous order number have completed
- Reviewers **read and edit** org-party form fields, then mark complete (no PDF upload)
- Approvers **upload a digitally-signed PDF** to mark their approval — each approver's signature is cumulative (one `_signed.pdf` file, overwritten after each approval)
- On rejection, the contract returns to `REJECTED`; the owner can resubmit with corrected participants
- After all internal participants are done, the contract moves to `READY_FOR_SIGNATURE`
- The owner may then send the contract for **external signature** (optional) after validating that all org-owned form fields are filled (org-gate)
- Provides an **inbox endpoint** so reviewers and approvers see only contracts where it is currently their turn

### 1.3 What This Service Does NOT Replace

The legacy `ContractWorkflowService` (existing `/contracts/{id}/review`, `/contracts/{id}/approve`, and `/contracts/{id}/signature` endpoints) is **completely untouched**. Both flows can coexist. A contract can only be in one flow at a time.

---

## 2. Architecture & Design Principles

### 2.1 Additive-Only Changes

The unified workflow was implemented with a strict **additive-only policy** on shared models:

| File | Type of Change |
|---|---|
| `Contract.java` | 3 new fields added — zero fields removed or renamed |
| `ContractStatus.java` | `REJECTED` value added |
| `Party.java` | `type` field added (null = INTERNAL, backward compatible) |
| `Template.java` (inner Party) | `type` field added |
| `ContractRepository.java` | 1 new query method added |
| `ContractRenewalService.java` | 1 line added in `convertParties()` |

All net-new files:
- `model/PartyType.java`
- `model/ParticipantRole.java`
- `model/WorkflowParticipant.java`
- `dto/ParticipantAssignment.java`
- `dto/FlowSubmitRequest.java`
- `dto/FlowFieldEditRequest.java`
- `dto/FlowCompleteRequest.java`
- `dto/FlowRejectRequest.java`
- `dto/FlowStatusResponse.java`
- `service/UnifiedWorkflowService.java`
- `controller/UnifiedWorkflowController.java`

### 2.2 Sequential Participant Engine

The core advance engine (`advanceWorkflow`) works as follows:

```
When a participant marks complete:
  1. Check if ALL participants at currentParticipantOrder are "completed"
  2. If not → do nothing (wait for others at the same order)
  3. If yes → find the lowest order number greater than currentOrder
     a. No next order → set status = READY_FOR_SIGNATURE, currentParticipantOrder = null
     b. Found next order → unlock all participants at that order,
                            update currentParticipantOrder,
                            set status = IN_REVIEW or IN_APPROVAL based on next participants' role
```

### 2.3 PDF Versioning Strategy

Approvers sign the contract PDF progressively. A single MinIO object key is used and **overwritten** after each approver completes:

```
contracts/{contractId}_signed.pdf
```

This is safe because approvers are unlocked **sequentially** — Approver 2 is never unlocked until Approver 1 has fully completed and their PDF is committed. There are no concurrent writes to the same key.

The `getParticipantFileUrl` endpoint always returns the **latest available version**: if `_signed.pdf` exists in MinIO, it is returned; otherwise the original `contracts/{contractId}.pdf` is returned. This ensures each participant always reads the most up-to-date state.

### 2.4 Optimistic Locking

After each approver completes their PDF upload, `contract.version` is incremented. This version is also synced to any pending `SignatureRequest` documents in MongoDB so that the external signing flow (if triggered later) operates on the correct version.

---

## 3. Authentication & Security

All endpoints require a valid **JWT Bearer token** in the `Authorization` header.

```
Authorization: Bearer <token>
```

The JWT subject is the user's email address. Spring Security extracts the email from the security context (`SecurityContextHolder`). The service layer performs all authorization checks against this email.

### 3.1 Access Rules Summary

| Action | Who Can Call |
|---|---|
| `submit` | Contract owner (createdBy) only |
| `saveFieldEdits` | Active participant (unlocked or in_progress) only |
| `markComplete` | Active participant (unlocked or in_progress) only |
| `reject` | Active participant (unlocked or in_progress) only |
| `initiateUpload` | Active APPROVER only, while status is IN_APPROVAL |
| `getPresignedPartUrl` | Active APPROVER only, while status is IN_APPROVAL |
| `abortUpload` | Active APPROVER only, while status is IN_APPROVAL |
| `getParticipantFileUrl` | Contract owner OR any participant (any status) |
| `sendForSignature` | Contract owner only, while status is READY_FOR_SIGNATURE |
| `getFlowStatus` | Contract owner OR any participant |
| `getFlowInbox` | Any authenticated user (filtered to caller's active tasks) |

### 3.2 Ownership Masking

Non-owner access to `submit` and `sendForSignature` returns **404 Not Found** instead of 403 Forbidden. This prevents contract ID enumeration — an attacker cannot tell whether a contract exists if they are not the owner.

---

## 4. Core Concepts & Terminology

### 4.1 Participant

A participant is any internal user (reviewer or approver) assigned to a contract's unified workflow. Each participant has:
- A **role** (`REVIEWER` or `APPROVER`)
- A **global order number** (shared across roles — order 1 might be a reviewer, order 2 might be an approver)
- A **status** that progresses through: `pending` → `unlocked` → `in_progress` → `completed` / `rejected`

### 4.2 Participant Status Lifecycle

```
pending      — assigned but not yet the active order; cannot take any action
unlocked     — it is this participant's turn; can edit fields and complete/reject
in_progress  — has saved at least one field edit; can still complete/reject
completed    — marked the workflow step complete; cannot undo
rejected     — rejected the contract; workflow stops, contract goes to REJECTED
```

### 4.3 Participant Roles

| Role | Can Edit Fields | Must Upload PDF | Can Reject |
|---|---|---|---|
| `REVIEWER` | Yes | No — uploading a PDF is an error | Yes |
| `APPROVER` | Yes | Yes — PDF is required to mark complete | Yes |

### 4.4 Global Ordering

Order numbers are **global** — they do not reset per role. All reviewer orders **must** be lower than all approver orders. You cannot have:
- A reviewer at order 3 and an approver at order 2 (reviewers must all come first)

Valid example:
```
Order 1: REVIEWER  (reviewer@company.com)
Order 2: REVIEWER  (reviewer2@company.com)
Order 3: APPROVER  (cfo@company.com)
Order 4: APPROVER  (ceo@company.com)
```

### 4.5 Party Types

| Type | Meaning |
|---|---|
| `INTERNAL` | Org-owned party — fields assigned to this party must be filled by internal users before external signing |
| `EXTERNAL` | External client party — fields assigned to this party are filled by the external signer |

A `null` type is treated as `INTERNAL` for backward compatibility.

### 4.6 externalSigningIncluded

A boolean flag set at submit time. When `true`:
- The owner must fill all `INTERNAL` party form fields before `sendForSignature` is allowed (org-gate)
- External signing is expected after the internal flow completes

When `false`:
- Org-gate is not enforced
- The contract still goes to `READY_FOR_SIGNATURE` when internal participants finish, but the owner controls what happens next

---

## 5. Contract Status State Machine

### 5.1 States Used by Unified Workflow

| Status | Meaning |
|---|---|
| `DRAFT` | Initial state — eligible for unified workflow submission |
| `REJECTED` | A reviewer or approver rejected the contract — eligible for resubmission |
| `IN_REVIEW` | Current active participants are REVIEWERs |
| `IN_APPROVAL` | Current active participants are APPROVERs |
| `READY_FOR_SIGNATURE` | All internal participants completed — owner may trigger external signing |

### 5.2 State Transition Diagram

```
                      ┌─────────────────────────────────────────────────────┐
                      │                  OWNER: submit()                    │
                      ▼                                                     │
 ┌──────────┐   first role = REVIEWER   ┌───────────┐                      │
 │  DRAFT   │ ─────────────────────────▶│ IN_REVIEW │                      │
 └──────────┘                           └─────┬─────┘                      │
      │                                       │                             │
      │  first role = APPROVER                │ all reviewers complete      │
      │                                       ▼                             │
      │                               ┌─────────────┐                      │
      └──────────────────────────────▶│ IN_APPROVAL │                      │
                                      └──────┬──────┘                      │
                                             │                             │
                          ┌──────────────────┤                             │
                          │ any participant  │ all approvers complete      │
                          │ rejects          ▼                             │
                          │        ┌────────────────────┐                  │
                          │        │ READY_FOR_SIGNATURE│                  │
                          ▼        └────────────────────┘                  │
                    ┌──────────┐           │                               │
                    │ REJECTED │           │ owner: sendForSignature()     │
                    └──────────┘           ▼                               │
                          │         (External Signing Flow)                │
                          │                                                 │
                          └─────────────────────────────────────────────────┘
                                 OWNER: submit() again (resubmit)
```

### 5.3 Resubmission After Rejection

The resubmission path depends on **who rejected**:

```
Reviewer rejected  →  Full reset: all participants replaced
Approver rejected  →  Partial reset: completed reviewers kept, only approvers replaced
```

In the approver-rejection case, since all reviewers are already `completed`, the first new approver is **immediately unlocked** upon resubmit — no re-review is needed.

---

## 6. Data Models

### 6.1 WorkflowParticipant

Stored as an embedded array `participants[]` on the `Contract` document.

```json
{
  "email":       "reviewer@example.com",
  "name":        "Alice Smith",
  "role":        "REVIEWER",
  "order":       1,
  "status":      "completed",
  "sentBy":      "owner@example.com",
  "sentAt":      "2026-06-30T10:00:00",
  "unlockedAt":  "2026-06-30T10:00:00",
  "completedAt": "2026-06-30T11:30:00",
  "rejectedAt":  null,
  "comments":    "Looks good, approved"
}
```

| Field | Type | Description |
|---|---|---|
| `email` | String | Participant's email (lowercased on save) |
| `name` | String | Display name |
| `role` | Enum | `REVIEWER` or `APPROVER` |
| `order` | int | Global sequential order number (1-based) |
| `status` | String | `pending` / `unlocked` / `in_progress` / `completed` / `rejected` |
| `sentBy` | String | Email of the owner who submitted the flow |
| `sentAt` | LocalDateTime | When the flow was submitted |
| `unlockedAt` | LocalDateTime | When this participant's turn started |
| `completedAt` | LocalDateTime | When markComplete was called |
| `rejectedAt` | LocalDateTime | When reject was called |
| `comments` | String | Set on markComplete or reject |

### 6.2 New Contract Fields (Additive)

Three new fields added to the `Contract` document for the unified flow:

| Field | Type | Description |
|---|---|---|
| `participants` | `List<WorkflowParticipant>` | All assigned internal participants |
| `currentParticipantOrder` | `Integer` | Order number currently active (null when READY_FOR_SIGNATURE) |
| `externalSigningIncluded` | `boolean` | Whether external signing is expected after internal flow |

### 6.3 ParticipantRole Enum

```java
public enum ParticipantRole {
    REVIEWER,
    APPROVER
}
```

### 6.4 PartyType Enum

```java
public enum PartyType {
    INTERNAL,   // org-owned party — fields must be filled by internal users
    EXTERNAL    // external client party — fields filled by the external signer
}
```

### 6.5 Party Model (updated)

```json
{
  "id":    "party-uuid-1",
  "label": "Our Company",
  "color": "#0066cc",
  "order": 1,
  "type":  "INTERNAL"
}
```

`type` is `null` on all legacy records and is treated as `INTERNAL`.

### 6.6 ModificationRequest (used for rejection tracking)

```json
{
  "requestedBy": "reviewer@example.com",
  "role":        "reviewer",
  "message":     "Clause 5 needs revision",
  "requestedAt": "2026-06-30T14:00:00"
}
```

The `role` field value is `"reviewer"` or `"approver"` (lowercase string). This is used by `handleResubmit()` to determine the reset scope.

---

## 7. Business Rules & Validations

### 7.1 Submission Rules

| Rule | Error |
|---|---|
| Caller must be the contract owner | 404 Not Found |
| Contract must be in `DRAFT` or `REJECTED` status | 400 Bad Request |
| Participant list must not be empty | 400 Bad Request |
| At least one `APPROVER` is required | 400 Bad Request |
| No duplicate order numbers | 400 Bad Request |
| No duplicate participant emails | 400 Bad Request |
| Owner cannot assign themselves as a participant | 400 Bad Request |
| All `REVIEWER` orders must be < all `APPROVER` orders | 400 Bad Request |

### 7.2 Field Edit Rules

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be an assigned participant | 400 Bad Request |
| Participant status must be `unlocked` or `in_progress` | 400 Bad Request |

### 7.3 Mark Complete Rules — Reviewer

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be active participant | 400 Bad Request |
| `uploadId` must be null/blank | 400 Bad Request |
| `parts` must be null/empty | 400 Bad Request |

### 7.4 Mark Complete Rules — Approver

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be active participant with `APPROVER` role | 400 Bad Request |
| `uploadId` is required (non-blank) | 400 Bad Request |
| `parts` list is required (non-empty) | 400 Bad Request |

### 7.5 Rejection Rules

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be active participant | 400 Bad Request |
| `message` is required (non-blank) | 400 Bad Request |

### 7.6 Upload Rules (Approver Only)

| Rule | Error |
|---|---|
| Contract must be in `IN_APPROVAL` | 400 Bad Request |
| Caller must be active APPROVER | 400 Bad Request |
| `partNumber` must be between 1 and 10000 (for presign) | 400 Bad Request |
| `uploadId` must be non-blank (for abort) | 400 Bad Request |

### 7.7 Send for Signature Rules

| Rule | Error |
|---|---|
| Caller must be contract owner | 404 Not Found |
| Contract must be in `READY_FOR_SIGNATURE` | 400 Bad Request |
| Contract file (`fileUploaded`) must be true | 400 Bad Request |
| All `INTERNAL` party form fields must be non-empty (org-gate) | 400 Bad Request |

### 7.8 Resubmission After Approver Rejection

When the last rejection was by an approver, the new participant list passed to `submit()` **must contain only APPROVERs**. Passing a `REVIEWER` in the list throws `400 Bad Request`. The completed reviewers from the previous run are automatically preserved.

---

## 8. API Reference

**Base path:** `/contracts`
**Auth:** All endpoints require `Authorization: Bearer <jwt-token>`

---

### 8.1 Submit Unified Flow

**`POST /contracts/{id}/flow/submit`**

Submits a `DRAFT` or `REJECTED` contract into the unified workflow. Assigns all participants with their roles and global orders. The contract is immediately moved to `IN_REVIEW` or `IN_APPROVAL` depending on the lowest order participant's role.

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| `id` | String | Contract ID |

**Request Body**

```json
{
  "participants": [
    {
      "email": "reviewer@example.com",
      "name":  "Alice Smith",
      "role":  "REVIEWER",
      "order": 1
    },
    {
      "email": "approver@example.com",
      "name":  "Bob Jones",
      "role":  "APPROVER",
      "order": 2
    }
  ],
  "externalSigningIncluded": true
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `participants` | Array | Yes | At least one participant; must include at least one APPROVER |
| `participants[].email` | String | Yes | Valid email; cannot be the owner's email |
| `participants[].name` | String | No | Display name |
| `participants[].role` | String | Yes | `REVIEWER` or `APPROVER` |
| `participants[].order` | int | Yes | Positive integer; unique across all participants; all REVIEWER orders < all APPROVER orders |
| `externalSigningIncluded` | boolean | No | Default `false`. When `true`, org-gate is enforced before external signing |

**Success Response — 200 OK**

Returns the full `ContractResponse` with `status` set to `IN_REVIEW` or `IN_APPROVAL` and `participants[]` populated.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found, or caller is not the owner |
| 400 | Contract not in DRAFT/REJECTED; validation failures listed in §7.1 |

---

### 8.2 Save Field Edits

**`POST /contracts/{id}/flow/fields`**

Allows the currently active participant to save form field values. On the first call, the participant's status transitions from `unlocked` → `in_progress`. Subsequent calls keep the status as `in_progress` and update values.

Each updated field gets a `filledBy` attribute set to the caller's email for attribution.

**Request Body**

```json
{
  "formFields": [
    { "fieldName": "companyName", "value": "Acme Corp" },
    { "fieldName": "contractDate", "value": "2026-07-01" }
  ],
  "fieldValues": {
    "companyName": "Acme Corp",
    "contractDate": "2026-07-01"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `formFields` | Array | No | Full field objects — merged by `fieldName`/`name` key |
| `fieldValues` | Map | No | Simple key-value pairs merged into `contract.fieldValues` |

Either or both can be sent. Sending both updates both storage locations.

**Success Response — 200 OK**

Returns the updated `ContractResponse`.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Contract not IN_REVIEW/IN_APPROVAL; caller not assigned; caller's turn not yet active |

---

### 8.3 Mark Complete

**`POST /contracts/{id}/flow/complete`**

Marks the caller's participation as complete.

- **Reviewer**: Only comments allowed. Contract advances to next order (or READY_FOR_SIGNATURE).
- **Approver**: Must provide `uploadId` and `parts` from the completed MinIO multipart upload. The PDF is finalized in MinIO, `contract.version` is incremented, and the contract advances.

**Request Body — Reviewer**

```json
{
  "comments": "Reviewed and approved — clause 5 updated"
}
```

**Request Body — Approver**

```json
{
  "uploadId": "minio-upload-id-from-initiate",
  "parts": [
    { "partNumber": 1, "etag": "d8e8fca2dc0f896fd7cb4cb0031ba249" },
    { "partNumber": 2, "etag": "a87ff679a2f3e71d9181a67b7542122c" }
  ],
  "comments": "Signed and approved",
  "formFields": [
    { "fieldName": "ceoSignature", "value": "B. Jones" }
  ],
  "fieldValues": {
    "ceoSignature": "B. Jones"
  }
}
```

| Field | Type | Required for | Description |
|---|---|---|---|
| `comments` | String | Neither | Optional remarks stored on participant |
| `uploadId` | String | Approver only | The `uploadId` returned by `initiateUpload` |
| `parts` | Array | Approver only | All part numbers and their ETags from the PUT responses |
| `parts[].partNumber` | int | Approver only | Part number (1-based) |
| `parts[].etag` | String | Approver only | ETag from the presigned PUT response header |
| `formFields` | Array | Neither | Optional final field state to merge before completing |
| `fieldValues` | Map | Neither | Optional simple field values to merge |

**Success Response — 200 OK**

Returns the updated `ContractResponse`. Inspect `status` to know what happened:
- `IN_REVIEW` or `IN_APPROVAL` — advanced to next order
- `READY_FOR_SIGNATURE` — all internal participants completed

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Wrong status; not an active participant; reviewer provided PDF; approver missing uploadId/parts |

---

### 8.4 Reject

**`POST /contracts/{id}/flow/reject`**

Rejects the contract. The participant's status is set to `rejected`, a `ModificationRequest` is appended, and the contract status moves to `REJECTED`. The owner must resubmit.

**Request Body**

```json
{
  "message": "Clause 5 requires legal review before approval"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `message` | String | Yes | Non-blank rejection reason |

**Success Response — 200 OK**

Returns the updated `ContractResponse` with `status: "REJECTED"`.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Contract not IN_REVIEW/IN_APPROVAL; caller not active participant; message is blank |

---

### 8.5 Initiate Upload

**`POST /contracts/{id}/flow/upload/initiate`**

**Approver only.** Starts a MinIO multipart upload for the signed PDF at key `contracts/{id}_signed.pdf`. Returns the `uploadId` needed for subsequent presign and complete calls.

**Request Body**

None.

**Success Response — 200 OK**

```json
{
  "uploadId": "2~SFgzXV4GnNAuGtWq8Jq..."
}
```

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Contract not in IN_APPROVAL; caller not an active APPROVER |

---

### 8.6 Get Presigned Part URL

**`GET /contracts/{id}/flow/upload/presign?uploadId={uploadId}&partNumber={n}`**

**Approver only.** Returns a presigned PUT URL for uploading a single chunk of the signed PDF directly to MinIO. The URL expires in 15 minutes.

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uploadId` | String | Yes | The uploadId from `initiateUpload` |
| `partNumber` | int | Yes | Part number 1–10000 |

**Success Response — 200 OK**

```json
{
  "url":        "https://minio.example.com/presigned-put-url...",
  "partNumber": 1
}
```

The frontend PUTs the chunk directly to `url`. Save the `ETag` header from that PUT response — it is required in `markComplete`.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Contract not in IN_APPROVAL; not active APPROVER; partNumber out of range |

---

### 8.7 Abort Upload

**`POST /contracts/{id}/flow/upload/abort?uploadId={uploadId}`**

**Approver only.** Cancels an in-progress multipart upload. Call this if the user cancels the upload or an error occurs mid-upload to free MinIO resources.

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uploadId` | String | Yes | The uploadId to cancel |

**Success Response — 200 OK**

No body.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Contract not in IN_APPROVAL; not active APPROVER; uploadId is blank/null |

---

### 8.8 Get Participant File URL

**`GET /contracts/{id}/flow/file-url`**

Returns a 15-minute presigned GET URL for the current PDF. The response logic:
- If `contracts/{id}_signed.pdf` exists in MinIO → return the signed PDF (latest approver's version)
- Otherwise → return the original `contracts/{id}.pdf`

Accessible by the contract owner or any participant (regardless of their status).

**Success Response — 200 OK**

```json
{
  "url": "https://minio.example.com/presigned-get-url..."
}
```

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found, or caller is neither owner nor any participant |

---

### 8.9 Send for Signature

**`POST /contracts/{id}/flow/send-for-signature`**

**Owner only.** Sends the contract for external signature. Before delegating to the signature service, the **org-gate** is enforced: all form fields assigned to `INTERNAL` parties must have a non-empty value.

**Request Body**

```json
{
  "assignments": [
    {
      "partyId":    "party-external-uuid",
      "partyLabel": "Client",
      "type":       "external",
      "email":      "client@example.com",
      "name":       "Client Name",
      "order":      1
    }
  ],
  "senderName": "Acme Corp Legal Team"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `assignments` | Array | Yes | At least one signer |
| `assignments[].partyId` | String | Yes | Must match a party ID in the contract |
| `assignments[].partyLabel` | String | Yes | Display label for the party |
| `assignments[].type` | String | Yes | `"external"` or `"internal"` |
| `assignments[].email` | String | No | Required for external signers |
| `assignments[].name` | String | No | Display name |
| `assignments[].order` | int | Yes | Signing order (1-based) |
| `senderName` | String | No | Displayed as sender name in emails |

**Success Response — 200 OK**

Returns the updated `ContractResponse` (delegated from signature service).

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found, or caller not owner |
| 400 | Status not READY_FOR_SIGNATURE; file not uploaded; INTERNAL party field(s) empty (org-gate) |

---

### 8.10 Get Flow Status

**`GET /contracts/{id}/flow/status`**

Returns the full status of the unified workflow for a contract. Includes all participant statuses, current active order, and org-gate readiness.

**Success Response — 200 OK**

```json
{
  "contractId":             "abc123",
  "status":                 "IN_APPROVAL",
  "currentParticipantOrder": 2,
  "participants": [
    {
      "email":       "reviewer@example.com",
      "name":        "Alice Smith",
      "role":        "REVIEWER",
      "order":       1,
      "status":      "completed",
      "completedAt": "2026-06-30T11:30:00",
      "comments":    "Approved"
    },
    {
      "email":      "approver@example.com",
      "name":       "Bob Jones",
      "role":       "APPROVER",
      "order":      2,
      "status":     "unlocked",
      "unlockedAt": "2026-06-30T11:30:00"
    }
  ],
  "externalSigningIncluded": true,
  "orgFieldsComplete":       false,
  "unfilledOrgFields":       ["CEO Name", "Contract Date"]
}
```

| Field | Type | Description |
|---|---|---|
| `contractId` | String | The contract ID |
| `status` | String | Current contract status |
| `currentParticipantOrder` | Integer | Active order number; null if READY_FOR_SIGNATURE |
| `participants` | Array | All participants with their current statuses |
| `externalSigningIncluded` | boolean | Whether external signing is expected |
| `orgFieldsComplete` | boolean | True if all INTERNAL party fields are filled |
| `unfilledOrgFields` | Array | Labels of INTERNAL party fields that are still empty |

**Access:** Owner or any participant. Outsiders receive 404.

---

### 8.11 Flow Inbox

**`GET /contracts/flow/inbox`**

Returns a list of contracts where the authenticated caller has an **active task** — i.e., they are an assigned participant with status `unlocked` or `in_progress`. Contracts where the caller's status is `pending`, `completed`, or `rejected` are excluded.

**Success Response — 200 OK**

```json
[
  {
    "id":     "abc123",
    "title":  "Service Agreement v3",
    "status": "IN_REVIEW",
    ...
  }
]
```

Returns an array of `ContractListResponse` objects. Empty array if the caller has no active tasks.

---

## 9. Multipart PDF Upload Guide

Approvers upload their signed PDF using a three-step MinIO multipart flow. The upload goes **browser → MinIO directly** — the Spring Boot server is not in the upload path (only coordination calls pass through it).

### Step 1 — Initiate

```
POST /contracts/{id}/flow/upload/initiate
Authorization: Bearer <token>
```

Response:
```json
{ "uploadId": "2~SFgzXV4GnNAuGtWq8Jq..." }
```

Store the `uploadId`.

### Step 2 — Upload Parts

For each chunk of the PDF (typically one part for files < 100 MB):

```
GET /contracts/{id}/flow/upload/presign?uploadId={uploadId}&partNumber=1
Authorization: Bearer <token>
```

Response:
```json
{ "url": "https://minio.../presigned-url", "partNumber": 1 }
```

Then PUT the chunk directly to MinIO (no auth header needed — the URL is pre-signed):
```
PUT {presigned-url}
Content-Type: application/pdf
Body: <binary PDF chunk>
```

Capture the `ETag` response header. You need it in Step 3.

### Step 3 — Complete (Mark Approve)

```
POST /contracts/{id}/flow/complete
Authorization: Bearer <token>
Content-Type: application/json

{
  "uploadId": "2~SFgzXV4GnNAuGtWq8Jq...",
  "parts": [
    { "partNumber": 1, "etag": "d8e8fca2dc0f896fd7cb4cb0031ba249" }
  ],
  "comments": "Signed and approved"
}
```

### Aborting on Error

If the upload fails or the user cancels:

```
POST /contracts/{id}/flow/upload/abort?uploadId={uploadId}
Authorization: Bearer <token>
```

### MinIO Object Key

The signed PDF is always stored at:
```
contracts/{contractId}_signed.pdf
```

This key is overwritten after each approver completes. The original upload at `contracts/{contractId}.pdf` is never modified.

---

## 10. Org-Field Gate

The org-gate prevents external signing from starting while internal org-party fields are still empty. It is enforced automatically in `sendForSignature()`.

### How It Works

1. Collect all `Party` objects on the contract where `type == INTERNAL` (or `type == null`)
2. Collect all `formFields` where `assignedParty` matches one of those internal party IDs
3. If any such field has an empty or blank `value`, throw `400 Bad Request` with the list of unfilled field labels

### When the Gate Does NOT Fire

| Scenario | Result |
|---|---|
| No `formFields` on contract | Passes — nothing to check |
| All parties are `EXTERNAL` | Passes — no internal party IDs |
| No parties defined | Passes — internal party ID set is empty |
| `externalSigningIncluded = false` | Gate still runs but passes if all internal fields are filled — it does not skip based on this flag |

### Checking Gate State Before Sending

Call `GET /contracts/{id}/flow/status` — the response includes `orgFieldsComplete` and `unfilledOrgFields`. Use these to show the user which fields still need to be filled before sending.

---

## 11. Resubmission After Rejection

When a contract is in `REJECTED` status, the owner calls `POST /contracts/{id}/flow/submit` again with a new participant list. The behavior depends on the last rejector's role.

### Case A — Last Rejector Was a Reviewer

Full reset. The entire participant list is replaced.

```json
{
  "participants": [
    { "email": "newreviewer@example.com", "role": "REVIEWER", "order": 1 },
    { "email": "approver@example.com",    "role": "APPROVER", "order": 2 }
  ]
}
```

The contract starts fresh from order 1 with status `IN_REVIEW` or `IN_APPROVAL` based on the first participant.

### Case B — Last Rejector Was an Approver

Partial reset. Completed reviewers are **preserved** and the new list must contain **only APPROVERs**.

```json
{
  "participants": [
    { "email": "newapprover@example.com", "role": "APPROVER", "order": 2 }
  ]
}
```

The service automatically merges the preserved (completed) reviewers with the new approvers. Because all reviewers are already `completed`, the new approver at the minimum order is immediately `unlocked` — no re-review cycle. The contract status is set to `IN_APPROVAL`.

Passing a `REVIEWER` in the new list when the last rejector was an approver throws `400 Bad Request`.

### Rejection History

Every rejection appends a `ModificationRequest` entry to `contract.modificationRequests[]`:

```json
{
  "requestedBy": "approver@example.com",
  "role":        "approver",
  "message":     "CFO needs to review first",
  "requestedAt": "2026-06-30T14:00:00"
}
```

On resubmission, a new entry is also appended:
```json
{
  "requestedBy": "owner@example.com",
  "role":        "contractor",
  "message":     "Resubmitted after approver rejection",
  "requestedAt": "2026-06-30T15:00:00"
}
```

---

## 12. Error Reference

All errors follow the global exception handler format:

```json
{
  "timestamp": "2026-06-30T12:00:00.000+00:00",
  "status":    400,
  "error":     "Bad Request",
  "message":   "Approvers must sign and upload the contract PDF. uploadId is required.",
  "path":      "/contracts/abc123/flow/complete"
}
```

### Common Error Messages

| HTTP | Message | Cause |
|---|---|---|
| 404 | `Contract not found` | Contract ID does not exist or caller is not the owner |
| 400 | `Contract must be DRAFT or REJECTED to submit into the unified flow.` | Wrong starting status |
| 400 | `At least one participant is required` | Empty participants list |
| 400 | `At least one APPROVER is required in the participant list` | Reviewers only |
| 400 | `Duplicate order numbers are not allowed` | Two participants with same order |
| 400 | `Duplicate participant emails are not allowed` | Same email twice |
| 400 | `You cannot assign yourself as a participant` | Owner email in participant list |
| 400 | `All REVIEWER orders must be lower than all APPROVER orders.` | Order constraint violated |
| 400 | `Field edits are only allowed while the contract is IN_REVIEW or IN_APPROVAL.` | Wrong status for field edit |
| 400 | `You are not an assigned participant for this contract` | Email not in participants list |
| 400 | `It is not your turn yet, or you have already completed your action on this contract` | Status is pending/completed/rejected |
| 400 | `Reviewers cannot upload a signed PDF. Only approvers sign the contract.` | Reviewer provided uploadId or parts |
| 400 | `Approvers must sign and upload the contract PDF. uploadId is required.` | Approver missing uploadId |
| 400 | `Approvers must sign and upload the contract PDF. parts list is required.` | Approver missing parts |
| 400 | `Upload is only allowed during the approval stage.` | Not in IN_APPROVAL |
| 400 | `Only approvers can upload a signed PDF` | Reviewer calling upload endpoint |
| 400 | `Part number must be between 1 and 10000` | Invalid partNumber |
| 400 | `uploadId is required` | Blank uploadId in abort |
| 400 | `Contract must be READY_FOR_SIGNATURE to send for external signature.` | Wrong status |
| 400 | `Contract file must be uploaded before sending for signature` | fileUploaded = false |
| 400 | `Cannot send for external signature — the following org fields are still empty: CEO Name, Contract Date` | Org-gate failure |
| 400 | `No rejection record found. Cannot determine resubmission type.` | REJECTED contract with no ModificationRequest |
| 400 | `When resubmitting after approver rejection, only APPROVER participants can be changed.` | Reviewer in list after approver rejection |

---

## 13. End-to-End Integration Walkthrough

This walkthrough covers the complete happy path: two internal participants (reviewer + approver) followed by one external signer.

> Replace `BASE_URL`, `OWNER_TOKEN`, `REVIEWER_TOKEN`, `APPROVER_TOKEN`, and `CONTRACT_ID` with actual values.

---

**Step 1 — Owner submits the contract into unified flow**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/submit \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "participants": [
      { "email": "reviewer@example.com", "name": "Alice", "role": "REVIEWER", "order": 1 },
      { "email": "approver@example.com", "name": "Bob",   "role": "APPROVER", "order": 2 }
    ],
    "externalSigningIncluded": true
  }'
```

Contract is now `IN_REVIEW`. Alice is `unlocked`.

---

**Step 2 — Reviewer checks inbox**

```bash
curl -X GET $BASE_URL/contracts/flow/inbox \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
```

Contract appears in Alice's inbox.

---

**Step 3 — Reviewer edits org fields**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/fields \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fieldValues": {
      "companyName":  "Acme Corp",
      "contractDate": "2026-07-01"
    }
  }'
```

Alice's status moves to `in_progress`.

---

**Step 4 — Reviewer marks complete**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/complete \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "comments": "All fields verified, approved" }'
```

Alice becomes `completed`. Bob is `unlocked`. Contract moves to `IN_APPROVAL`.

---

**Step 5 — Approver checks flow status**

```bash
curl -X GET $BASE_URL/contracts/$CONTRACT_ID/flow/status \
  -H "Authorization: Bearer $APPROVER_TOKEN"
```

Bob sees `status: IN_APPROVAL`, his status is `unlocked`.

---

**Step 6 — Approver gets current PDF to sign**

```bash
curl -X GET $BASE_URL/contracts/$CONTRACT_ID/flow/file-url \
  -H "Authorization: Bearer $APPROVER_TOKEN"
```

Returns presigned URL. Bob downloads and signs the PDF.

---

**Step 7 — Approver initiates upload**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/upload/initiate \
  -H "Authorization: Bearer $APPROVER_TOKEN"
# Response: { "uploadId": "UPLOAD_ID" }
```

---

**Step 8 — Approver gets presigned URL and uploads chunk**

```bash
curl -X GET "$BASE_URL/contracts/$CONTRACT_ID/flow/upload/presign?uploadId=UPLOAD_ID&partNumber=1" \
  -H "Authorization: Bearer $APPROVER_TOKEN"
# Response: { "url": "PRESIGNED_URL", "partNumber": 1 }

# PUT directly to MinIO (no auth header)
curl -X PUT "PRESIGNED_URL" \
  -H "Content-Type: application/pdf" \
  --data-binary @signed-contract.pdf
# Save ETag from response header
```

---

**Step 9 — Approver marks complete with signed PDF**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/complete \
  -H "Authorization: Bearer $APPROVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "uploadId": "UPLOAD_ID",
    "parts":    [{ "partNumber": 1, "etag": "ETAG_FROM_STEP_8" }],
    "comments": "Signed by CFO"
  }'
```

Bob becomes `completed`. Contract moves to `READY_FOR_SIGNATURE`.

---

**Step 10 — Owner checks org-gate**

```bash
curl -X GET $BASE_URL/contracts/$CONTRACT_ID/flow/status \
  -H "Authorization: Bearer $OWNER_TOKEN"
# Check orgFieldsComplete and unfilledOrgFields
```

---

**Step 11 — Owner sends for external signature**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/send-for-signature \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "assignments": [
      {
        "partyId":    "party-external-uuid",
        "partyLabel": "Client",
        "type":       "external",
        "email":      "client@example.com",
        "name":       "Client Name",
        "order":      1
      }
    ],
    "senderName": "Acme Corp"
  }'
```

External signing flow begins.

---

## 14. Frontend Integration Guide

### 14.1 Detecting Which Flow a Contract Is In

A contract is in the **unified workflow** if `contract.participants` is non-null and non-empty. A contract is in the **legacy workflow** if `contract.reviewers` or `contract.approver` is set. These are mutually exclusive.

| Field present | Flow |
|---|---|
| `participants[]` non-empty | Unified workflow |
| `reviewers[]` / `approver` set | Legacy workflow |

### 14.2 Rendering Participant List (Owner View)

Use `GET /contracts/{id}/flow/status` to get the full `participants[]` array with current statuses. Display a timeline or step-list using `order` for position and `status` for the icon/color:

| Status | UI suggestion |
|---|---|
| `pending` | Grey dot — waiting |
| `unlocked` | Blue dot — active, waiting for action |
| `in_progress` | Yellow dot — working |
| `completed` | Green check — done |
| `rejected` | Red X — rejected |

### 14.3 Participant Inbox (Reviewer / Approver View)

On login, call `GET /contracts/flow/inbox` to get contracts where the user has an active task. Show a badge count or a dedicated inbox section.

### 14.4 Reviewer Editing Flow

1. Call `GET /contracts/{id}/flow/status` to confirm `status = IN_REVIEW` and participant's status is `unlocked` or `in_progress`
2. Load form fields from `GET /contracts/{id}` → `formFields[]`
3. As the user edits, call `POST /contracts/{id}/flow/fields` to save
4. When done, call `POST /contracts/{id}/flow/complete` (no uploadId/parts)
5. Or call `POST /contracts/{id}/flow/reject` with a reason

### 14.5 Approver Signing Flow

1. Confirm `status = IN_APPROVAL` and participant's status is `unlocked` or `in_progress`
2. Call `GET /contracts/{id}/flow/file-url` to get the PDF to sign
3. User signs the PDF (in a PDF editor or signing tool)
4. Call `POST /contracts/{id}/flow/upload/initiate` → receive `uploadId`
5. For each chunk:
   - Call `GET /contracts/{id}/flow/upload/presign?uploadId=...&partNumber=N`
   - PUT the chunk to the presigned URL
   - Collect the `ETag` from response header
6. Call `POST /contracts/{id}/flow/complete` with `uploadId` + `parts[]`
7. On any error, call `POST /contracts/{id}/flow/upload/abort?uploadId=...`

### 14.6 Owner Send-for-Signature Flow

1. Poll `GET /contracts/{id}/flow/status` until `status = READY_FOR_SIGNATURE`
2. Check `orgFieldsComplete`. If `false`, show `unfilledOrgFields` list to the user — they need to fill those in the contract editor
3. When all fields filled, call `POST /contracts/{id}/flow/send-for-signature`

### 14.7 Rejection & Resubmit UI

When `contract.status = REJECTED`:
- Read `contract.modificationRequests[]` — the last entry has the rejection reason and role
- Show the owner a resubmit form:
  - If `lastRejection.role = "reviewer"` → show full participant assignment form
  - If `lastRejection.role = "approver"` → show only approver assignment (reviewers are locked in)
- Call `POST /contracts/{id}/flow/submit` with the new list

---

## 15. Test Coverage

The unified workflow is covered by **71 unit tests** in `UnifiedWorkflowServiceTest.java`.

| Test Class | Tests | What It Covers |
|---|---|---|
| `submit — fresh DRAFT` | 13 | All submission validations, status transitions, participant ordering, flag persistence |
| `submit — resubmit after rejection` | 4 | Reviewer rejection full reset, approver rejection partial reset, validation of reviewer-in-list error, no modification records error |
| `saveFieldEdits` | 7 | Status transitions (unlocked → in_progress), field merging, filledBy attribution, access control, wrong-status guard |
| `markComplete` | 11 | Reviewer completion (advance + READY), reviewer PDF rejection, approver completion with MinIO call, version increment, signedPdfKey set, missing uploadId/parts errors, access control |
| `reject` | 5 | Reviewer and approver rejection paths, mod request role field, access control, wrong-status guard |
| `initiateUpload` | 4 | Approver gets uploadId, reviewer blocked, wrong status blocked, not found |
| `getPresignedPartUrl` | 4 | Part number bounds (0, 10001), non-approver blocked, not found |
| `abortUpload` | 4 | Cancel called with correct args, blank/null uploadId validation, non-approver blocked |
| `sendForSignature` | 8 | Happy path, non-owner 404, wrong status, file not uploaded, org-gate unfilled, org-gate external party skip, no-form-fields pass, no-parties pass |
| `getFlowStatus` | 5 | Owner access, participant access, outsider 404, orgFieldsComplete=true, orgFieldsComplete=false with labels |
| `getFlowInbox` | 6 | Unlocked in inbox, in_progress in inbox, completed excluded, pending excluded, mixed contracts filtered, empty repo |
| **Total** | **71** | |

**Running the tests:**

```bash
# Set JAVA_HOME to JDK 25 first (project requires Java 25)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Run only unified workflow tests
mvn test -Dtest=UnifiedWorkflowServiceTest

# Run all tests
mvn test
```

---

*End of Unified Workflow Documentation*
