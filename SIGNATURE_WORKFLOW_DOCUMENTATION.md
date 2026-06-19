# Contract Signature Workflow — Technical Documentation

**Version:** 2.4.0
**Last Updated:** 2026-06-19
**Base URL (Backend):** `http://localhost:8080`
**Base URL (Frontend):** `http://localhost:3000`
**Status:** Production Ready

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [System Architecture](#2-system-architecture)
3. [Authentication & Security Model](#3-authentication--security-model)
4. [Core Concepts & Terminology](#4-core-concepts--terminology)
5. [Signing Flows](#5-signing-flows)
6. [Signing Order Rules](#6-signing-order-rules)
7. [State Machines](#7-state-machines)
8. [Data Models](#8-data-models)
9. [File Storage Strategy (MinIO)](#9-file-storage-strategy-minio)
10. [Email Notifications](#10-email-notifications)
11. [Optimistic Locking](#11-optimistic-locking)
12. [API Reference](#12-api-reference)
13. [Error Reference](#13-error-reference)
14. [Frontend Integration Guide](#14-frontend-integration-guide)
15. [Environment Configuration](#15-environment-configuration)
16. [Development & Testing Guide](#16-development--testing-guide)
17. [Contract Detail Screen — API Reference](#17-contract-detail-screen--api-reference)

---

## 1. Introduction

### 1.1 What Is the Signature Workflow?

The signature workflow allows a contractor (the contract creator) to send a contract PDF to one or more parties for digital signatures. The system manages who signs, in what order, tracks progress, stores signed PDFs in MinIO, and notifies parties via email.

The entire workflow is built in **Spring Boot** on the backend. The **Next.js** frontend is a pure UI layer — it calls Spring Boot APIs and renders the signing interface. No business logic lives in the frontend.

### 1.2 What the System Does

- Sends contracts to external clients (non-users) via a unique, expiring email link
- Shows contracts to internal CMS users (registered users) in their inbox
- Enforces a sequential signing order — the next party only unlocks after the previous one completes
- Stores PDFs in MinIO: one in-progress cumulative file (overwritten after each signing), one final file created at the end
- Uses chunked presigned uploads — signed PDFs go **browser → MinIO directly**, bypassing Spring Boot and the Next.js proxy entirely
- Sends automated emails at each step (invitation, signed copy after finalization)
- Protects against concurrent modification conflicts via optimistic locking
- Supports a continuous linear signing chain — a contractor can add more parties at any time, always continuing the order sequence (new parties must always have an order greater than all existing ones)

### 1.3 Scope Boundaries

| In Scope | Out of Scope |
|---|---|
| Sending contracts for external signature via email token | Creating the PDF template |
| Internal signer flow via inbox page | Contractor editing contract content |
| Sequential signing order enforcement | DocuSign / third-party signature provider integration |
| Cumulative PDF accumulation across signers | Electronic signature legal validity (country-specific law) |
| Email notifications (invitation, signed copy) | SMS notifications |
| Continuous linear signing chain (re-share) | Bulk contract sending |
| Optimistic locking for concurrent access | Video verification / ID verification |
| Chunked presigned upload (browser → MinIO direct) | Multipart form-data upload through proxy |

---

## 2. System Architecture

### 2.1 High-Level Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              CLIENT SIDE                                  │
│                                                                           │
│  ┌──────────────────────┐          ┌──────────────────────────────────┐  │
│  │   Contractor / CMS   │          │   External Signer Page           │  │
│  │   User (Next.js)     │          │   /sign/[token]  (Next.js)       │  │
│  │                      │          │                                  │  │
│  │  - Share dialog      │          │  - PDF viewer + annotation       │  │
│  │  - Dashboard         │          │  - Field fill + submit           │  │
│  │  - Inbox             │          │  - Auto-save support             │  │
│  └──────────┬───────────┘          └────────────────┬─────────────────┘  │
│             │ JWT in header                          │ No JWT (token URL) │
└─────────────┼──────────────────────────────────────-┼────────────────────┘
              │                                        │
              │                  ┌─────────────────────┘
              │                  │ Presigned PUT (chunked parts)
              │                  │ goes DIRECTLY to MinIO
              │                  │ (bypasses Spring Boot + Next.js proxy)
              │                  ▼
              │         ┌─────────────────────┐
              │         │   MinIO (port 9000)  │
              │         │                     │
              │         │ contracts/{id}.pdf   │
              │         │ contracts/{id}       │
              │         │   _signed.pdf        │
              │         │ contracts/{id}       │
              │         │   _final.pdf         │
              │         └─────────────────────┘
              │                  ▲
              ▼                  │ MinIO SDK (server-to-server)
┌─────────────────────────────────────────────────────────────────────────┐
│                   SPRING BOOT BACKEND (port 8080)                        │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    SignatureController                           │    │
│  │                                                                  │    │
│  │  JWT-Protected (contractor & internal signer):                   │    │
│  │  POST /contracts/{id}/submit-for-signature                       │    │
│  │  GET  /contracts/{id}/signature-status                           │    │
│  │  POST /contracts/{id}/sign/upload/initiate                       │    │
│  │  GET  /contracts/{id}/sign/upload/presign                        │    │
│  │  POST /contracts/{id}/sign/upload/abort                          │    │
│  │  POST /contracts/{id}/internal-sign                              │    │
│  │  POST /contracts/{id}/finalize                                   │    │
│  │                                                                  │    │
│  │  Public (signing token is the credential):                       │    │
│  │  GET   /sign-requests/{token}                                    │    │
│  │  GET   /sign-requests/{token}/file-url                           │    │
│  │  POST  /sign-requests/{token}/upload/initiate                    │    │
│  │  GET   /sign-requests/{token}/upload/presign                     │    │
│  │  POST  /sign-requests/{token}/upload/complete                    │    │
│  │  POST  /sign-requests/{token}/upload/abort                       │    │
│  │  PATCH /sign-requests/{token}/viewed                             │    │
│  └──────────────────────┬──────────────────────────────────────────┘    │
│                         │                                                │
│  ┌──────────────────────▼──────────────────────────────────────────┐    │
│  │                    SignatureService                              │    │
│  │  - Order validation & re-share logic                            │    │
│  │  - Token generation (sig_{ts}_{uuid})                           │    │
│  │  - MinIO presigned URL generation (CustomMinioClient)           │    │
│  │  - Multipart upload initiate / finalize / abort                 │    │
│  │  - Optimistic locking (version check)                           │    │
│  │  - Calls AutoAdvanceService after each completion               │    │
│  └────────┬────────────────────────────┬────────────────────────────┘   │
│           │                            │                                 │
│  ┌────────▼──────────────┐  ┌──────────▼──────────────────────────┐     │
│  │  AutoAdvanceService   │  │         EmailService                 │     │
│  │  - State machine      │  │  - Signature request email           │     │
│  │  - Unlocks next order │  │  - Signed copy email (on finalize)   │     │
│  │  - Detects all done   │  │  - Non-fatal (try/catch)             │     │
│  └───────────────────────┘  └─────────────────────────────────────┘     │
│                                                                          │
│  ContractController also participates:                                   │
│  GET /contracts/{id}/file/view-url — serves _signed.pdf if it exists    │
└──────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────┐
│               MongoDB                     │
│                                          │
│  contracts collection:                   │
│  - externalSigners[]                     │
│  - internalSigners[]                     │
│  - partyCompletions[]                    │
│  - signatureFlowStatus                   │
│  - currentSigningOrder                   │
│  - version                               │
│                                          │
│  signature_requests collection:          │
│  - token (unique index)                  │
│  - contractVersion (locking)             │
│  - status, expiresAt                     │
└──────────────────────────────────────────┘
```

### 2.2 Technology Stack

| Component | Technology | Purpose |
|---|---|---|
| Backend | Spring Boot 4.x | REST API, business logic, email |
| Database | MongoDB | Contract and signature request storage |
| File Storage | MinIO | PDF storage (original, in-progress, final) |
| Email | Spring JavaMailSender (SMTP) | Signature invitation, signed copy emails |
| Auth | JWT (stateless) | Contractor and internal signer identity |
| Frontend | Next.js | UI rendering, PDF viewer |
| PDF Viewer | Apryse WebViewer | In-browser PDF annotation and signing |

### 2.3 Key Design Decision — Direct Browser-to-MinIO Upload

Signed PDFs (which can be 30–50 MB) are **never routed through Spring Boot or the Next.js proxy**. Instead:

1. Spring Boot generates a presigned MinIO URL for each chunk
2. The browser PUTs each chunk directly to MinIO using those URLs
3. Spring Boot finalizes the multipart upload via the MinIO SDK when all parts are uploaded

This eliminates the `ClientAbortException: EOFException` that occurs when the Next.js rewrite proxy drops large multipart request bodies before Tomcat can fully receive them.

---

## 3. Authentication & Security Model

### 3.1 Two Authentication Tiers

**Tier 1 — JWT Authentication (Contractor & Internal Signers)**

All contractor and internal signer endpoints require a valid JWT passed as a Bearer token in the `Authorization` header.

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

The JWT carries the caller's email address, which the backend extracts via `SecurityContextHolder.getContext().getAuthentication().getName()`. This email is used to:
- Verify the caller owns the contract (contractor operations)
- Verify the caller is an assigned internal signer (internal sign operations)

**Tier 2 — Token Authentication (External Signers)**

External signers have no CMS account and therefore no JWT. Their authentication is the **signing token** embedded in the email link they receive.

```
GET http://localhost:8080/sign-requests/sig_1718123456789_abc123def456
```

The token itself functions as the credential. It is:
- Cryptographically random (UUID-based, 40+ characters)
- Bound to a specific signer's email and contract
- Valid for 7 days from creation
- Single-use (invalidated after the signer submits their final signature)

### 3.2 Public Endpoints (No JWT Required)

The following endpoints are explicitly whitelisted in `SecurityConfig` to allow requests without JWT. The signing token in the URL path is the credential.

| Method | Path | Reason |
|---|---|---|
| `GET` | `/sign-requests/{token}` | External signer loads signing page data |
| `GET` | `/sign-requests/{token}/file-url` | Returns presigned MinIO URL for PDF download |
| `POST` | `/sign-requests/{token}/upload/initiate` | Starts chunked upload session |
| `GET` | `/sign-requests/{token}/upload/presign` | Gets presigned PUT URL for one chunk |
| `POST` | `/sign-requests/{token}/upload/complete` | Finalizes upload + records signature |
| `POST` | `/sign-requests/{token}/upload/abort` | Cancels upload on error |
| `PATCH` | `/sign-requests/{token}/viewed` | Marks link as opened |

All other endpoints require a valid JWT.

### 3.3 Token Security Properties

| Property | Value |
|---|---|
| Format | `sig_{Unix timestamp ms}_{UUID without hyphens}` |
| Example | `sig_1718123456789_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6` |
| Length | ~50 characters |
| Uniqueness | `@Indexed(unique = true)` on `signature_requests.token` |
| Expiry | 7 days from creation (`expiresAt = createdAt + 7 days`) |
| Revocation | Automatic after final submit (`status = "signed"`) |

---

## 4. Core Concepts & Terminology

| Term | Definition |
|---|---|
| **Contractor** | The CMS user who created the contract and initiates the signing workflow |
| **External Signer** | A person outside the CMS (no account) who signs via a unique, expiring email link |
| **Internal Signer** | A registered CMS user who signs via their inbox page |
| **Signing Order** | A 1-based integer that determines when a party signs. Each party has a unique order. |
| **Auto-Advance** | The server-side process that unlocks the next signer after the current one completes |
| **Re-Share** | When the contractor submits for signature again on a contract where the chain is still in progress or has completed. New signers must always continue the order sequence at a number greater than all existing signers. |
| **Token** | A unique, unguessable string that identifies one external signer's signing session |
| **Signature Request** | A MongoDB document created per external signer — stores their token, contract snapshot, and version |
| **Flow Status** | The current state of the signature workflow (`pending_signatures`, `all_completed`, `finalized`) |
| **Version** | An integer on the contract that increments after each signer completes — used for conflict detection |
| **Finalization** | The contractor's action after all parties have signed — creates the final PDF and distributes it |
| **Party** | A role defined in the contract template (e.g., "Buyer", "Seller", "Witness") |
| **Party Completion** | A denormalized record tracking the completion status of each party assignment |
| **Signing Round** | *(Vestigial — retained in data models for backward compatibility with existing documents, but no longer used in any business logic. All signing order decisions are now based on the globally unique `order` integer across the entire contract lifetime.)* |
| **Presigned URL** | A time-limited MinIO URL that authorises one specific HTTP operation (GET or PUT) without credentials |
| **Chunked Upload** | Splitting a large file into parts and uploading each part separately via presigned PUT URLs directly to MinIO |
| **uploadId** | The MinIO multipart upload session identifier — returned by initiate, required for presign and complete |

---

## 5. Signing Flows

There are three supported signing flows, determined by the types of parties assigned.

### 5.1 All-External Flow

All signers are external clients with no CMS account.

```
Contractor submits
      │
      ▼
  Order 1 signer → Email sent immediately → Signer opens link → Signs → Submits
                                                                              │
                                                                    Auto-advance fires
                                                                              │
                                                                              ▼
                                                          Order 2 signer → Email sent → Signs → Submits
                                                                                                     │
                                                                                           signatureFlowStatus
                                                                                           = "all_completed"
                                                                                                     │
                                                                                           Contractor finalizes
                                                                                                     │
                                                                                           Final PDF emailed to all
```

**Key behaviour:** The next signer's email is triggered automatically by the Spring Boot server (not the browser). If the contractor closes their browser, the workflow continues uninterrupted.

### 5.2 All-Internal Flow

All signers are registered CMS users.

```
Contractor submits
      │
      ▼
  Order 1 internal user → Sees contract in inbox (status = "unlocked")
                                      │
                              Opens inbox, signs, submits via API
                                      │
                                Auto-advance fires
                                      │
                                      ▼
                         Order 2 internal user → Appears in their inbox → Signs
                                                                               │
                                                                     signatureFlowStatus
                                                                     = "all_completed"
                                                                               │
                                                                     Contractor finalizes
```

**Key behaviour:** No emails are sent to internal signers. They discover their assignment by loading the inbox page (`GET /contracts/inbox`), which returns all contracts where they are a reviewer, approver, or internal signer with `status = "unlocked"`.

### 5.3 Mixed Flow (Internal + External)

Both internal and external parties are involved, each at their own order.

```
Example: party_1 = external (order 1), party_2 = internal (order 2)

Contractor submits
      │
      ▼
  External party_1 → Email sent → Signs at /sign/{token}
                                              │
                                    Auto-advance fires
                                              │
                                              ▼
                              Internal party_2 → Appears in inbox → Signs
                                                                         │
                                                               signatureFlowStatus
                                                               = "all_completed"
```

**Key behaviour:** Internal and external signers at different orders are unlocked and notified differently — external via email, internal via inbox query — but the order sequencing logic is identical.

---

## 6. Signing Order Rules

The signing order is the central control mechanism of the workflow. These rules are strictly enforced by the backend.

### 6.1 Uniqueness Rule

**Every signer must have a unique order number.** No two parties — internal or external — can share the same order in any active set of assignments.

```
400 Bad Request: "Order number 1 is assigned to more than one party in this submission.
Each signer must have a unique order."
```

### 6.2 Sequential Advancement

The workflow advances strictly one order at a time:

```
currentSigningOrder = 1 (active)
    │
    │  order 1 signer completes
    ▼
AutoAdvanceService fires:
    - finds next order > 1 across all signers
    - unlocks that signer
    - sets currentSigningOrder = 2
    │
    │  order 2 signer completes
    ▼
AutoAdvanceService fires again → order 3 unlocks, and so on
```

### 6.3 First Submission Rules

When no signers exist on the contract yet:
- The lowest order in the submission **must be 1**. Starting at 2 or any other number is rejected.
- All orders must start at 1, with no duplicates within the submission.

### 6.4 Re-Share Order Calculation

Orders are **globally unique across the entire contract lifetime** — there is no concept of "rounds" restarting at order 1. When the contractor adds new signers to an existing contract, the new signers must always continue the chain at a number greater than all existing signers.

The backend determines which case applies automatically based on whether the existing chain is still active.

---

**Case A — In-Progress Chain: Append at the End (`appendOnly` mode)**

If any signer has status `pending`, `unlocked`, or `viewed`, the chain is still active. New signers are **appended** beyond the current chain — they never unlock immediately.

```
Existing chain: party_1 (order 1, completed), party_2 (order 2, unlocked)

hasInProgress = true
maxExistingOrder = 2

New assignment validation:
  ✓  order 3 → allowed (3 > 2) — P3 added with status "pending"
  ✓  order 4 → allowed (4 > 2)
  ✗  order 2 → rejected: "Order 2 conflicts with an in-progress signer. New signers must have order greater than 2."
  ✗  order 1 → rejected: same reason
```

**Critical behavior (`appendOnly = true`):**
- All new signers get `status = "pending"` — **never `"unlocked"`**, even the first new one
- `currentSigningOrder` is **not changed** — it stays where the active chain is
- AutoAdvanceService will unlock them naturally when the chain reaches their order

---

**Case B — All Completed: Continue the Chain**

If all existing signers have completed, the contractor can extend the signing chain by assigning new parties at orders greater than all existing orders. The new signer at the lowest assigned order unlocks **immediately** — no auto-advance needed.

```
Existing chain: P1(order=1), P2(order=2), P3(order=3), P4(order=4) — all completed
maxExistingOrder = 4

Re-share for P5:
  ✓  order 5 → allowed (5 > 4)
     P5 gets: order=5, status="unlocked" immediately
     currentSigningOrder = 5
     contract.status = IN_SIGNATURE

  ✗  order 4 → rejected: "Order 4 is already used. New signers must continue the chain at an order greater than 4."
  ✗  order 1 → rejected: same reason
```

**Key difference from Case A:** When `hasInProgress = false` (all completed), `appendOnly` stays `false` — so the new signer at the starting order gets `"unlocked"` immediately and `currentSigningOrder` advances to their order.

---

**Blocked: No Unassigned Parties Remain**

If all existing signers completed AND every party defined on the contract already has a signer assigned, the workflow is fully done. No new signers can be added.

```
Contract has parties: P1, P2 (both assigned and completed, no unassigned parties left)

✗ Rejected: "Cannot add signers — the contract has already completed the signing workflow"
```

---

## 7. State Machines

### 7.1 Contract Status Lifecycle

```
DRAFT
  │
  │  submit for review
  ▼
IN_REVIEW ──── [reviewer completes] ──── IN_APPROVAL ──── [approver approves] ──┐
  │                                                                               │
  │  (or direct, no workflow)                                                     │
  └───────────────────────────────────────────────────────────────────────────┐  │
                                                                              │  │
                                                                              ▼  ▼
                                                                    READY_FOR_SIGNATURE
                                                                              │
                                                              contractor submits-for-signature
                                                                              │
                                                                              ▼
                                                                        IN_SIGNATURE
                                                                              │
                                                             auto-advance: all signers done
                                                                              │
                                                                              ▼
                                                                    SIGNED_BY_EVERYONE
                                                                              │
                                                    ┌─────────────────────────┤
                                                    │                         │
                                         unassigned parties            all parties have
                                         still exist?                  a signer assigned?
                                                    │                         │
                                                    ▼                         ▼
                                              contractor re-shares    contractor clicks Finalize
                                              (linear chain continues:         │
                                               new orders > maxExisting)       ▼
                                                    │                        SIGNED
                                                    └──── back to ───────────┘
                                                         IN_SIGNATURE
```

### 7.2 signatureFlowStatus Values

| Value | Meaning | When Set |
|---|---|---|
| `null` | Signature workflow not yet started | Before first submit-for-signature |
| `"pending_signatures"` | Workflow active — at least one signer has not yet completed | Set on submit-for-signature, preserved on re-share |
| `"all_completed"` | All assigned signers have completed — awaiting contractor finalization | Set by AutoAdvanceService when no next order exists |
| `"finalized"` | Contractor has finalized the contract — final PDF distributed | Set on POST /contracts/{id}/finalize |

### 7.3 External Signer Status Transitions

```
pending
  │
  │  [auto-advance: this signer's order becomes currentSigningOrder]
  ▼
unlocked ──── [signer opens /sign/{token} link] ──── viewed
  │                                                    │
  └────────────────────────────────────────────────────┘
                          │
               [signer submits final signature]
                          │
                          ▼
                       completed
```

| Status | Meaning |
|---|---|
| `pending` | This signer's order has not been reached yet |
| `unlocked` | It is this signer's turn — they received their email, can sign now |
| `viewed` | Signer opened the email link (non-blocking) |
| `completed` | Signer submitted their final signature |

### 7.4 Internal Signer Status Transitions

```
pending
  │
  │  [auto-advance: this signer's order becomes currentSigningOrder]
  ▼
unlocked  ← contract appears in their inbox now
  │
  │  [signer completes chunked upload + calls POST /contracts/{id}/internal-sign]
  ▼
completed
```

Internal signers have no `viewed` state. The inbox query includes contracts where `internalSigners[].email` matches the logged-in user and status is `"unlocked"`.

### 7.5 SignatureRequest Status

| Status | Meaning |
|---|---|
| `pending` | Token is valid and awaiting the signer's action |
| `signed` | Signer submitted their final signature — token is now invalid |

Expiry is checked live by comparing `expiresAt` to `LocalDateTime.now()` — there is no stored `expired` status.

---

## 8. Data Models

### 8.1 ExternalSigner (embedded in Contract)

| Field | Type | Required | Description |
|---|---|---|---|
| `email` | `String` | Yes | Signer's email address |
| `name` | `String` | No | Display name used in email greeting |
| `partyId` | `String` | Yes | Party identifier (e.g., `"party_1"`) |
| `partyLabel` | `String` | Yes | Human-readable label (e.g., `"Buyer"`) |
| `order` | `int` | Yes | Signing sequence number — globally unique across the entire contract lifetime |
| `signingRound` | `int` | — | **Vestigial.** Retained in the data model for backward compatibility with existing MongoDB documents. Not used in any business logic. New documents will have this set to `0` or `1`; it carries no meaning. |
| `token` | `String` | Yes | Unique signing token — used to build the signing URL |
| `status` | `String` | Yes | `pending` \| `unlocked` \| `viewed` \| `completed` |
| `sentAt` | `LocalDateTime` | Yes | When this entry was created |
| `unlockedAt` | `LocalDateTime` | No | When auto-advance unlocked this signer |
| `viewedAt` | `LocalDateTime` | No | When signer opened the link |
| `completedAt` | `LocalDateTime` | No | When signer submitted their final signature |

### 8.2 InternalSigner (embedded in Contract)

| Field | Type | Required | Description |
|---|---|---|---|
| `userId` | `String` | No | CMS user ID — display only |
| `email` | `String` | Yes | CMS user's email — verified against JWT on sign |
| `name` | `String` | No | Display name |
| `partyId` | `String` | Yes | Party identifier |
| `partyLabel` | `String` | Yes | Human-readable label |
| `order` | `int` | Yes | Signing sequence number — globally unique across the entire contract lifetime |
| `signingRound` | `int` | — | **Vestigial.** Same as `ExternalSigner.signingRound` — retained for backward compatibility only. Not used in any business logic. |
| `status` | `String` | Yes | `pending` \| `unlocked` \| `completed` |
| `assignedAt` | `LocalDateTime` | Yes | When assigned |
| `unlockedAt` | `LocalDateTime` | No | When auto-advance unlocked this order |
| `completedAt` | `LocalDateTime` | No | When signer submitted |

### 8.3 PartyCompletion (embedded in Contract)

A denormalized, unified record that tracks one party's assignment and completion status. One entry per party. Used by the frontend to render signing progress without merging two separate lists.

| Field | Type | Description |
|---|---|---|
| `partyId` | `String` | Party identifier |
| `partyLabel` | `String` | Human-readable label |
| `order` | `int` | Signing order |
| `assigneeType` | `String` | `"internal"` or `"external"` |
| `assigneeEmail` | `String` | Email of the assigned person |
| `assigneeName` | `String` | Display name |
| `status` | `String` | `pending` \| `unlocked` \| `completed` |
| `completedBy` | `String` | Email of who completed |
| `completedAt` | `LocalDateTime` | When completed |

### 8.4 SignatureRequest (separate collection: `signature_requests`)

One document per external signer. The `token` field has a unique MongoDB index.

> **Important — Lazy Creation:** `SignatureRequest` documents are **not** created for all external signers at `submit-for-signature` time. They are created on-demand:
> - **Starting-order external signers** → document created immediately in `SignatureService.submitForSignature()`
> - **Higher-order external signers** → token is stored in `ExternalSigner.token` inside the contract, but the `SignatureRequest` document does **not** exist yet. It is created by `AutoAdvanceService.advance()` at the moment that signer's order is unlocked.
>
> This means: if you query `signature_requests` by token for a signer who is still `pending`, the document will not be there yet — that is expected. The document is guaranteed to exist only once the signer's status is `"unlocked"` or `"signed"`.

| Field | Type | Description |
|---|---|---|
| `id` | `String` | MongoDB `_id` |
| `token` | `String` | Unique signing token — `@Indexed(unique=true)` |
| `contractId` | `String` | Reference to the parent contract |
| `contractTitle` | `String` | Snapshot of contract title at creation |
| `signerEmail` | `String` | This signer's email |
| `signerName` | `String` | This signer's display name |
| `createdBy` | `String` | Contractor's email |
| `createdByName` | `String` | Contractor's display name |
| `createdAt` | `LocalDateTime` | When this request was created |
| `expiresAt` | `LocalDateTime` | `createdAt + 7 days` |
| `status` | `String` | `pending` \| `signed` |
| `signedAt` | `LocalDateTime` | When signer submitted final signature |
| `assignedParty` | `List<String>` | Party IDs assigned to this signer — stored as a list internally. **Returned as a single `String` (not a list) in the `GET /sign-requests/{token}` response** because the frontend expects a scalar and wraps it in an array itself. Always contains exactly one element (one signer = one party). |
| `assignedPartyLabel` | `List<String>` | Party labels |
| `order` | `int` | Signing order for this signer |
| `formFields` | `List<Map>` | Snapshot of contract form fields at creation |
| `xfdfData` | `String` | Pre-filled XFDF from contractor (for initial load only) |
| `fieldValues` | `Map<String, String>` | Pre-filled field values from contractor |
| `contractVersion` | `int` | Contract version — used for optimistic locking on submit |

> **Note:** `xfdfData` in `SignatureRequest` is the contractor's pre-filled annotations sent to the signer on page load. It is **never** updated after the signer submits — the signed PDF in MinIO is the authoritative source for all post-signing annotation state. Storing large XFDF strings (which can embed signature images) in MongoDB would risk exceeding the 16 MB BSON document limit.

### 8.5 Contract — Signature Fields

| Field | Type | Description |
|---|---|---|
| `externalSigners` | `List<ExternalSigner>` | All external signer assignments across all rounds |
| `internalSigners` | `List<InternalSigner>` | All internal signer assignments across all rounds |
| `partyCompletions` | `List<PartyCompletion>` | Unified completion tracking per party |
| `signatureFlowStatus` | `String` | Current workflow state (see Section 7.2) |
| `currentSigningOrder` | `Integer` | Which order is currently active. `null` when all done. |
| `version` | `int` | Incremented after each signer completes — for optimistic locking |
| `signingRound` | `int` | **Vestigial.** Retained for backward compatibility with existing MongoDB documents. Not used in any business logic — `AutoAdvanceService` no longer filters by round. New submissions leave this unchanged. |
| `signatureSenderName` | `String` | Contractor's display name — stored for use in auto-advance emails |
| `signedPdfKey` | `String` | MinIO object key of in-progress signed PDF |
| `finalPdfKey` | `String` | MinIO object key of the finalized PDF |

---

## 9. File Storage Strategy (MinIO)

### 9.1 Object Key Naming Convention

| PDF Type | MinIO Object Key | Content |
|---|---|---|
| Original | `contracts/{contractId}.pdf` | The PDF the contractor uploaded — never overwritten |
| In-Progress Signed | `contracts/{contractId}_signed.pdf` | Accumulates each signer's work — overwritten after every completion |
| Final | `contracts/{contractId}_final.pdf` | Created by copying `_signed.pdf` when contractor finalizes |

### 9.2 Cumulative PDF Accumulation

**This is the most important invariant of the file storage strategy.**

Each signer receives the PDF that contains all previous signers' work. When they sign and export, the output PDF contains all prior annotations plus their own. The backend saves this as the new `_signed.pdf`, overwriting the previous version.

| Step | Signer loads from MinIO | Signer adds | Backend saves to |
|---|---|---|---|
| Signer 1 | `contract.pdf` (original — no `_signed.pdf` yet) | S1 signatures + fields | `contract_signed.pdf` (created) |
| Signer 2 | `contract_signed.pdf` (contains S1) | S2 signatures + fields | `contract_signed.pdf` (overwrites — now S1+S2) |
| Signer 3 | `contract_signed.pdf` (contains S1+S2) | S3 signatures + fields | `contract_signed.pdf` (overwrites — now S1+S2+S3) |
| Signer N | `contract_signed.pdf` (all prior signers) | SN work | `contract_signed.pdf` (final cumulative) |

At the end, there is still only one `_signed.pdf` — the complete document with everyone's work.

### 9.3 PDF URL Logic — Which File Is Served

Both PDF-serving endpoints use the same fallback logic:

```
if contracts/{contractId}_signed.pdf EXISTS in MinIO:
    return presigned URL for _signed.pdf   ← all prior signers' work included
else:
    return presigned URL for contracts/{contractId}.pdf   ← first signer, no prior annotations
```

This applies to:
- `GET /sign-requests/{token}/file-url` — external signer signing page
- `GET /contracts/{id}/file/view-url` — internal signer inbox viewer + contract owner detail page

The presigned URL is valid for **15 minutes**. The browser fetches the PDF directly from MinIO using this URL — no bytes are routed through Spring Boot.

### 9.4 Chunked Presigned Upload — How It Works

Signed PDFs go **directly from the browser to MinIO** via a 4-step flow. Spring Boot acts as a coordinator, not a data pipe.

```
Step 1 — Initiate (Spring Boot → MinIO SDK)
  POST /sign-requests/{token}/upload/initiate
  → Spring Boot calls customMinioClient.startMultipartUpload(bucket, objectKey)
  → Returns: { "uploadId": "some-minio-multipart-id" }

Step 2 — Presign each part (Spring Boot → returns URL)
  GET /sign-requests/{token}/upload/presign?uploadId=X&partNumber=N
  → Spring Boot calls minioClient.getPresignedObjectUrl() for that part
  → Returns: { "url": "http://localhost:9000/...?uploadId=X&partNumber=N&X-Amz-Signature=...", "partNumber": N }

Step 3 — Upload parts directly to MinIO (Browser → MinIO, NO Spring Boot involved)
  PUT {presignedUrl}  [browser sends chunk bytes directly to MinIO]
  → MinIO returns ETag header for each part — browser must save these

Step 4 — Complete (Spring Boot finalizes + records signature)
  POST /sign-requests/{token}/upload/complete
  Body: { uploadId, parts: [{partNumber, eTag}, ...], fieldValues, formFields, autoSave }
  → Spring Boot calls customMinioClient.finishMultipartUpload()
  → Runs all signing business logic (version check, mark signed, auto-advance, etc.)
```

The same 4-step pattern is used for both external signers (`/sign-requests/{token}/upload/*`) and internal signers (`/contracts/{id}/sign/upload/*` + `/contracts/{id}/internal-sign`).

### 9.5 Recommended Chunk Size

Split the PDF into **10 MB chunks**. MinIO requires each part (except the last) to be at least 5 MB.

```javascript
const CHUNK_SIZE = 10 * 1024 * 1024; // 10 MB per part
```

### 9.6 Auto-Save vs Final Submit

Controlled by `autoSave` field in the `POST .../upload/complete` request body.

| Behaviour | Auto-Save (`autoSave: true`) | Final Submit (`autoSave: false`) |
|---|---|---|
| MinIO upload completed | Yes (if parts provided) | Yes (required) |
| `SignatureRequest.status` | Unchanged (`pending`) | Set to `signed` |
| `ExternalSigner.status` | Unchanged | Set to `completed` |
| `contract.version` incremented | No | Yes |
| Auto-advance triggered | No | Yes |
| Response message | `"Progress saved"` | `"Signature submitted successfully"` |
| Version conflict check | No | Yes (409 if mismatch) |

### 9.7 Finalization

```
POST /contracts/{id}/finalize

1. Read contracts/{contractId}_signed.pdf from MinIO
2. Write identical bytes to contracts/{contractId}_final.pdf
3. Update contract.finalPdfKey = "contracts/{contractId}_final.pdf"
4. Send signed copy email to every external signer
5. Send signed copy email to every internal signer
6. Update contract.status = SIGNED, signatureFlowStatus = "finalized"
```

---

## 10. Email Notifications

### 10.1 SMTP Configuration

Emails are sent via Spring's `JavaMailSender` using SMTP. All SMTP credentials are injected from environment variables or `application.properties`.

```properties
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

### 10.2 Email Format — HTML with Plain-Text Fallback

All emails are sent as `multipart/alternative` MIME messages. Each message contains:
- **HTML part** — fully styled email rendered by the recipient's mail client (Gmail, Outlook, Apple Mail, etc.)
- **Plain-text part** — fallback for clients that do not support HTML

All HTML uses **inline CSS only** — no external stylesheets or `<style>` blocks — because most email clients (including Gmail) strip `<head>` styles. Inline styles are the only reliable way to achieve consistent rendering across all clients.

The HTML template structure for both emails:

| Region | Description |
|---|---|
| Header | Teal gradient (`#0e7c6b → #14967c`) with white title text |
| Body | White background, left-padded content area |
| Contract card | Light grey card (`#f8fafb`) with colored left border |
| Party badge | Outlined badge inside the card: `Assigned Party : {partyLabel}` |
| CTA button | Teal button linking to the signing URL or download URL |
| Expiry notice | Amber box (`#fffbeb`) with clock icon and expiry date |
| Footer | Light grey strip with system attribution text |

---

### 10.3 Email 1 — Signature Request (to external signer)

**Trigger:** When an external signer's order is unlocked — either at initial submit-for-signature (for the starting-order signer), or when `AutoAdvanceService.advance()` fires for that signer's order. In both cases, the `SignatureRequest` document is created at the same time the email is sent — never before.

| Field | Value |
|---|---|
| From | `{spring.mail.username}` |
| Reply-To | `{contractorEmail}` — replies go directly to the contractor |
| To | `{signerEmail}` |
| Subject | `"{contractTitle}" is ready for your signature` |

**HTML Visual Layout:**

```
┌──────────────────────────────────────────────┐
│         Document Signature Request           │  ← teal gradient header
├──────────────────────────────────────────────┤
│ Hello,                                       │
│                                              │
│ You have been requested to review and sign   │
│ the following document:                      │
│                                              │
│  ╔══ (partyColor border) ════════════════╗   │
│  ║  [Assigned Party : Buyer]             ║   │  ← colored badge, border matches party color
│  ║  ContractTitle                        ║   │
│  ║  Requested by: contractor@email.com   ║   │
│  ║  Sent on: Tuesday, June 16, 2026      ║   │
│  ╚═══════════════════════════════════════╝   │
│                                              │
│  [  Review & Sign Document  ]                │  ← teal button
│                                              │
│  ⏰ This link will expire on: {date}         │  ← amber notice
│                                              │
│  (footer note about replies going to sender) │
├──────────────────────────────────────────────┤
│  CostaCloud Contract Management | automated  │  ← footer
└──────────────────────────────────────────────┘
```

**Party color resolution:** `EmailService` receives `partyLabel` and `partyColor` from the caller. The color is resolved in both `SignatureService.submitForSignature()` and `AutoAdvanceService.advance()` by looking up `contract.getParties()` stream-filtered by `partyId`, falling back to `#0e7c6b` (teal) if the party has no color configured.

**Plain-text fallback (sent alongside HTML):**
```
Hi {signerName},

{contractorName} has sent you a contract for your digital signature.

Party    : {partyLabel}
Contract : {contractTitle}
Sent by  : {contractorName} ({contractorEmail})
Expires  : {expiresAt formatted as DD MMM YYYY}

Review & Sign: {baseUrl}/sign/{token}

This link expires on {expiresAt}. After signing, you will receive a copy
of the signed document by email.

If you have questions, reply to this email — replies go directly to {contractorName}.
```

---

### 10.4 Email 2 — Signed Copy (to all signers after finalization)

**Trigger:** Contractor calls `POST /contracts/{id}/finalize`. Sent to every external signer and every internal signer.

| Field | Value |
|---|---|
| From | `{spring.mail.username}` |
| To | `{signerEmail}` |
| Subject | `Signed copy: "{contractTitle}"` |

**HTML Visual Layout:**

```
┌──────────────────────────────────────────────┐
│  ✓   Document Fully Signed                   │  ← teal gradient header + checkmark
├──────────────────────────────────────────────┤
│ Hi {signerName},                             │
│                                              │
│ All parties have signed "{contractTitle}".   │
│ A copy of the fully signed document is       │
│ ready for download.                          │
│                                              │
│  ╔══ (teal border) ══════════════════════╗   │
│  ║  ContractTitle                        ║   │
│  ║  Status: Fully Executed               ║   │
│  ╚═══════════════════════════════════════╝   │
│                                              │
│  [ ⬇  Download Signed Document  ]            │  ← teal button
│                                              │
│  ⏰ Download link is valid for 7 days.       │  ← amber notice
│                                              │
│  Please save a copy for your records.        │
├──────────────────────────────────────────────┤
│  CostaCloud Contract Management | automated  │
└──────────────────────────────────────────────┘
```

**Plain-text fallback:**
```
Hi {signerName},

All parties have signed "{contractTitle}".
A copy of the fully signed document is available at the link below.

Download: {baseUrl}/contracts/{contractId}/final-pdf

This link is valid for 7 days.
```

> **Note:** There is **no** automatic email sent to the contractor when all parties have signed. The contractor discovers that signing is complete by checking the signature status (`GET /contracts/{id}/signature-status`) or noticing the contract status changed to `SIGNED_BY_EVERYONE` in their dashboard.

---

### 10.5 Email Failure Handling

**Email failures are non-fatal.** All email methods are wrapped in `try/catch`. If email delivery fails:
- The error is logged at `ERROR` level via SLF4J
- The signing operation is **not** rolled back
- The API response is still `200 OK`
- The contract state is still updated correctly

This ensures a temporary SMTP outage does not block the signing workflow. During development, use Mailtrap to capture emails without real delivery (see Section 16).

---

## 11. Optimistic Locking

### 11.1 Why It Is Needed

The in-progress PDF (`_signed.pdf`) is updated by each signer in turn. If two signers somehow submitted simultaneously, the second upload would silently overwrite the first signer's work. Optimistic locking detects and rejects this.

### 11.2 How It Works

Every `SignatureRequest` document stores a `contractVersion` — the version of the contract at the time the signing request was created (or last synced).

Every time a signer **completes** (not auto-save), `contract.version` is incremented by 1.

On final submit (`autoSave: false`), the server compares:

```java
if (sr.getContractVersion() != contract.getVersion()) {
    throw new ConflictException(
        "Contract has been modified since you started signing. Please reload and try again."
    );
    // → HTTP 409 Conflict
}
```

### 11.3 Version Increment Events

| Event | Version Change |
|---|---|
| External signer submits final | `+1` |
| Internal signer completes | `+1` |
| Auto-save | No change |
| Contractor edits contract metadata | No change |
| Signature request created | No change |
| First submission | Resets to `0` |

### 11.4 Version Synchronization

After each signing completion, the backend syncs the new version to all remaining `pending` `SignatureRequest` documents:

```java
mongoTemplate.updateMulti(
    Query.query(Criteria.where("contractId").is(contractId).and("status").is("pending")),
    Update.update("contractVersion", newVersion),
    SignatureRequest.class
);
```

Auto-save also syncs `contractVersion` in the current signer's `SignatureRequest` to the live contract version (without incrementing it), preventing a false 409 on their eventual final submit.

---

## 12. API Reference

### Overview

| # | Method | Endpoint | Auth | Description |
|---|---|---|---|---|
| 1 | `POST` | `/contracts/{id}/submit-for-signature` | JWT | Start or extend the signing workflow |
| 2 | `GET` | `/contracts/{id}/signature-status` | JWT | Get full signing progress |
| 3 | `POST` | `/contracts/{id}/sign/upload/initiate` | JWT | Start chunked upload session (internal signer) |
| 4 | `GET` | `/contracts/{id}/sign/upload/presign` | JWT | Get presigned URL for one chunk (internal signer) |
| 5 | `POST` | `/contracts/{id}/sign/upload/abort` | JWT | Abort upload on error (internal signer) |
| 6 | `POST` | `/contracts/{id}/internal-sign` | JWT | Finalize upload + submit internal signature |
| 7 | `POST` | `/contracts/{id}/finalize` | JWT | Contractor finalizes after all signed |
| 8 | `GET` | `/contracts/{id}/file/view-url` | JWT | Get presigned URL to view current PDF (owner, reviewer, approver, internal signer) |
| 9 | `GET` | `/sign-requests/{token}` | None | Load signing page data |
| 10 | `GET` | `/sign-requests/{token}/file-url` | None | Get presigned MinIO URL for PDF download |
| 11 | `POST` | `/sign-requests/{token}/upload/initiate` | None | Start chunked upload session (external signer) |
| 12 | `GET` | `/sign-requests/{token}/upload/presign` | None | Get presigned URL for one chunk (external signer) |
| 13 | `POST` | `/sign-requests/{token}/upload/complete` | None | Finalize upload + submit or auto-save |
| 14 | `POST` | `/sign-requests/{token}/upload/abort` | None | Abort upload on error |
| 15 | `PATCH` | `/sign-requests/{token}/viewed` | None | Mark link as opened |

---

### 12.1 POST `/contracts/{id}/submit-for-signature`

Initiates the signature workflow (first call) or adds new signers to an existing workflow (re-share).

**Authentication:** JWT. Caller must be the contract owner.

**Preconditions:**
- Contract status is `READY_FOR_SIGNATURE` or `IN_SIGNATURE`, **OR** status is `SIGNED_BY_EVERYONE` with unassigned parties still remaining
- Contract file is uploaded (`fileUploaded == true`)
- If status is `SIGNED_BY_EVERYONE` and **all** parties already have a signer assigned, the request is rejected (workflow is genuinely complete)

**Request Body:**

```json
{
  "assignments": [
    {
      "partyId": "party_1",
      "partyLabel": "Buyer",
      "type": "external",
      "email": "buyer@client.com",
      "name": "John Doe",
      "order": 1
    },
    {
      "partyId": "party_2",
      "partyLabel": "Seller",
      "type": "internal",
      "email": "alice@company.com",
      "userId": "user_abc123",
      "order": 2
    }
  ],
  "senderName": "Priya Sharma"
}
```

**Assignment Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `partyId` | `String` | Yes | Party ID from the template definition |
| `partyLabel` | `String` | Yes | Human-readable party label |
| `type` | `String` | Yes | `"external"` or `"internal"` |
| `email` | `String` | Yes | Signer's email |
| `name` | `String` | No | Display name used in emails |
| `userId` | `String` | No | CMS user ID (internal signers, display only) |
| `order` | `int` | Yes | Signing sequence. Globally unique across all signers on the contract. First submission must start at 1. Re-share orders must exceed all existing signers' orders. |

**What Happens Internally:**

1. Ownership and status validation
2. Per-assignment and cross-assignment validation (email format, duplicates, self-assignment)
3. Re-share scenario detection — see Section 6.4:
   - **Case A (appendOnly):** In-progress chain exists → validate `newMinOrder > maxExistingOrder`, new signers appended as `"pending"`, `currentSigningOrder` unchanged
   - **Case B (all completed):** All existing completed + unassigned parties remain → validate `newMinOrder > maxExistingOrder`, new signer at `startingOrder` gets `"unlocked"` immediately, `currentSigningOrder` advances to `startingOrder`
   - **Blocked:** All completed + no unassigned parties remain → `400` error
4. `ExternalSigner`, `InternalSigner`, and `PartyCompletion` objects built and appended to the contract's existing lists
5. For each external signer **at the starting order only** (and only when NOT `appendOnly`): `SignatureRequest` created, invitation email sent immediately. External signers at higher orders receive a token stored in `ExternalSigner.token` but no `SignatureRequest` document yet — created lazily by `AutoAdvanceService` when their order is unlocked.
6. Contract updated: `status = IN_SIGNATURE`, `signatureFlowStatus = "pending_signatures"`
7. `currentSigningOrder` is set to `startingOrder` **only when NOT appendOnly** — in-progress re-shares preserve the existing value
8. On first submission only: `contract.version = 0`

**Success Response:** `200 OK` — `ContractResponse`

> **Development Tip:** The signing token is returned in `externalSigners[].token`. Use it to open the signing page directly without email: `http://localhost:3000/sign/{token}`

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Contract not found` |
| `400` | `Contract must be ready for signature or already in signature workflow. Current status: {X}` |
| `400` | `Cannot add signers — the contract has already completed the signing workflow` — only when `SIGNED_BY_EVERYONE` AND no unassigned parties remain |
| `400` | `Contract file must be uploaded before sending for signature` |
| `400` | `Assignment type must be "internal" or "external"` |
| `400` | `Signer email is required for party {partyLabel}` |
| `400` | `Invalid email format: {email}` |
| `400` | `Duplicate signer email: {email}` |
| `400` | `You cannot assign yourself as a signer` |
| `400` | `Party {partyLabel} is already assigned` |
| `400` | `Order number {n} is assigned to more than one party in this submission.` |
| `400` | `Order {n} conflicts with an in-progress signer. New signers must have order greater than {maxExistingOrder}. Re-share is only allowed at orders beyond the existing chain.` |
| `400` | `Order {n} is already used. New signers must continue the chain at an order greater than {maxExistingOrder}.` |
| `400` | `The signing chain must start at order 1. Lowest order provided: {n}` |

---

### 12.2 GET `/contracts/{id}/signature-status`

Returns the full current state of the signing workflow.

**Authentication:** JWT. Caller must be the contract owner, a reviewer, an approver, or an assigned internal signer.

**Success Response:** `200 OK`

```json
{
  "contractId": "6650a1b2c3d4e5f6a7b8c9d0",
  "signatureFlowStatus": "pending_signatures",
  "currentSigningOrder": 2,
  "version": 1,
  "externalSigners": [...],
  "internalSigners": [...],
  "partyCompletions": [...]
}
```

---

### 12.3 GET `/contracts/{id}/file/view-url`

Returns a presigned MinIO URL for viewing the current contract PDF. Serves `_signed.pdf` if it exists (to show signing progress), otherwise falls back to the original `.pdf`.

**Authentication:** JWT. Caller must be the contract owner, a reviewer, an approver, or an assigned internal signer.

**Response:** `200 OK`

```json
{ "url": "http://localhost:9000/contract-management/contracts/{id}_signed.pdf?X-Amz-Signature=..." }
```

The URL is valid for **15 minutes**. The browser loads the PDF directly from MinIO.

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Contract not found` (also returned if caller has no access — hides existence) |
| `400` | `File not yet uploaded for this contract` |

---

### 12.4 POST `/contracts/{id}/sign/upload/initiate`

Starts a MinIO multipart upload session for the internal signer's signed PDF. Call this before generating presigned part URLs.

**Authentication:** JWT. Caller must be an assigned internal signer with `status = "unlocked"`.

**Request Body:** None

**Response:** `200 OK`

```json
{ "uploadId": "some-minio-multipart-upload-id" }
```

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Contract not found` |
| `404` | `You are not assigned as a signer on this contract` |
| `400` | `You have already completed your signature` |
| `400` | `It is not yet your turn to sign` |

---

### 12.5 GET `/contracts/{id}/sign/upload/presign`

Returns a presigned PUT URL for uploading one chunk directly to MinIO.

**Authentication:** JWT. Same caller restrictions as initiate.

**Query Parameters:**

| Param | Required | Description |
|---|---|---|
| `uploadId` | Yes | The ID returned by the initiate call |
| `partNumber` | Yes | 1-based integer. Part numbers must be sequential. |

**Response:** `200 OK`

```json
{ "url": "http://localhost:9000/...?partNumber=1&uploadId=...&X-Amz-Signature=...", "partNumber": 1 }
```

The browser PUTs the chunk bytes directly to this URL. The `ETag` response header from MinIO must be saved for the complete call.

---

### 12.6 POST `/contracts/{id}/sign/upload/abort`

Cancels the MinIO multipart upload and frees the partial chunks.

**Authentication:** JWT.

**Query Parameters:** `uploadId` (required)

**Response:** `200 OK` — no body

---

### 12.7 POST `/contracts/{id}/internal-sign`

Finalizes the MinIO multipart upload and runs all signing business logic for the internal signer. Call this **after** all parts have been uploaded to MinIO and the chunked upload is ready to complete.

**Authentication:** JWT. Caller's email must appear in `contract.internalSigners[]` with `status = "unlocked"`.

**Request Body:**

```json
{
  "signerEmail": "alice@company.com",
  "uploadId": "some-minio-multipart-upload-id",
  "parts": [
    { "partNumber": 1, "eTag": "\"d8e8fca2dc0f896fd7cb4cb0031ba249\"" },
    { "partNumber": 2, "eTag": "\"3d9a92c8b3b1d58ec8a4f9f7c2e81b0d\"" }
  ],
  "fieldValues": {
    "FullName": "Alice Kumar",
    "SignatureDate": "2026-06-16"
  },
  "formFields": [
    { "fieldName": "FullName", "type": "text", "assignedParty": "party_2", "value": "Alice Kumar" }
  ]
}
```

**Request Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `signerEmail` | `String` | Yes | Must match the JWT caller's email |
| `uploadId` | `String` | Yes | The ID from the initiate call |
| `parts` | `List<Part>` | Yes | All parts with their MinIO ETags |
| `parts[].partNumber` | `int` | Yes | 1-based part number |
| `parts[].eTag` | `String` | Yes | ETag from the MinIO PUT response header |
| `fieldValues` | `Map<String, String>` | No | Field name to value mapping |
| `formFields` | `List<Map>` | No | Updated form field definitions |

**What Happens Internally:**

1. JWT email matched against `internalSigners[]`
2. Signer status validated — must be `unlocked`
3. MinIO multipart upload finalized (`customMinioClient.finishMultipartUpload`)
4. `contract.signedPdfKey` updated
5. Form fields merged — server-side metadata preserved from the authoritative stored record: `assignedParty`, `partyLabel`, `partyColor`, `profileKey`, `lockedBy`. This prevents the Apryse `exportFormFields()` call on the frontend (which strips these fields) from wiping party assignment data from the database. The merge matches fields by both `fieldName` and `name` key variants.
6. `internalSigners[].status = "completed"`, `completedAt = now`
7. `partyCompletions` entry updated
8. `contract.version++`
9. Version synced to all pending `SignatureRequest` documents
10. `AutoAdvanceService.advance()` called

**Success Response:** `200 OK`

```json
{ "success": true, "message": "Signature submitted successfully" }
```

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Contract not found` |
| `404` | `You are not assigned as a signer on this contract` |
| `400` | `You have already completed your signature on this contract` |
| `400` | `It is not yet your turn to sign. Please wait for previous signers to complete.` |
| `400` | `Signed PDF is required — complete chunked upload first` |

---

### 12.8 POST `/contracts/{id}/finalize`

Called by the contractor after all parties have signed (`signatureFlowStatus == "all_completed"`).

**Authentication:** JWT. Caller must be the contract owner.

**Request Body:** None

**What Happens Internally:**

1. Validates caller is contract owner
2. Validates `signatureFlowStatus == "all_completed"`
3. Reads `_signed.pdf` from MinIO and copies to `_final.pdf`
4. Sends signed copy email to every external signer and internal signer
5. Updates `contract.status = SIGNED`, `signatureFlowStatus = "finalized"`

**Success Response:** `200 OK` — `ContractResponse` with updated status

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Contract not found` |
| `400` | `Cannot finalize — not all parties have signed yet. Current status: {signatureFlowStatus}` |
| `400` | `Contract has already been finalized` |

---

### 12.9 GET `/sign-requests/{token}` — PUBLIC

Loads all data needed to render the external signer's signing page.

**Authentication:** None — the token is the credential.

**Response:** `200 OK`

```json
{
  "token": "sig_1718123456789_a1b2c3d4e5f6a7b8",
  "contractId": "6650a1b2c3d4e5f6a7b8c9d0",
  "contractTitle": "Rental Agreement – Q3 2026",
  "signerEmail": "buyer@client.com",
  "signerName": "John Doe",
  "status": "pending",
  "expiresAt": "2026-06-22T10:30:00",
  "assignedParty": "party_1",
  "assignedPartyLabel": ["Buyer"],
  "formFields": [...],
  "xfdfData": "<?xml version=\"1.0\"?>...",
  "fieldValues": { "PropertyAddress": "123 Main Street" },
  "parties": [{ "id": "party_1", "label": "Buyer", "color": "#4CAF50" }],
  "contractVersion": 0
}
```

**Side Effect:** Syncs `SignatureRequest.contractVersion` to the current `contract.version` on load, preventing a false 409 Conflict.

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Signing request not found` |
| `400` | `You have already submitted your signature` |
| `400` | `This signing link expired on {date}` |

---

### 12.10 GET `/sign-requests/{token}/file-url` — PUBLIC

Returns a presigned MinIO URL so the browser can load the PDF directly from MinIO. Serves `_signed.pdf` if it exists (all prior signers' work), otherwise the original `.pdf`.

**Authentication:** None — token is validated.

**Response:** `200 OK`

```json
{ "url": "http://localhost:9000/contract-management/contracts/{id}_signed.pdf?X-Amz-Signature=..." }
```

The URL is valid for **15 minutes**. The browser (or Apryse WebViewer) loads the PDF directly from this URL. No PDF bytes pass through Spring Boot.

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Signing request not found` |
| `400` | `You have already submitted your signature` |
| `400` | `This signing link expired on {date}` |

---

### 12.11 POST `/sign-requests/{token}/upload/initiate` — PUBLIC

Starts a MinIO multipart upload session for the external signer's signed PDF.

**Authentication:** None — token validated.

**Request Body:** None

**Response:** `200 OK`

```json
{ "uploadId": "some-minio-multipart-upload-id" }
```

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Signing request not found` |
| `400` | `You have already submitted your signature` |
| `400` | `This signing link expired on {date}` |

---

### 12.12 GET `/sign-requests/{token}/upload/presign` — PUBLIC

Returns a presigned PUT URL for uploading one chunk directly to MinIO.

**Authentication:** None — token validated.

**Query Parameters:**

| Param | Required | Description |
|---|---|---|
| `uploadId` | Yes | ID from the initiate call |
| `partNumber` | Yes | 1-based. Sequential. |

**Response:** `200 OK`

```json
{ "url": "http://localhost:9000/...?partNumber=1&uploadId=...&X-Amz-Signature=...", "partNumber": 1 }
```

The browser PUTs the chunk bytes **directly to this MinIO URL** — not to the Spring Boot backend.

---

### 12.13 POST `/sign-requests/{token}/upload/complete` — PUBLIC

Finalizes the MinIO multipart upload and runs signing business logic. Handles both **final submit** and **auto-save** based on the `autoSave` field.

**Authentication:** None — token validated.

**Request Body:**

```json
{
  "uploadId": "some-minio-multipart-upload-id",
  "parts": [
    { "partNumber": 1, "eTag": "\"d8e8fca2dc0f896fd7cb4cb0031ba249\"" }
  ],
  "fieldValues": { "FullName": "John Doe", "SignatureDate": "2026-06-16" },
  "formFields": [
    { "fieldName": "FullName", "type": "text", "assignedParty": "party_1", "value": "John Doe" }
  ],
  "autoSave": false
}
```

**Request Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `uploadId` | `String` | Yes (if parts provided) | ID from initiate call |
| `parts` | `List<Part>` | Yes for final submit | All part ETags from MinIO PUT responses |
| `parts[].partNumber` | `int` | Yes | 1-based part number |
| `parts[].eTag` | `String` | Yes | ETag from MinIO PUT response header |
| `fieldValues` | `Map<String, String>` | No | Field name to value mapping |
| `formFields` | `List<Map>` | No | Updated form field definitions |
| `autoSave` | `boolean` | No (default `false`) | `true` = save progress only; `false` = final submit |

**Auto-Save Behaviour (`autoSave: true`):**
- Validates token is not expired
- Finalizes MinIO upload (so progress is not lost)
- Merges form fields on contract — server-side party metadata (`assignedParty`, `partyLabel`, `partyColor`, `profileKey`, `lockedBy`) is always preserved from the stored record, never overwritten by the incoming payload
- Syncs `contractVersion` in `SignatureRequest` to current version
- Returns `{ "success": true, "message": "Progress saved" }`
- Does **not** mark as signed, increment version, or trigger auto-advance

**Final Submit Behaviour (`autoSave: false`):**
- Validates token is not expired AND not already signed
- Checks `signatureRequest.contractVersion == contract.version` — mismatch → 409
- Finalizes MinIO upload
- Merges form fields — same server-side party metadata preservation as auto-save
- Sets `SignatureRequest.status = "signed"`, `signedAt = now`
- Sets `ExternalSigner.status = "completed"`, `completedAt = now`
- Updates `PartyCompletion`
- Increments `contract.version`
- Syncs version to all pending `SignatureRequest` documents
- Triggers `AutoAdvanceService.advance()`

**Success Response:** `200 OK`

```json
{ "success": true, "message": "Signature submitted successfully" }
```

**Error Responses:**

| HTTP | Message |
|---|---|
| `404` | `Signing request not found` |
| `400` | `This signing link has expired` |
| `400` | `You have already submitted your signature` |
| `409` | `Contract has been modified since you started signing. Please reload the page and try again.` |
| `400` | `Signed PDF upload is required — complete chunked upload first` |

---

### 12.14 POST `/sign-requests/{token}/upload/abort` — PUBLIC

Cancels the MinIO multipart upload and frees the partial chunks. Call on any error during upload.

**Authentication:** None — token validated.

**Query Parameters:** `uploadId` (required)

**Response:** `200 OK` — no body

---

### 12.15 PATCH `/sign-requests/{token}/viewed` — PUBLIC

Records that the external signer has opened their signing link. Updates `ExternalSigner.status` from `"unlocked"` to `"viewed"` and sets `viewedAt`.

**Authentication:** None.

**Request Body:** None

**Behaviour:** Fire-and-forget. Always returns 200. No error handling needed on the frontend.

---

## 13. Error Reference

### 13.1 Standard Error Response Format

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Human-readable error description"
}
```

### 13.2 HTTP Status Code Reference

| Code | When Used |
|---|---|
| `200 OK` | All successful operations |
| `400 Bad Request` | Validation error, invalid state, expired token, already signed |
| `404 Not Found` | Resource not found or caller has no access (returns 404 to hide existence) |
| `409 Conflict` | Optimistic locking failure — signer must reload |
| `500 Internal Server Error` | Unexpected exception — logged with full stack trace |
| `503 Service Unavailable` | MinIO storage operation failed |

### 13.3 Complete Error Catalogue

**Public Signing Endpoints:**

| Code | Message |
|---|---|
| `404` | `Signing request not found` |
| `400` | `You have already submitted your signature` |
| `400` | `This signing link expired on {date}` |
| `400` | `This signing link has expired` |
| `409` | `Contract has been modified since you started signing. Please reload the page and try again.` |
| `400` | `Signed PDF upload is required — complete chunked upload first` |

**Internal Sign:**

| Code | Message |
|---|---|
| `404` | `Contract not found` |
| `404` | `You are not assigned as a signer on this contract` |
| `400` | `You have already completed your signature on this contract` |
| `400` | `It is not yet your turn to sign. Please wait for previous signers to complete.` |
| `400` | `Signed PDF is required — complete chunked upload first` |

**Submit for Signature:**

| Code | Message |
|---|---|
| `404` | `Contract not found` |
| `400` | `Contract must be ready for signature or already in signature workflow. Current status: {X}` |
| `400` | `Cannot add signers — the contract has already completed the signing workflow` |
| `400` | `Contract file must be uploaded before sending for signature` |
| `400` | `Assignment type must be "internal" or "external"` |
| `400` | `Signer email is required for party {partyLabel}` |
| `400` | `Invalid email format: {email}` |
| `400` | `Duplicate signer email: {email}` |
| `400` | `You cannot assign yourself as a signer` |
| `400` | `Party {partyLabel} is already assigned` |
| `400` | `Order number {n} is assigned to more than one party in this submission. Each signer must have a unique order.` |
| `400` | `Order {n} conflicts with an in-progress signer. New signers must have order greater than {maxExistingOrder}. Re-share is only allowed at orders beyond the existing chain.` |
| `400` | `Order {n} is already used. New signers must continue the chain at an order greater than {maxExistingOrder}.` |
| `400` | `The signing chain must start at order 1. Lowest order provided: {n}` |

**Finalize:**

| Code | Message |
|---|---|
| `404` | `Contract not found` |
| `400` | `Cannot finalize — not all parties have signed yet. Current status: {signatureFlowStatus}` |
| `400` | `Contract has already been finalized` |

**View URL:**

| Code | Message |
|---|---|
| `404` | `Contract not found` |
| `400` | `File not yet uploaded for this contract` |

---

## 14. Frontend Integration Guide

### 14.1 External Signer — Complete Flow

```typescript
// Step 1: Load signing page data
const data = await fetch(`/api/backend/sign-requests/${token}`).then(r => r.json());

// Step 2: Mark as viewed (fire and forget)
fetch(`/api/backend/sign-requests/${token}/viewed`, { method: 'PATCH' });

// Step 3: Get presigned URL and load PDF directly from MinIO
const { url: pdfUrl } = await fetch(`/api/backend/sign-requests/${token}/file-url`).then(r => r.json());
// Pass pdfUrl directly to Apryse WebViewer as the document source
// WebViewer fetches the PDF from MinIO directly — no proxy involved

// Step 4: When signer is done (auto-save or final submit)
async function uploadSignedPdf(pdfBlob: Blob, fieldValues: object, formFields: object[], autoSave: boolean) {
  const CHUNK_SIZE = 10 * 1024 * 1024; // 10 MB

  // 4a: Initiate
  const { uploadId } = await fetch(`/api/backend/sign-requests/${token}/upload/initiate`, {
    method: 'POST'
  }).then(r => r.json());

  // 4b + 4c: Split into chunks, get presigned URL, upload each chunk to MinIO
  const parts = [];
  let partNumber = 1;

  for (let offset = 0; offset < pdfBlob.size; offset += CHUNK_SIZE) {
    const chunk = pdfBlob.slice(offset, offset + CHUNK_SIZE);

    const { url } = await fetch(
      `/api/backend/sign-requests/${token}/upload/presign?uploadId=${uploadId}&partNumber=${partNumber}`
    ).then(r => r.json());

    // PUT directly to MinIO — NOT to /api/backend
    const uploadRes = await fetch(url, { method: 'PUT', body: chunk });
    const eTag = uploadRes.headers.get('ETag');
    parts.push({ partNumber, eTag });
    partNumber++;
  }

  // 4d: Complete — finalize MinIO upload + run business logic
  const result = await fetch(`/api/backend/sign-requests/${token}/upload/complete`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ uploadId, parts, fieldValues, formFields, autoSave })
  });

  if (result.status === 409) {
    window.location.reload(); // Version conflict — reload to get latest PDF
    return;
  }

  return result.json();
}

// On auto-save:
await uploadSignedPdf(currentPdfBlob, fieldValues, formFields, true);

// On final submit:
await uploadSignedPdf(signedPdfBlob, fieldValues, formFields, false);

// On error during upload — abort to clean up MinIO:
await fetch(`/api/backend/sign-requests/${token}/upload/abort?uploadId=${uploadId}`, { method: 'POST' });
```

### 14.2 Internal Signer — Complete Flow

```typescript
// Step 1: View the contract PDF (from inbox)
const { url: pdfUrl } = await fetch(`/api/backend/contracts/${contractId}/file/view-url`, {
  headers: { 'Authorization': `Bearer ${jwt}` }
}).then(r => r.json());
// Pass pdfUrl to Apryse WebViewer — loads directly from MinIO

// Step 2: When signer is done signing
async function submitInternalSignature(pdfBlob: Blob, fieldValues: object, formFields: object[]) {
  const CHUNK_SIZE = 10 * 1024 * 1024;

  // 2a: Initiate
  const { uploadId } = await fetch(`/api/backend/contracts/${contractId}/sign/upload/initiate`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${jwt}` }
  }).then(r => r.json());

  // 2b + 2c: Upload chunks directly to MinIO
  const parts = [];
  let partNumber = 1;

  for (let offset = 0; offset < pdfBlob.size; offset += CHUNK_SIZE) {
    const chunk = pdfBlob.slice(offset, offset + CHUNK_SIZE);

    const { url } = await fetch(
      `/api/backend/contracts/${contractId}/sign/upload/presign?uploadId=${uploadId}&partNumber=${partNumber}`,
      { headers: { 'Authorization': `Bearer ${jwt}` } }
    ).then(r => r.json());

    // PUT directly to MinIO — no auth header needed (presigned URL handles auth)
    const uploadRes = await fetch(url, { method: 'PUT', body: chunk });
    const eTag = uploadRes.headers.get('ETag');
    parts.push({ partNumber, eTag });
    partNumber++;
  }

  // 2d: Finalize upload + submit signature
  await fetch(`/api/backend/contracts/${contractId}/internal-sign`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ signerEmail: currentUserEmail, uploadId, parts, fieldValues, formFields })
  });
}

// On error — abort:
await fetch(`/api/backend/contracts/${contractId}/sign/upload/abort?uploadId=${uploadId}`, {
  method: 'POST',
  headers: { 'Authorization': `Bearer ${jwt}` }
});
```

### 14.3 Contractor — Submit for Signature

```typescript
const response = await fetch(`/api/backend/contracts/${contractId}/submit-for-signature`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    assignments: [
      { partyId: 'party_1', partyLabel: 'Buyer', type: 'external', email: 'buyer@client.com', name: 'John Doe', order: 1 },
      { partyId: 'party_2', partyLabel: 'Seller', type: 'internal', email: 'alice@company.com', userId: 'user_abc', order: 2 }
    ],
    senderName: currentUser.displayName,
  }),
});

const contract = await response.json();
// contract.externalSigners[0].token → signing URL for dev testing
```

### 14.4 Inbox — Internal Signer Card Rendering

```typescript
function getInternalSignerState(contract, currentUserEmail) {
  const entry = contract.internalSigners?.find(
    s => s.email.toLowerCase() === currentUserEmail.toLowerCase()
  );
  if (!entry) return null;
  return entry.status; // "pending" | "unlocked" | "completed"
}

// Render based on status:
// "pending"   → badge: "Awaiting Your Turn" (grey)  | View only
// "unlocked"  → badge: "Ready to Sign"     (orange) | View & Sign
// "completed" → badge: "Signed"            (green)  | View only
```

### 14.5 Important Notes for Frontend

- **Never send the PDF through `/api/backend`** — always use the presigned URLs returned by the `presign` endpoints to PUT directly to MinIO.
- **Save the `ETag` from each MinIO PUT response** — these are required in the `complete` call. MinIO returns the ETag in the response headers.
- **The presigned URL for MinIO PUT does not need an `Authorization` header** — the URL already encodes the authorization via HMAC signature.
- **Handle 409 Conflict** on the complete endpoint — this means the contract changed while the signer was signing. Reload the page to get the latest PDF and version.
- **Call abort on any upload error** — this cleans up the partial upload from MinIO and frees storage.
- **`assignedParty` in `GET /sign-requests/{token}` response is a `String`, not an array** — the backend extracts the first element before returning. The frontend should use it directly as a scalar: `signatureRequest.assignedParty` → `"party_1"`.
- **Party field restrictions depend on `assignedParty` being present** — if `assignedParty` is null/missing, the Apryse restriction block will not run and the signer will be able to fill all fields. Always verify the value is non-null after loading.
- **Never wipe `assignedParty` / `partyLabel` / `partyColor` / `profileKey` from formFields when saving** — use the server-returned formFields as the base and only update `value` fields before posting back. The server merges and restores these fields, but passing them through avoids the round-trip dependency.

---

## 15. Environment Configuration

### 15.1 Required Environment Variables

| Variable | Description | Example |
|---|---|---|
| `MAIL_HOST` | SMTP server hostname | `smtp.gmail.com` |
| `MAIL_PORT` | SMTP port | `587` |
| `MAIL_USERNAME` | SMTP account email | `notifications@yourapp.com` |
| `MAIL_PASSWORD` | SMTP account password or app password | `abcd efgh ijkl mnop` |
| `APP_BASE_URL` | Frontend base URL — used in email links | `https://yourapp.com` |

### 15.2 application.properties Reference

```properties
# MongoDB
spring.mongodb.host=localhost
spring.mongodb.port=27017
spring.mongodb.database=contractdb
spring.data.mongodb.auto-index-creation=true

# MinIO
minio.endpoint=http://localhost:9000
minio.access-key=minioadmin
minio.secret-key=minioadmin123
minio.bucket-name=contract-management

# Email (SMTP)
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true

# App
app.base-url=${APP_BASE_URL:http://localhost:3000}
app.signing-link-expiry-days=7

# CORS
cors.allowed-origins=http://localhost:3000,http://localhost:3001

# JWT
jwt.secret=your_secret_here
jwt.expiration=86400000

# File uploads (retained for non-signing endpoints)
server.tomcat.max-http-form-post-size=-1
spring.servlet.multipart.max-file-size=500MB
spring.servlet.multipart.max-request-size=500MB
```

### 15.3 SMTP Provider Options

**Gmail (development/small scale):**
```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=yourapp@gmail.com
spring.mail.password=abcd efgh ijkl mnop   # App Password from Google Account settings
```

**Mailtrap (development — catches emails without delivering):**
```properties
spring.mail.host=sandbox.smtp.mailtrap.io
spring.mail.port=2525
spring.mail.username={mailtrap_username}
spring.mail.password={mailtrap_password}
```

**SendGrid (production):**
```properties
spring.mail.host=smtp.sendgrid.net
spring.mail.port=587
spring.mail.username=apikey          # literal string "apikey"
spring.mail.password={SENDGRID_API_KEY}
```

---

## 16. Development & Testing Guide

### 16.1 Testing Without Email

The signing token is returned directly in the submit-for-signature API response, so you can test without email configured.

1. Call `POST /contracts/{id}/submit-for-signature`
2. In the response: `externalSigners[0].token`
3. Open: `http://localhost:3000/sign/{token}`

### 16.2 Complete End-to-End Test Scenarios

**Scenario 1: External + Internal, Sequential (P1 external → P2 internal)**

```
1. Create contract (POST /contracts)
2. Upload file (PUT /contracts/{id}/file or chunked)
3. Advance to READY_FOR_SIGNATURE via review/approval workflow
4. Submit for signature:
   - party_1: external, order 1
   - party_2: internal, order 2

5. External signer (party_1):
   GET  /sign-requests/{token}           → verify status, expiresAt
   PATCH /sign-requests/{token}/viewed   → signer status becomes "viewed"
   GET  /sign-requests/{token}/file-url  → get presigned URL for original .pdf
   POST /sign-requests/{token}/upload/initiate  → get uploadId
   GET  /sign-requests/{token}/upload/presign?uploadId=X&partNumber=1  → get presigned PUT URL
   PUT  {presignedUrl}  [PDF chunk bytes directly to MinIO]  → save ETag
   POST /sign-requests/{token}/upload/complete
        body: { uploadId, parts: [{partNumber:1, eTag:...}], fieldValues, formFields, autoSave: false }
   → verify: currentSigningOrder = 2, party_1.status = "completed"
   → verify: party_2.status = "unlocked" (auto-advance fired)

6. Internal signer (party_2) — login with JWT:
   GET  /contracts/{id}/file/view-url    → presigned URL for _signed.pdf (has party_1's work)
   POST /contracts/{id}/sign/upload/initiate → uploadId
   GET  /contracts/{id}/sign/upload/presign?uploadId=X&partNumber=1 → presigned PUT URL
   PUT  {presignedUrl}  [PDF chunk to MinIO]  → save ETag
   POST /contracts/{id}/internal-sign
        body: { signerEmail, uploadId, parts, fieldValues, formFields }
   → verify: signatureFlowStatus = "all_completed"
   → verify: contract status = "SIGNED_BY_EVERYONE"

7. Contractor finalizes:
   POST /contracts/{id}/finalize
   → verify: status = "SIGNED", signatureFlowStatus = "finalized"
   → verify: _final.pdf exists in MinIO
   → verify: signed copy email sent to both signers
```

**Scenario 2: All Internal, Two Parties**

```
1–4. Same setup, both parties type: "internal"
5. Login as internal user 1 (JWT)
   GET /contracts/inbox → contract appears with status "unlocked"
   GET /contracts/{id}/file/view-url → presigned URL
   [chunked upload steps]
   POST /contracts/{id}/internal-sign → currentSigningOrder = 2, user2 unlocked
6. Login as user2
   [same steps]
   → signatureFlowStatus = "all_completed"
7. POST /contracts/{id}/finalize (contractor JWT)
```

**Scenario 3: Re-Share While In Progress**

```
1–4. Submit with party_1 (order 1) only
5. Check: party_1 unlocked, currentSigningOrder = 1
6. Re-share before party_1 completes: submit party_2 at order 2
   → party_2.status = "pending" (not yet unlocked)
   → currentSigningOrder still = 1
7. party_1 completes signing
   → auto-advance fires
   → party_2.status = "unlocked", currentSigningOrder = 2
```

**Scenario 4: Re-Share After Chain Completes — Unassigned Party Continues the Linear Chain**

```
1. Contract has parties: P1, P2, P3, P4, P5 (only P1–P4 assigned in first submission)
2. Submit P1 (order 1), P2 (order 2), P3 (order 3), P4 (order 4)
3. All four complete → signatureFlowStatus = "all_completed", status = SIGNED_BY_EVERYONE
   maxExistingOrder = 4

4. Re-share for P5 (unassigned party), continuing the chain:
   POST /contracts/{id}/submit-for-signature
   body: { assignments: [{ partyId: "party_5", order: 5, ... }] }
   → accepted: order 5 > maxExistingOrder (4) ✓
   → P5: order=5, status="unlocked" immediately (all previous completed, appendOnly=false)
   → currentSigningOrder = 5
   → contract.status = IN_SIGNATURE

   If instead order 4 or lower is submitted:
   → 400: "Order 4 is already used. New signers must continue the chain at an order greater than 4."

5. P5 completes signing
   → AutoAdvance looks for next order > 5 → none
   → signatureFlowStatus = "all_completed"

6. Contractor finalizes → status = SIGNED
```

**Scenario 5: Re-Share After Chain Completes — All Parties Already Assigned (Blocked)**

```
All parties assigned and completed → signatureFlowStatus = "all_completed"
No unassigned parties remain on the contract.
Attempt to re-share:
→ 400: "Cannot add signers — the contract has already completed the signing workflow"
```

**Scenario 6: Error Cases**

```
Expired token:
  GET /sign-requests/expired_token
  → 400: "This signing link expired on..."

Duplicate order:
  Submit two parties both at order 1
  → 400: "Order number 1 is assigned to more than one party..."

Wrong turn (internal):
  POST /contracts/{id}/internal-sign when status = "pending"
  → 400: "It is not yet your turn to sign..."

Version conflict:
  POST /sign-requests/{token}/upload/complete when versions diverged
  → 409: "Contract has been modified since you started signing..."
```

### 16.3 MongoDB Queries for Manual Inspection

```javascript
// All pending signature requests for a contract
db.signature_requests.find({ contractId: "CONTRACT_ID", status: "pending" })

// Current signing state
db.contracts.findOne(
  { _id: ObjectId("CONTRACT_ID") },
  { signatureFlowStatus: 1, currentSigningOrder: 1, version: 1,
    externalSigners: 1, internalSigners: 1 }
)

// Contracts where a user is an internal signer
db.contracts.find({ "internalSigners.email": "user@company.com" })
```

### 16.4 MinIO Manual Inspection

Open MinIO Console at `http://localhost:9001` (login: `minioadmin` / `minioadmin123`).

Navigate to bucket `contract-management`:
- `contracts/{id}.pdf` — original (always present after contract file upload)
- `contracts/{id}_signed.pdf` — appears after first signer completes
- `contracts/{id}_final.pdf` — appears after contractor finalizes

### 16.5 Checking Email Logs

```
ERROR EmailService - Failed to send signature request email to {email}: ...
```

If you see this, SMTP credentials are incorrect or the server is unreachable. The signing workflow itself is unaffected. Use Mailtrap during development to capture outgoing emails without real delivery.

---

## 17. Contract Detail Screen — API Reference

The contract detail screen (e.g., `/contracts/{id}`) requires **no new backend APIs** except the activity timeline. All data shown on that screen is already available through existing endpoints.

### 17.1 Required API Calls on Page Load

Make these two calls in parallel on page load:

| Call | Purpose | Auth |
|---|---|---|
| `GET /contracts/{id}` | Contract info (title, client, category, dates, description, status, `signatureFlowStatus`) | JWT |
| `GET /contracts/{id}/signature-status` | Signers list, who completed, current order, `partyCompletions` | JWT |

### 17.2 Data Mapping — Screen Sections

| UI Section | Data Source | Field |
|---|---|---|
| Title, Client, Category, Template | `GET /contracts/{id}` | `title`, `client`, `category`, `templateName` |
| Start Date, End Date | `GET /contracts/{id}` | `startDate`, `endDate` |
| Description | `GET /contracts/{id}` | `description` |
| Status badge ("In Signature") | `GET /contracts/{id}` | `status` |
| Contract Progress bar (days remaining) | `GET /contracts/{id}` | Calculate from `startDate` → `endDate` on the frontend |
| Documents tab — PDF name | `GET /contracts/{id}` | `templateFileName` or `title` |
| Documents tab — View (eye icon) | `GET /contracts/{id}/file/view-url` | Presigned MinIO URL |
| Documents tab — Download | Same presigned URL, trigger browser download | — |
| Activity tab | `GET /contracts/{id}/activity` *(new endpoint — see 17.4)* | JWT |
| **Finalize button** | Show/hide based on `signatureFlowStatus` | — |

### 17.3 Finalize Button Visibility Logic

```typescript
// Show "Finalize" button only when all current-round signers have completed
const showFinalizeButton = contract.signatureFlowStatus === "all_completed";

// Hide button / show "Finalized" badge when done
const isFinalized = contract.signatureFlowStatus === "finalized";
```

The button should **not** appear during `"pending_signatures"` (signing still in progress) or `"finalized"` (already done).

### 17.4 GET `/contracts/{id}/activity` — NEW ENDPOINT

Returns a chronological timeline of all events on the contract, derived from existing data fields on the contract document.

**Authentication:** JWT. Caller must be the contract owner, a reviewer, an approver, or an assigned internal signer.

**Response:** `200 OK`

```json
[
  {
    "event": "Contract Created",
    "by": "admin@gmail.com",
    "at": "2026-06-17T10:00:00"
  },
  {
    "event": "Sent for Signature",
    "by": "buyer@client.com",
    "at": "2026-06-17T10:05:00"
  },
  {
    "event": "Signed",
    "by": "pallavi@gmail.com",
    "at": "2026-06-17T12:30:00"
  },
  {
    "event": "All Parties Signed",
    "by": null,
    "at": "2026-06-17T15:00:00"
  },
  {
    "event": "Contract Finalized",
    "by": "admin@gmail.com",
    "at": "2026-06-17T15:05:00"
  }
]
```

**Event Sources (derived, no new DB collection needed):**

| Event | Derived From |
|---|---|
| Contract Created | `contract.createdAt` + `contract.createdBy` |
| Sent for Signature | `externalSigners[].sentAt` (one event per signer sent) |
| Signed | `externalSigners[].completedAt` + `internalSigners[].completedAt` |
| Reviewed by X | `reviewers[].reviewedAt` (if review workflow used) |
| Approved by X | `approver.approvedAt` (if approval workflow used) |
| All Parties Signed | Derived from `signatureFlowStatus = "all_completed"` — timestamp approximated from last `completedAt` |
| Contract Finalized | `signatureFlowStatus = "finalized"` + contract `updatedAt` |

The endpoint assembles these events from the existing contract document, sorts them chronologically, and returns them. **No separate MongoDB collection is required.** A dedicated event log collection should only be considered in the future if fine-grained events (e.g., "PDF viewed", "field auto-saved", "admin overrode status") need to be tracked that leave no trace in the contract document.

---

*End of Documentation — Version 2.3.0*
