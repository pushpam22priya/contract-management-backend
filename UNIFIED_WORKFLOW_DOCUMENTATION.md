# Unified Workflow — Technical Documentation

**Version:** 2.1.0
**Last Updated:** 2026-07-08
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
11. [External Signing — Auto-Trigger vs Manual](#11-external-signing--auto-trigger-vs-manual)
12. [Resubmission After Rejection](#12-resubmission-after-rejection)
13. [Email Delivery & Error Handling](#13-email-delivery--error-handling)
14. [Error Reference](#14-error-reference)
15. [End-to-End Integration Walkthrough](#15-end-to-end-integration-walkthrough)
16. [Frontend Integration Guide](#16-frontend-integration-guide)
17. [Test Coverage](#17-test-coverage)

---

## 1. Introduction

### 1.1 What Is the Unified Workflow?

The Unified Workflow is a **new, parallel service** that merges internal review, internal approval, and optional external client signing into a **single sequential chain**. It replaces the need to run separate review → approval → signature flows in disconnected steps.

The key distinction from the legacy `ContractWorkflowService`:
- **Legacy flow**: Review, Approval, and Signature were three separate, loosely-coupled processes.
- **Unified flow**: All internal participants (reviewers and approvers) are assigned upfront with a global sequential order. Each participant — reviewer or approver — edits the PDF and uploads it when completing their step. After all internal work is done the contract automatically advances.

### 1.2 What the System Does

- Assigns a sequential list of **reviewers** and **approvers** to a contract in a single submit call
- Unlocks each participant only after all participants at the previous order number have completed
- **Both reviewers and approvers** edit org-party form fields, upload the modified PDF, and mark complete — the upload flow is identical for both roles
- Each participant's upload overwrites the same `_signed.pdf` key — the next participant always works from the most recent version
- On rejection by a reviewer, the contract returns to `REJECTED_BY_REVIEWER`; on rejection by an approver, it returns to `REJECTED_BY_APPROVER`. Both are eligible for the owner to resubmit
- After all internal participants are done, the contract moves to `READY_FOR_SIGNATURE`
- **When `externalSigningIncluded = true`**: External signing is auto-triggered immediately after the last approver completes — no manual action needed. The `externalSigners` list provided at submit time is used automatically.
- **When `externalSigningIncluded = false`**: Contract reaches `READY_FOR_SIGNATURE` and the owner manually calls `POST /flow/send-for-signature` when ready
- Provides an **inbox endpoint** so reviewers and approvers see only contracts where it is currently their turn
- Provides a **sent endpoint** so reviewers and approvers can view contracts they have already completed (read-only)

### 1.3 What This Service Does NOT Replace

The legacy `ContractWorkflowService` (existing `/contracts/{id}/review`, `/contracts/{id}/approve`, and `/contracts/{id}/signature` endpoints) is **completely untouched**. Both flows can coexist. A contract can only be in one flow at a time.

---

## 2. Architecture & Design Principles

### 2.1 Additive-Only Changes

The unified workflow was implemented with a strict **additive-only policy** on shared models:

| File | Type of Change |
|---|---|
| `Contract.java` | 5 new fields added — zero fields removed or renamed |
| `ContractStatus.java` | `REJECTED` value added |
| `Party.java` | `type` field added (null = INTERNAL, backward compatible) |
| `Template.java` (inner Party) | `type` field added |
| `ContractRepository.java` | 1 new query method added |
| `ContractRenewalService.java` | 1 line added in `convertParties()` |
| `EmailService.java` | Email exceptions now rethrow — callers handle gracefully |
| `SignatureService.java` | Email exceptions caught per-signer — signing flow is never blocked |
| `AutoAdvanceService.java` | Email exceptions caught per-signer |

All net-new files:
- `model/PartyType.java`
- `model/ParticipantRole.java`
- `model/WorkflowParticipant.java`
- `dto/ParticipantAssignment.java`
- `dto/ExternalSignerAssignment.java`
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

Every participant — reviewer and approver — uploads the PDF when completing their step. A single MinIO object key is used and **overwritten** after each participant completes:

```
contracts/{contractId}_signed.pdf
```

This is safe because participants are unlocked **sequentially** — Participant at order 2 is never unlocked until Participant at order 1 has fully completed and their PDF is committed. There are no concurrent writes to the same key.

The `getParticipantFileUrl` endpoint always returns the **latest available version**: if `_signed.pdf` exists in MinIO, it is returned; otherwise the original `contracts/{contractId}.pdf` is returned. This ensures each participant always reads the most up-to-date state.

After the multipart upload completes in `markComplete()`, `contract.fileUploaded` is also set to `true`. This guarantees the downstream signature flow's file-check always passes for contracts going through the unified flow.

### 2.4 Optimistic Locking

After each **approver** completes their PDF upload, `contract.version` is incremented. This version is also synced to any pending `SignatureRequest` documents in MongoDB so that the external signing flow operates on the correct version. Reviewer completions do not increment the version.

### 2.5 Auto-Trigger External Signing

When `externalSigningIncluded = true` and the last approver marks complete:

1. `markComplete()` saves the contract at `READY_FOR_SIGNATURE` to MongoDB
2. `triggerAutoExternalSigning()` is called immediately after
3. It builds `SignerAssignmentDto` objects from `contract.pendingExternalSigners` (stored at submit time)
4. Calls `signatureService.submitForSignature()` which saves the contract as `IN_SIGNATURE` and sends emails
5. The contract is **reloaded from DB** and the `IN_SIGNATURE` state is returned in the `markComplete` response

If the auto-trigger fails for any reason (e.g., SMTP error), the failure is logged prominently but the API still returns 200. The contract DB state at that point is whatever was last saved — either `IN_SIGNATURE` (signing triggered but email failed) or `READY_FOR_SIGNATURE` (trigger failed before signing). The owner can fall back to calling `POST /flow/send-for-signature` manually.

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
| `submit` | Contract owner (`createdBy`) only |
| `saveFieldEdits` | Active participant (`unlocked` or `in_progress`) only |
| `markComplete` | Active participant (`unlocked` or `in_progress`) only |
| `reject` | Active participant (`unlocked` or `in_progress`) only |
| `initiateUpload` | Active participant (reviewer **or** approver) while status is `IN_REVIEW` or `IN_APPROVAL` |
| `getPresignedPartUrl` | Active participant (reviewer **or** approver) while status is `IN_REVIEW` or `IN_APPROVAL` |
| `abortUpload` | Active participant (reviewer **or** approver) while status is `IN_REVIEW` or `IN_APPROVAL` |
| `getParticipantFileUrl` | Contract owner OR any participant (any status) |
| `sendForSignature` | Contract owner only, while status is `READY_FOR_SIGNATURE` |
| `getFlowStatus` | Contract owner OR any participant |
| `getFlowInbox` | Any authenticated user (filtered to caller's active tasks) |
| `getFlowSent` | Any authenticated user (filtered to caller's completed contracts) |

### 3.2 Ownership Masking

Non-owner access to `submit` and `sendForSignature` returns **404 Not Found** instead of 403 Forbidden. This prevents contract ID enumeration.

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
unlocked     — it is this participant's turn; can edit fields, upload PDF, and complete/reject
in_progress  — has saved at least one field edit; can still upload and complete/reject
completed    — marked the workflow step complete; cannot undo
rejected     — rejected the contract; workflow stops, contract goes to REJECTED
```

### 4.3 Participant Roles

| Role | Can Edit Fields | Must Upload PDF | Can Reject |
|---|---|---|---|
| `REVIEWER` | Yes | **Yes — PDF upload is required to mark complete** | Yes |
| `APPROVER` | Yes | **Yes — PDF upload is required to mark complete** | Yes |

Both roles follow the **identical** upload flow: initiate multipart upload → presign chunk URLs → PUT chunks to MinIO → call `markComplete` with `uploadId` + `parts`.

### 4.4 Global Ordering

Order numbers are **global** — they do not reset per role. All reviewer orders **must** be lower than all approver orders.

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

A boolean flag set at submit time that controls the post-approval path.

| Value | Behavior after last approver completes |
|---|---|
| `true` | External signing is **auto-triggered** using the `externalSigners` list stored at submit time. The owner does not need to do anything. |
| `false` | Contract reaches `READY_FOR_SIGNATURE`. The owner manually calls `POST /flow/send-for-signature` when ready. |

When `true`, the `externalSigners` list is **required** at submit time. When `false`, no external signers need to be provided at submit.

In both cases, only `type: "external"` signers are accepted in the signature flow.

---

## 5. Contract Status State Machine

### 5.1 States Used by Unified Workflow

| Status | Meaning |
|---|---|
| `DRAFT` | Initial state — eligible for unified workflow submission |
| `IN_REVIEW` | Current active participants are REVIEWERs |
| `IN_APPROVAL` | Current active participants are APPROVERs |
| `READY_FOR_SIGNATURE` | All internal participants completed — auto-trigger pending, or owner may call send-for-signature |
| `IN_SIGNATURE` | External signing in progress (managed by SignatureService) |
| `REJECTED_BY_REVIEWER` | A reviewer rejected the contract — eligible for resubmission |
| `REJECTED_BY_APPROVER` | An approver rejected the contract — eligible for resubmission |
| `REJECTED` | Legacy rejection status — also eligible for resubmission (backward compatibility) |

### 5.2 State Transition Diagram

```
                      ┌─────────────────────────────────────────────────────────┐
                      │              OWNER: submit() (resubmit)                 │
                      ▼                                                         │
 ┌──────────┐  first role=REVIEWER  ┌───────────┐                              │
 │  DRAFT   │ ──────────────────── ▶│ IN_REVIEW │                              │
 └──────────┘                       └─────┬─────┘                              │
      │                                   │ all at current order complete       │
      │  first role=APPROVER              ▼                                     │
      │                           ┌─────────────┐                              │
      └──────────────────────────▶│ IN_APPROVAL │                              │
                                  └──────┬──────┘                              │
                                         │                                      │
                      ┌──────────────────┤ all approvers complete              │
                      │ reviewer rejects  │                                     │
                      ▼                  ▼                                      │
          ┌─────────────────┐  ┌────────────────────┐                          │
          │REJECTED_BY_     │  │ READY_FOR_SIGNATURE│                          │
          │REVIEWER         │  └──────┬──────┬───────┘                          │
          └────────┬────────┘         │      │ externalSigningIncluded=false    │
                   │                  │      │ owner: sendForSignature()        │
                   │  approver rejects│      ▼                                  │
          ┌────────▼────────┐         │  ┌──────────────┐                      │
          │REJECTED_BY_     │         │  │ IN_SIGNATURE │                      │
          │APPROVER         │         │  └──────────────┘                      │
          └────────┬────────┘         │                                         │
                   │   externalSigning│Included=true → auto-trigger fires      │
                   └──────────────────┴─────────────────────────────────────────┘
```

### 5.3 Resubmission After Rejection

Regardless of **who rejected**, the resubmission is always a **full reset**:

```
REJECTED_BY_REVIEWER  →  Full reset: all participants replaced, new flow starts from order 1
REJECTED_BY_APPROVER  →  Full reset: all participants replaced (including previously completed reviewers)
REJECTED (legacy)     →  Full reset: backward compatible, same behaviour
```

The owner must call `PATCH /contracts/{id}` first to update the contract with the fresh template file and metadata, then call `POST /contracts/{id}/flow/submit` to send the updated contract to the new set of participants. When `submit` is called on a rejected contract, the old `_signed.pdf` is deleted from MinIO so participants in the new flow always see the fresh template.

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
  "comments":    "Looks good"
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

Five new fields added to the `Contract` document for the unified flow:

| Field | Type | Description |
|---|---|---|
| `participants` | `List<WorkflowParticipant>` | All assigned internal participants |
| `currentParticipantOrder` | `Integer` | Order number currently active (null when READY_FOR_SIGNATURE) |
| `externalSigningIncluded` | `boolean` | Whether external signing is expected after internal flow |
| `pendingExternalSigners` | `List<ExternalSignerAssignment>` | Stored at submit time; auto-consumed when last approver completes (only when `externalSigningIncluded=true`) |
| `flowSenderName` | `String` | Sender display name used in external signing emails |

### 6.3 ExternalSignerAssignment (DTO + embedded in Contract)

```json
{
  "email":      "client@example.com",
  "name":       "Client Name",
  "partyId":    "party-external-uuid",
  "partyLabel": "Client",
  "order":      1
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `email` | String | Yes | Valid email — the external signer's email address |
| `name` | String | No | Display name shown in signature emails |
| `partyId` | String | Yes | Must match a party ID on the contract |
| `partyLabel` | String | Yes | Display label for the party |
| `order` | int | Yes | Signing order (positive integer, 1-based) |

### 6.4 ParticipantRole Enum

```java
public enum ParticipantRole {
    REVIEWER,
    APPROVER
}
```

### 6.5 PartyType Enum

```java
public enum PartyType {
    INTERNAL,   // org-owned party — fields must be filled by internal users
    EXTERNAL    // external client party — fields filled by the external signer
}
```

### 6.6 Party Model (updated)

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

### 6.7 FlowStatusResponse Fields

```json
{
  "contractId":              "abc123",
  "status":                  "IN_APPROVAL",
  "currentParticipantOrder": 2,
  "externalSigningIncluded": true,
  "orgFieldsComplete":       false,
  "unfilledOrgFields":       ["CEO Name", "Contract Date"],
  "parties": [
    { "id": "party-1", "label": "Our Company", "type": "INTERNAL", "order": 1 },
    { "id": "party-2", "label": "Client",      "type": "EXTERNAL", "order": 2 }
  ],
  "formFields": [
    { "fieldName": "companyName", "value": "Acme Corp", "assignedParty": "party-1" }
  ],
  "xfdfData": "<?xml version=\"1.0\"?>...",
  "participants": [...]
}
```

| Field | Type | Description |
|---|---|---|
| `contractId` | String | The contract ID |
| `status` | String | Current contract status |
| `currentParticipantOrder` | Integer | Active order number; null if READY_FOR_SIGNATURE |
| `externalSigningIncluded` | boolean | Whether external signing is expected |
| `orgFieldsComplete` | boolean | True if all INTERNAL party fields are filled |
| `unfilledOrgFields` | Array | Labels of INTERNAL party fields that are still empty |
| `parties` | Array | Raw party list from the contract (raw, no enrichment) |
| `formFields` | Array | Raw form fields from the contract (raw, no enrichment) |
| `xfdfData` | String | The latest XFDF annotation string (updated by each participant) |
| `participants` | Array | All participants with their current statuses |

### 6.8 FlowFieldEditRequest Fields

```json
{
  "formFields": [
    { "fieldName": "companyName", "value": "Acme Corp" }
  ],
  "fieldValues": {
    "companyName": "Acme Corp"
  },
  "xfdfData": "<?xml version=\"1.0\"?>..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `formFields` | Array | No | Full field objects — merged by `fieldName`/`name` key; `filledBy` is set to caller's email |
| `fieldValues` | Map | No | Simple key-value pairs merged into `contract.fieldValues` |
| `xfdfData` | String | No | XFDF annotation string — overwrites the existing value when provided |

### 6.9 FlowCompleteRequest Fields

```json
{
  "uploadId":   "minio-upload-id",
  "parts":      [{ "partNumber": 1, "etag": "abc123" }],
  "comments":   "Signed and approved",
  "formFields": [{ "fieldName": "ceoName", "value": "John" }],
  "fieldValues": { "ceoName": "John" },
  "xfdfData":   "<?xml version=\"1.0\"?>..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `uploadId` | String | **Yes (both roles)** | The `uploadId` from `initiateUpload` |
| `parts` | Array | **Yes (both roles)** | All part numbers and ETags from the PUT responses |
| `parts[].partNumber` | int | Yes | Part number (1-based) |
| `parts[].etag` | String | Yes | ETag from the presigned PUT response header |
| `comments` | String | No | Optional remarks stored on participant |
| `formFields` | Array | No | Optional final field state to merge before completing |
| `fieldValues` | Map | No | Optional simple field values to merge |
| `xfdfData` | String | No | XFDF annotation string saved on complete |

### 6.10 ModificationRequest (used for rejection tracking)

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
| Contract must be in `DRAFT`, `REJECTED`, `REJECTED_BY_REVIEWER`, or `REJECTED_BY_APPROVER` | 400 Bad Request |
| Participant list must not be empty | 400 Bad Request |
| At least one `APPROVER` is required | 400 Bad Request |
| No duplicate order numbers | 400 Bad Request |
| No duplicate participant emails | 400 Bad Request |
| Owner cannot assign themselves as a participant | 400 Bad Request |
| All `REVIEWER` orders must be < all `APPROVER` orders | 400 Bad Request |
| `externalSigners` is required when `externalSigningIncluded = true` | 400 Bad Request |

### 7.2 Field Edit Rules

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be an active participant (`unlocked` or `in_progress`) | 400 Bad Request |

### 7.3 Mark Complete Rules — Both Reviewer and Approver

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be active participant (unlocked or in_progress) | 400 Bad Request |
| `uploadId` is required (non-blank) | 400 Bad Request |
| `parts` list is required (non-empty) | 400 Bad Request |

Both roles must complete the multipart PDF upload. There is no role distinction in the complete endpoint.

### 7.4 Rejection Rules

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be active participant | 400 Bad Request |
| `message` is required (non-blank) | 400 Bad Request |

### 7.5 Upload Rules (Active Participant — Reviewer or Approver)

| Rule | Error |
|---|---|
| Contract must be in `IN_REVIEW` or `IN_APPROVAL` | 400 Bad Request |
| Caller must be an active participant (unlocked or in_progress) | 400 Bad Request |
| `partNumber` must be between 1 and 10000 (for presign) | 400 Bad Request |
| `uploadId` must be non-blank (for abort) | 400 Bad Request |

### 7.6 Send for Signature Rules (Manual — `externalSigningIncluded = false`)

| Rule | Error |
|---|---|
| Caller must be contract owner | 404 Not Found |
| Contract must be in `READY_FOR_SIGNATURE` | 400 Bad Request |
| Contract file (`fileUploaded`) must be true | 400 Bad Request |
| All `INTERNAL` party form fields must be non-empty (org-gate) | 400 Bad Request |
| All signers must have `type: "external"` — internal signers are rejected | 400 Bad Request |

### 7.7 Resubmission — "Update and Resubmit" Flow

Resubmission is always a **full reset** regardless of who rejected. The owner:
1. Calls `PATCH /contracts/{id}` with fresh metadata, `xfdfData`, `fieldValues`, and `formFields` (from the clean template)
2. Uploads the new filled PDF via the single-shot or chunked upload endpoint
3. Calls `POST /contracts/{id}/flow/submit` with a complete new participant list (reviewers + approvers)

On step 3, the backend automatically deletes the old `_signed.pdf` from MinIO and clears `signedPdfKey` so reviewers in the new flow see the fresh template. Any combination of reviewer and approver assignments is valid.

---

## 8. API Reference

**Base path:** `/contracts`
**Auth:** All endpoints require `Authorization: Bearer <jwt-token>`

---

### 8.1 Submit Unified Flow

**`POST /contracts/{id}/flow/submit`**

Submits a `DRAFT` or `REJECTED` contract into the unified workflow. Assigns all participants and stores external signer info for auto-trigger.

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
  "externalSigningIncluded": true,
  "externalSigners": [
    {
      "email":      "client@example.com",
      "name":       "Client Name",
      "partyId":    "party-external-uuid",
      "partyLabel": "Client",
      "order":      1
    }
  ],
  "senderName": "Priya from CostaCloud"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `participants` | Array | Yes | At least one; must include at least one APPROVER |
| `participants[].email` | String | Yes | Valid email; cannot be the owner's email |
| `participants[].name` | String | No | Display name |
| `participants[].role` | String | Yes | `REVIEWER` or `APPROVER` |
| `participants[].order` | int | Yes | Positive integer; unique; all REVIEWER orders < all APPROVER orders |
| `externalSigningIncluded` | boolean | No | Default `false`. When `true`, org-gate is enforced and external signers must be provided |
| `externalSigners` | Array | **Required if `externalSigningIncluded=true`** | Stored and auto-used when last approver completes |
| `externalSigners[].email` | String | Yes | External signer email |
| `externalSigners[].name` | String | No | Display name for emails |
| `externalSigners[].partyId` | String | Yes | Party ID on the contract |
| `externalSigners[].partyLabel` | String | Yes | Party display label |
| `externalSigners[].order` | int | Yes | Signing order (1-based) |
| `senderName` | String | No | Display name shown in external signing emails |

**Success Response — 200 OK**

Returns the full `ContractResponse` with `status` set to `IN_REVIEW` or `IN_APPROVAL` and `participants[]` populated.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found, or caller is not the owner |
| 400 | Contract not in DRAFT/REJECTED; validation failures (see §7.1) |

---

### 8.2 Save Field Edits

**`POST /contracts/{id}/flow/fields`**

Allows the currently active participant to save form field values and/or XFDF annotation data. On the first call, the participant's status transitions from `unlocked` → `in_progress`.

**Request Body**

```json
{
  "formFields": [
    { "fieldName": "companyName", "value": "Acme Corp" }
  ],
  "fieldValues": {
    "companyName": "Acme Corp"
  },
  "xfdfData": "<?xml version=\"1.0\"?>..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `formFields` | Array | No | Full field objects — merged by `fieldName`/`name`; `filledBy` is set to caller's email |
| `fieldValues` | Map | No | Simple key-value pairs merged into `contract.fieldValues` |
| `xfdfData` | String | No | XFDF string — overwrites the stored value when provided |

Send any combination. Sending all three updates all three storage locations.

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

Marks the caller's participation as complete. **Both reviewers and approvers must upload the edited PDF.** Both roles use the identical request format.

**Request Body**

```json
{
  "uploadId": "minio-upload-id-from-initiate",
  "parts": [
    { "partNumber": 1, "etag": "d8e8fca2dc0f896fd7cb4cb0031ba249" }
  ],
  "comments":   "Reviewed, fields updated",
  "formFields": [{ "fieldName": "companyName", "value": "Acme Corp" }],
  "fieldValues": { "companyName": "Acme Corp" },
  "xfdfData":   "<?xml version=\"1.0\"?>..."
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `uploadId` | String | **Yes** | The `uploadId` returned by `initiateUpload` |
| `parts` | Array | **Yes** | All part numbers and ETags from the PUT responses |
| `parts[].partNumber` | int | Yes | Part number (1-based) |
| `parts[].etag` | String | Yes | ETag from the presigned PUT response header |
| `comments` | String | No | Optional remarks stored on participant |
| `formFields` | Array | No | Optional final field state to merge before completing |
| `fieldValues` | Map | No | Optional simple field values to merge |
| `xfdfData` | String | No | XFDF annotation string saved on complete |

**Success Response — 200 OK**

Returns the updated `ContractResponse`. Inspect `status` to determine what happened:

| `status` in response | Meaning |
|---|---|
| `IN_REVIEW` or `IN_APPROVAL` | Advanced to next order — another participant is now active |
| `READY_FOR_SIGNATURE` | All internal participants completed; auto-trigger failed or `externalSigningIncluded=false` |
| `IN_SIGNATURE` | Auto-trigger succeeded — external signing emails sent (`externalSigningIncluded=true`) |

**Note on APPROVER completion:** After an approver marks complete, `contract.version` is incremented and synced to any pending `SignatureRequest` documents.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Wrong status; not an active participant; `uploadId` or `parts` missing |

---

### 8.4 Reject

**`POST /contracts/{id}/flow/reject`**

Rejects the contract. The participant's status is set to `rejected`, a `ModificationRequest` is appended, and the contract status moves to `REJECTED`.

**Request Body**

```json
{
  "message": "Clause 5 requires legal review before approval"
}
```

**Success Response — 200 OK**

Returns the updated `ContractResponse`. The `status` field reflects who rejected:

| Rejector role | Response `status` |
|---|---|
| `REVIEWER` | `REJECTED_BY_REVIEWER` |
| `APPROVER` | `REJECTED_BY_APPROVER` |

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Contract not IN_REVIEW/IN_APPROVAL; caller not active participant; message is blank |

---

### 8.5 Initiate Upload

**`POST /contracts/{id}/flow/upload/initiate`**

**Active participant (reviewer or approver).** Starts a MinIO multipart upload for the signed PDF. Returns the `uploadId` needed for subsequent presign and complete calls.

**Request Body:** None.

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
| 400 | Contract not in IN_REVIEW or IN_APPROVAL; caller not an active participant |

---

### 8.6 Get Presigned Part URL

**`GET /contracts/{id}/flow/upload/presign?uploadId={uploadId}&partNumber={n}`**

**Active participant (reviewer or approver).** Returns a presigned PUT URL for uploading a single chunk directly to MinIO. Expires in 15 minutes.

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

The client PUTs the chunk directly to `url`. Save the `ETag` response header — required in `markComplete`.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found |
| 400 | Not in IN_REVIEW/IN_APPROVAL; not active participant; partNumber out of range |

---

### 8.7 Abort Upload

**`POST /contracts/{id}/flow/upload/abort?uploadId={uploadId}`**

**Active participant (reviewer or approver).** Cancels an in-progress multipart upload. Call on user cancellation or upload error to free MinIO resources.

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
| 400 | Not in IN_REVIEW/IN_APPROVAL; not active participant; uploadId blank |

---

### 8.8 Get Participant File URL

**`GET /contracts/{id}/flow/file-url`**

Returns a 15-minute presigned GET URL for the current PDF:
- If `contracts/{id}_signed.pdf` exists in MinIO → returns the latest participant-uploaded version
- Otherwise → returns the original `contracts/{id}.pdf`

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

### 8.9 Send for Signature (Manual — `externalSigningIncluded = false`)

**`POST /contracts/{id}/flow/send-for-signature`**

**Owner only. Used when `externalSigningIncluded = false`.**

Sends the contract for external signature. The org-gate is enforced first (all INTERNAL party fields must be non-empty). Only `type: "external"` signers are accepted — any internal signer is rejected with 400.

This endpoint is also available as a **fallback** when `externalSigningIncluded = true` but the auto-trigger failed.

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
| `assignments[].partyId` | String | Yes | Must match a party ID on the contract |
| `assignments[].partyLabel` | String | Yes | Display label |
| `assignments[].type` | String | Yes | **Must be `"external"`** — internal signers rejected |
| `assignments[].email` | String | Yes | Signer's email address |
| `assignments[].name` | String | No | Display name |
| `assignments[].order` | int | Yes | Signing order (1-based) |
| `senderName` | String | No | Shown as sender name in signature emails |

**Success Response — 200 OK**

Returns the updated `ContractResponse` with `status: "IN_SIGNATURE"`.

**Error Responses**

| Status | Condition |
|---|---|
| 404 | Contract not found, or caller not owner |
| 400 | Status not READY_FOR_SIGNATURE; file not uploaded; org fields empty; signer type is not "external" |

---

### 8.10 Get Flow Status

**`GET /contracts/{id}/flow/status`**

Returns the full status of the unified workflow. Includes participant statuses, form field state, XFDF data, party list, and org-gate readiness.

**Success Response — 200 OK**

```json
{
  "contractId":              "abc123",
  "status":                  "IN_APPROVAL",
  "currentParticipantOrder": 2,
  "externalSigningIncluded": true,
  "orgFieldsComplete":       false,
  "unfilledOrgFields":       ["CEO Name", "Contract Date"],
  "parties": [
    { "id": "p1", "label": "Our Company", "type": "INTERNAL", "order": 1 },
    { "id": "p2", "label": "Client",      "type": "EXTERNAL", "order": 2 }
  ],
  "formFields": [
    { "fieldName": "companyName", "value": "Acme Corp", "assignedParty": "p1", "filledBy": "reviewer@example.com" }
  ],
  "xfdfData": "<?xml version=\"1.0\"?>...",
  "participants": [
    {
      "email": "reviewer@example.com", "role": "REVIEWER", "order": 1,
      "status": "completed", "completedAt": "2026-07-01T11:30:00"
    },
    {
      "email": "approver@example.com", "role": "APPROVER", "order": 2,
      "status": "unlocked", "unlockedAt": "2026-07-01T11:30:00"
    }
  ]
}
```

**Access:** Owner or any participant. Outsiders receive 404.

---

### 8.11 Flow Inbox

**`GET /contracts/flow/inbox`**

Returns contracts where the caller has an **active task** — status `unlocked` or `in_progress`. Excludes `pending`, `completed`, and `rejected`.

**Success Response — 200 OK**

Array of `ContractListResponse` objects. Empty array if the caller has no active tasks.

---

### 8.12 Flow Sent

**`GET /contracts/flow/sent`**

Returns contracts where the caller has **already completed or rejected** their participation (participant status `completed` or `rejected`). This is the read-only view for the "Sent" tab. The caller can view contract details but cannot edit or take any action. Rejected contracts remain in the sent tab until they are resubmitted and the caller has a new active turn.

**Success Response — 200 OK**

Array of `ContractListResponse` objects. Empty array if the caller has not completed any contracts.

---

## 9. Multipart PDF Upload Guide

Both reviewers and approvers upload the edited PDF using the same three-step MinIO multipart flow. The upload goes **browser → MinIO directly** — the Spring Boot server is not in the upload path (only coordination calls pass through it).

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

For each chunk (typically one part for files < 100 MB):

```
GET /contracts/{id}/flow/upload/presign?uploadId={uploadId}&partNumber=1
Authorization: Bearer <token>
```

Response:
```json
{ "url": "https://minio.../presigned-url", "partNumber": 1 }
```

PUT the chunk directly to MinIO (no auth header — URL is pre-signed):
```
PUT {presigned-url}
Content-Type: application/pdf
Body: <binary PDF chunk>
```

**Capture the `ETag` response header.** Required in Step 3.

### Step 3 — Complete (Mark Complete)

```
POST /contracts/{id}/flow/complete
Authorization: Bearer <token>
Content-Type: application/json

{
  "uploadId": "2~SFgzXV4GnNAuGtWq8Jq...",
  "parts": [
    { "partNumber": 1, "etag": "d8e8fca2dc0f896fd7cb4cb0031ba249" }
  ],
  "comments": "Reviewed and updated"
}
```

### Aborting on Error

```
POST /contracts/{id}/flow/upload/abort?uploadId={uploadId}
Authorization: Bearer <token>
```

### MinIO Object Key

The edited PDF is always stored at:
```
contracts/{contractId}_signed.pdf
```

This key is overwritten after each participant completes. The original upload at `contracts/{contractId}.pdf` is never modified.

---

## 10. Org-Field Gate

The org-gate prevents external signing from starting while internal org-party fields are still empty. It is enforced in both `sendForSignature()` (manual path) and `triggerAutoExternalSigning()` (auto path).

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

### Checking Gate State Before Sending

Call `GET /contracts/{id}/flow/status` — the response includes `orgFieldsComplete` and `unfilledOrgFields`. Use these to show the user which fields need to be filled before sending.

---

## 11. External Signing — Auto-Trigger vs Manual

### When `externalSigningIncluded = true` (Auto-Trigger)

1. At submit time, provide `externalSigners[]` and optionally `senderName`
2. These are stored on the contract as `pendingExternalSigners` and `flowSenderName`
3. When the last approver calls `markComplete`:
   - Contract is saved as `READY_FOR_SIGNATURE`
   - `triggerAutoExternalSigning()` fires immediately
   - Builds `SignerAssignmentDto` list from `pendingExternalSigners`, resolving `partyId`/`partyLabel` from the contract's party list if needed
   - Calls `signatureService.submitForSignature()` → contract transitions to `IN_SIGNATURE`, emails sent
   - Contract is reloaded from DB; `markComplete` response shows `IN_SIGNATURE`

**Submit request example (auto-trigger):**
```json
{
  "participants": [
    { "email": "reviewer@example.com", "role": "REVIEWER", "order": 1 },
    { "email": "approver@example.com", "role": "APPROVER", "order": 2 }
  ],
  "externalSigningIncluded": true,
  "externalSigners": [
    {
      "email":      "client@example.com",
      "name":       "Client Name",
      "partyId":    "party-external-uuid",
      "partyLabel": "Client",
      "order":      1
    }
  ],
  "senderName": "Priya from CostaCloud"
}
```

### When `externalSigningIncluded = false` (Manual)

1. At submit time, no `externalSigners` needed
2. Contract reaches `READY_FOR_SIGNATURE` after last approver completes
3. Owner checks `GET /contracts/{id}/flow/status` to confirm `orgFieldsComplete = true`
4. Owner calls `POST /contracts/{id}/flow/send-for-signature` with signer assignments

**Send-for-signature request example (manual):**
```json
{
  "assignments": [
    {
      "type":       "external",
      "email":      "client@example.com",
      "name":       "Client Name",
      "partyId":    "party-external-uuid",
      "partyLabel": "Client",
      "order":      1
    }
  ],
  "senderName": "Priya from CostaCloud"
}
```

### External-Only Validation

In both paths, only `type: "external"` signers are accepted. Passing an internal signer returns:
```
400 Bad Request: Only external signers are allowed in the unified flow.
Signer 'user@company.com' has type 'internal'.
```

---

## 12. Resubmission After Rejection

When a contract is in `REJECTED_BY_REVIEWER`, `REJECTED_BY_APPROVER`, or `REJECTED` (legacy) status, the owner runs the **"Update and Resubmit"** flow.

### Full Flow (always the same regardless of who rejected)

**Step 1 — Update the contract**

Call `PATCH /contracts/{id}` with the fresh metadata, `xfdfData`, `fieldValues`, and `formFields` from the clean template. Upload the new filled PDF file.

**Step 2 — Resubmit with a new participant list**

```json
{
  "participants": [
    { "email": "reviewer@example.com",    "role": "REVIEWER", "order": 1 },
    { "email": "approver@example.com",    "role": "APPROVER", "order": 2 }
  ],
  "externalSigningIncluded": false
}
```

Any combination of reviewers and approvers is valid. Resubmission is always a full reset — there is no partial reset. Previously completed reviewers are **not** preserved.

**What happens on resubmit (backend):**
1. The old `contracts/{id}_signed.pdf` is deleted from MinIO
2. `signedPdfKey` is cleared on the contract
3. All participants are replaced with the new list
4. The first participant (lowest order) is unlocked
5. Status becomes `IN_REVIEW` (if first is REVIEWER) or `IN_APPROVAL` (if first is APPROVER)
6. A `ModificationRequest` entry with `role: "contractor"` is appended

### Rejection History

Every rejection appends a `ModificationRequest`:

```json
{
  "requestedBy": "approver@example.com",
  "role":        "approver",
  "message":     "CFO needs to review first",
  "requestedAt": "2026-07-01T14:00:00"
}
```

The `role` is `"reviewer"` for reviewer rejections and `"approver"` for approver rejections. On resubmission, a `"contractor"` entry is appended:

```json
{
  "requestedBy": "owner@example.com",
  "role":        "contractor",
  "message":     "Resubmitted after rejection",
  "requestedAt": "2026-07-01T15:00:00"
}
```

---

## 13. Email Delivery & Error Handling

### How Emails Are Sent

When `signatureService.submitForSignature()` is called (either via auto-trigger or the manual `sendForSignature` endpoint), it:

1. Saves the contract as `IN_SIGNATURE`
2. Creates a `SignatureRequest` document per external signer
3. Calls `emailService.sendSignatureRequestEmail()` for each signer

### Error Behavior

`EmailService.sendSignatureRequestEmail()` now **rethrows** on SMTP failure (as `RuntimeException`) after logging the full error. The callers (`SignatureService` and `AutoAdvanceService`) catch this exception per-signer and log:

```
SIGNATURE EMAIL NOT SENT — contract=<id>, signer=<email>: <reason>
```

This means:
- **The contract IS in `IN_SIGNATURE`** and the `SignatureRequest` IS saved — the signing session is open
- **The email was not delivered** — the signer won't receive the link unless manually resent
- **The `markComplete` API still returns 200** — the approver's action completed successfully

### SMTP Configuration

```properties
# application.properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=priya@costacloud.com
spring.mail.password=<app-password>
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

**If emails are not being received**, check server logs for:
- `535 5.7.8 Username and Password not accepted` → Gmail app password expired or invalid. Regenerate at **Google Account → Security → 2-Step Verification → App Passwords**.
- `Connection refused` → SMTP host/port is wrong or firewall blocking.
- `535 5.7.14 ... web login required` → 2FA not enabled on the account; app passwords require 2FA.

---

## 14. Error Reference

All errors follow the global exception handler format:

```json
{
  "timestamp": "2026-07-01T12:00:00.000+00:00",
  "status":    400,
  "error":     "Bad Request",
  "message":   "uploadId is required — upload the edited PDF before marking complete.",
  "path":      "/contracts/abc123/flow/complete"
}
```

### Common Error Messages

| HTTP | Message | Cause |
|---|---|---|
| 404 | `Contract not found` | Contract ID does not exist or caller is not the owner |
| 400 | `Contract must be DRAFT or REJECTED to submit into the unified flow.` | Status is not DRAFT / REJECTED / REJECTED_BY_REVIEWER / REJECTED_BY_APPROVER |
| 400 | `At least one participant is required` | Empty participants list |
| 400 | `At least one APPROVER is required in the participant list` | Reviewers only |
| 400 | `Duplicate order numbers are not allowed` | Two participants with same order |
| 400 | `Duplicate participant emails are not allowed` | Same email twice |
| 400 | `You cannot assign yourself as a participant` | Owner email in participant list |
| 400 | `All REVIEWER orders must be lower than all APPROVER orders.` | Order constraint violated |
| 400 | `externalSigners list is required when externalSigningIncluded is true` | Missing externalSigners on submit |
| 400 | `Field edits are only allowed while the contract is IN_REVIEW or IN_APPROVAL.` | Wrong status for field edit |
| 400 | `You are not an assigned participant for this contract` | Email not in participants list |
| 400 | `It is not your turn yet, or you have already completed your action on this contract` | Status is pending/completed/rejected |
| 400 | `uploadId is required — upload the edited PDF before marking complete.` | Missing uploadId in complete |
| 400 | `parts list is required — complete the multipart upload before marking complete.` | Missing parts in complete |
| 400 | `Upload is only allowed during the review or approval stage.` | Upload called outside IN_REVIEW/IN_APPROVAL |
| 400 | `Part number must be between 1 and 10000` | Invalid partNumber |
| 400 | `uploadId is required` | Blank uploadId in abort |
| 400 | `Contract must be READY_FOR_SIGNATURE to send for external signature.` | Wrong status for send-for-signature |
| 400 | `Contract file must be uploaded before sending for signature` | fileUploaded = false |
| 400 | `Cannot send for external signature — the following org fields are still empty: CEO Name` | Org-gate failure |
| 400 | `Only external signers are allowed in the unified flow. Signer 'x@y.com' has type 'internal'.` | Internal signer in assignments |
| 400 | `uploadId is required — upload the edited PDF before marking complete.` | Reviewer missing uploadId (both roles must upload) |

---

## 15. End-to-End Integration Walkthrough

This walkthrough covers the complete happy path: reviewer + approver, with auto-trigger external signing.

> Replace `BASE_URL`, `OWNER_TOKEN`, `REVIEWER_TOKEN`, `APPROVER_TOKEN`, and `CONTRACT_ID` with actual values.

---

**Step 1 — Owner submits the contract (with auto-trigger)**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/submit \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "participants": [
      { "email": "reviewer@example.com", "name": "Alice", "role": "REVIEWER", "order": 1 },
      { "email": "approver@example.com", "name": "Bob",   "role": "APPROVER", "order": 2 }
    ],
    "externalSigningIncluded": true,
    "externalSigners": [
      { "email": "client@example.com", "name": "Client", "partyId": "p-ext-1", "partyLabel": "Client", "order": 1 }
    ],
    "senderName": "Priya from CostaCloud"
  }'
```

Contract is now `IN_REVIEW`. Alice is `unlocked`.

---

**Step 2 — Reviewer checks inbox**

```bash
curl -X GET $BASE_URL/contracts/flow/inbox \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
```

---

**Step 3 — Reviewer gets the PDF to review**

```bash
curl -X GET $BASE_URL/contracts/$CONTRACT_ID/flow/file-url \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
# Download and annotate the PDF
```

---

**Step 4 — Reviewer saves field edits (with XFDF)**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/fields \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fieldValues": { "companyName": "Acme Corp" },
    "xfdfData": "<?xml version=\"1.0\"?>..."
  }'
```

Alice's status moves to `in_progress`.

---

**Step 5 — Reviewer initiates upload**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/upload/initiate \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
# Response: { "uploadId": "REVIEWER_UPLOAD_ID" }
```

---

**Step 6 — Reviewer gets presigned URL and uploads PDF**

```bash
curl -X GET "$BASE_URL/contracts/$CONTRACT_ID/flow/upload/presign?uploadId=REVIEWER_UPLOAD_ID&partNumber=1" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
# Response: { "url": "PRESIGNED_URL", "partNumber": 1 }

curl -X PUT "PRESIGNED_URL" -H "Content-Type: application/pdf" --data-binary @reviewed-contract.pdf
# Save ETag from response header → REVIEWER_ETAG
```

---

**Step 7 — Reviewer marks complete**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/complete \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "uploadId": "REVIEWER_UPLOAD_ID",
    "parts":    [{ "partNumber": 1, "etag": "REVIEWER_ETAG" }],
    "comments": "Fields verified and updated"
  }'
```

Alice becomes `completed`. Bob is `unlocked`. Contract moves to `IN_APPROVAL`.

---

**Step 8 — Approver gets PDF (reviewer's updated version)**

```bash
curl -X GET $BASE_URL/contracts/$CONTRACT_ID/flow/file-url \
  -H "Authorization: Bearer $APPROVER_TOKEN"
# Returns the _signed.pdf uploaded by the reviewer
```

---

**Step 9 — Approver initiates upload**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/upload/initiate \
  -H "Authorization: Bearer $APPROVER_TOKEN"
# Response: { "uploadId": "APPROVER_UPLOAD_ID" }
```

---

**Step 10 — Approver uploads signed PDF**

```bash
curl -X GET "$BASE_URL/contracts/$CONTRACT_ID/flow/upload/presign?uploadId=APPROVER_UPLOAD_ID&partNumber=1" \
  -H "Authorization: Bearer $APPROVER_TOKEN"

curl -X PUT "PRESIGNED_URL" -H "Content-Type: application/pdf" --data-binary @signed-contract.pdf
# Save ETag → APPROVER_ETAG
```

---

**Step 11 — Approver marks complete (triggers external signing automatically)**

```bash
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/complete \
  -H "Authorization: Bearer $APPROVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "uploadId": "APPROVER_UPLOAD_ID",
    "parts":    [{ "partNumber": 1, "etag": "APPROVER_ETAG" }],
    "comments": "Signed by CFO"
  }'
```

Response `status` is `IN_SIGNATURE` — external signing auto-triggered. `client@example.com` receives the signing email.

---

**Manual path (if `externalSigningIncluded = false` or auto-trigger failed):**

```bash
# Owner checks org-gate
curl -X GET $BASE_URL/contracts/$CONTRACT_ID/flow/status \
  -H "Authorization: Bearer $OWNER_TOKEN"
# Verify orgFieldsComplete = true

# Owner sends manually
curl -X POST $BASE_URL/contracts/$CONTRACT_ID/flow/send-for-signature \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "assignments": [
      { "type": "external", "email": "client@example.com", "name": "Client",
        "partyId": "p-ext-1", "partyLabel": "Client", "order": 1 }
    ],
    "senderName": "Priya from CostaCloud"
  }'
```

---

## 16. Frontend Integration Guide

### 16.1 Detecting Which Flow a Contract Is In

| Field present | Flow |
|---|---|
| `participants[]` non-empty | Unified workflow |
| `reviewers[]` / `approver` set | Legacy workflow |

### 16.2 Rendering Participant List (Owner View)

Use `GET /contracts/{id}/flow/status` to get `participants[]`. Display a timeline using `order` for position and `status` for icon/color:

| Status | UI suggestion |
|---|---|
| `pending` | Grey dot — waiting |
| `unlocked` | Blue dot — active, waiting for action |
| `in_progress` | Yellow dot — working |
| `completed` | Green check — done |
| `rejected` | Red X — rejected |

### 16.3 Participant Inbox (Reviewer / Approver View)

On login, call `GET /contracts/flow/inbox` for contracts where the user has an active task.

### 16.4 Participant Sent Tab (Read-Only)

Call `GET /contracts/flow/sent` for contracts the user has already completed. These are read-only — do not show edit/complete/reject actions on contracts from this list.

### 16.5 Reviewer and Approver Editing Flow (Identical for Both Roles)

1. Confirm `status = IN_REVIEW` (for reviewer) or `IN_APPROVAL` (for approver) via `GET /contracts/{id}/flow/status`
2. Confirm participant's status is `unlocked` or `in_progress`
3. Load PDF via `GET /contracts/{id}/flow/file-url` — always returns the most recent version
4. Load form fields from `formFields[]` and XFDF from `xfdfData` in the status response
5. User edits fields and/or annotates the PDF
6. Save edits with `POST /contracts/{id}/flow/fields` (including `xfdfData` if annotations changed)
7. Initiate upload: `POST /contracts/{id}/flow/upload/initiate` → `uploadId`
8. For each chunk:
   - `GET /contracts/{id}/flow/upload/presign?uploadId=...&partNumber=N`
   - PUT chunk to presigned URL
   - Collect `ETag` from response header
9. Call `POST /contracts/{id}/flow/complete` with `uploadId`, `parts[]`, and optionally final `xfdfData`
10. On error or cancel: `POST /contracts/{id}/flow/upload/abort?uploadId=...`
11. To reject instead: `POST /contracts/{id}/flow/reject` with a message

### 16.6 Handling `markComplete` Response Status

After calling `/flow/complete`, check the response `status`:

| Response `status` | What to show |
|---|---|
| `IN_REVIEW` / `IN_APPROVAL` | "Submitted — waiting for next participant" |
| `READY_FOR_SIGNATURE` | "All internal work done — external signing pending" (owner may need to act) |
| `IN_SIGNATURE` | "External signing emails sent — contract is with the client" |

### 16.7 Owner View After All Approvers Complete

**If `externalSigningIncluded = true`:** The `markComplete` response from the last approver will already show `IN_SIGNATURE` — no action needed from the owner.

**If `externalSigningIncluded = false` or auto-trigger failed:**
1. Contract shows `READY_FOR_SIGNATURE`
2. Call `GET /contracts/{id}/flow/status` and check `orgFieldsComplete`
3. If `false`, show `unfilledOrgFields` — user needs to fill those in the contract editor
4. When all fields filled, call `POST /contracts/{id}/flow/send-for-signature`

### 16.8 Submit with External Signers (Owner)

When `externalSigningIncluded = true`, collect external signer details in the submit form:
- `email` (required)
- `name` (optional, shown in email)
- `partyId` and `partyLabel` — must match an EXTERNAL party on the contract
- `order` — signing sequence

These are stored and auto-used when the last approver completes. The owner does not need to provide them again.

### 16.9 Field Editing Rules for Participants

| Field type | Who can edit |
|---|---|
| INTERNAL party field | Any active participant |
| EXTERNAL party field | **Not editable by internal participants** — only the external signer fills these |
| Signature field | **Not editable by internal participants** — only the external signer fills these |

The `xfdfData` string represents the full annotation state. Save it on every `/flow/fields` call and include it in `/flow/complete`.

### 16.10 Rejection & Resubmit UI

When `contract.status` is `REJECTED_BY_REVIEWER`, `REJECTED_BY_APPROVER`, or `REJECTED`:
- Show an **"Update and Resubmit"** button instead of "Edit and Share"
- Read `contract.modificationRequests[]` — the last entry with `role = "reviewer"` or `role = "approver"` shows the rejection reason and who rejected
- Display the rejection reason to the owner so they know what needs to change

**Update and Resubmit flow (identical regardless of who rejected):**
1. Open the contract metadata dialog — pre-filled with current metadata so owner can update it
2. Open the PDF editor with the **original blank template** (from `templateId`) — not the old reviewed/signed copy
3. Owner fills the fresh template and saves via `PATCH /contracts/{id}` (metadata + `xfdfData` + `formFields`)
4. Owner uploads the filled PDF file
5. Open the unified flow dialog (same as contract creation)
6. Owner assigns a completely new set of participants (reviewers + approvers) and submits
7. Call `POST /contracts/{id}/flow/submit` with the full new participant list

Check the `status` field to determine which badge to show:

| `status` | Badge |
|---|---|
| `REJECTED_BY_REVIEWER` | "Rejected by Reviewer" |
| `REJECTED_BY_APPROVER` | "Rejected by Approver" |
| `REJECTED` | "Rejected" (legacy) |

---

## 17. Test Coverage

The unified workflow is covered by unit tests in `UnifiedWorkflowServiceTest.java`.

| Test Group | What It Covers |
|---|---|
| `submit — fresh DRAFT` | All submission validations, status transitions, participant ordering, externalSigners storage and validation, flag persistence |
| `submit — resubmit after rejection` | `REJECTED_BY_REVIEWER` full reset, `REJECTED_BY_APPROVER` full reset (no partial reset), legacy `REJECTED` accepted, `_signed.pdf` deleted from MinIO on resubmit, `signedPdfKey` cleared, mod request appended with role="contractor", externalSigners stored/cleared correctly |
| `saveFieldEdits` | Status transitions (unlocked → in_progress), field merging, xfdfData save, filledBy attribution, access control, wrong-status guard |
| `markComplete` | **Both reviewer and approver** must provide uploadId + parts; PDF finalized to `_signed.pdf`, `fileUploaded=true` set for both roles; advance engine (next order + READY_FOR_SIGNATURE); version increment on approver; signedPdfKey set; missing uploadId/parts errors for both roles; non-participant and pending-participant guards |
| `reject` | Reviewer → `REJECTED_BY_REVIEWER` status; approver → `REJECTED_BY_APPROVER` status; mod request role field; access control; wrong-status guard |
| `initiateUpload` | Reviewer in IN_REVIEW gets uploadId; approver in IN_APPROVAL gets uploadId; wrong status (READY_FOR_SIGNATURE) blocked; not found |
| `getPresignedPartUrl` | Part number bounds (0, 10001); reviewer in IN_REVIEW can presign; not found |
| `abortUpload` | Cancel called with correct args; blank/null uploadId validation; reviewer in IN_REVIEW can abort |
| `sendForSignature` | Happy path; non-owner 404; wrong status; file not uploaded; org-gate unfilled; internal signer type rejected; org-gate external party skip; no-form-fields pass; no-parties pass |
| `getFlowStatus` | Owner access; participant access; outsider 404; orgFieldsComplete=true/false; unfilled field label in response |
| `getFlowInbox` | Unlocked in inbox; in_progress in inbox; completed/pending/rejected excluded; multiple contracts filtered correctly; empty repo |
| `getFlowSent` | Completed in sent; **rejected in sent**; unlocked/in_progress/pending excluded; multiple contracts (completed + rejected included, active excluded); empty repo |

**Running the tests:**

```bash
# Set JAVA_HOME to JDK 25 first
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Run only unified workflow tests
mvn test -Dtest=UnifiedWorkflowServiceTest

# Run all tests
mvn test
```

---

*End of Unified Workflow Documentation — v2.0.0*
