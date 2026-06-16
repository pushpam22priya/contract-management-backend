# Contract Review & Approval Workflow — Technical Documentation

**Version:** 3.0.0
**Status:** Implemented & Production-Ready
**Last Updated:** 2026-06-11
**Base URL:** `http://localhost:8080`
**Authentication:** JWT Bearer Token — required on every endpoint

---

## Changelog

| Version | Date | Summary |
|---|---|---|
| 3.0.0 | 2026-06-11 | Full rewrite — detailed logic explanations, frontend integration guide, inbox returns ContractResponse, all content verified against live source files |
| 2.1.0 | 2026-06-11 | Added GET /contracts/inbox, GET /users, extended view-url access, mode-lock on resubmission |
| 2.0.0 | 2026-06-09 | Initial implementation documentation |

---

## Table of Contents

1. [System Overview & Design Philosophy](#1-system-overview--design-philosophy)
2. [Authentication Model — Why JWT and Not Request Body](#2-authentication-model--why-jwt-and-not-request-body)
3. [Workflow Modes — The Three Paths](#3-workflow-modes--the-three-paths)
4. [Status System — Three Enums Working Together](#4-status-system--three-enums-working-together)
5. [Data Models — MongoDB Document Structure](#5-data-models--mongodb-document-structure)
6. [API Reference — All 9 Endpoints](#6-api-reference--all-9-endpoints)
   - [POST /contracts/{id}/submit](#61-post-contractsidsubmit)
   - [POST /contracts/{id}/review/complete](#62-post-contractsidreviewcomplete)
   - [POST /contracts/{id}/review/forward](#63-post-contractsidreviewforward)
   - [POST /contracts/{id}/review/reject](#64-post-contractsidreviewreject)
   - [POST /contracts/{id}/approval/approve](#65-post-contractsidapprovalapprove)
   - [POST /contracts/{id}/approval/reject](#66-post-contractsidapprovalreject)
   - [GET /contracts/inbox](#67-get-contractsinbox)
   - [GET /contracts/{id}/file/view-url](#68-get-contractsidfileview-url)
   - [GET /users](#69-get-users)
7. [PATCH Guard — Why Editing Is Blocked During Active Workflow](#7-patch-guard--why-editing-is-blocked-during-active-workflow)
8. [Resubmission — All 4 Cases in Detail](#8-resubmission--all-4-cases-in-detail)
9. [Frontend Integration Guide](#9-frontend-integration-guide)
10. [Complete Status Transition Map](#10-complete-status-transition-map)
11. [Business Rules Index](#11-business-rules-index)
12. [Complete Error Reference](#12-complete-error-reference)
13. [End-to-End Testing Guide](#13-end-to-end-testing-guide)

---

## 1. System Overview & Design Philosophy

The Review & Approval Workflow allows a contract owner (contractor) to send a contract through a structured sign-off process before it moves into the signature stage. The contractor chooses one of three modes at submission time depending on what the contract requires.

### Architecture

```
React Frontend
     │
     │  HTTP + JWT Bearer Token
     ▼
Spring Boot Backend  ──► MongoDB (single "contracts" collection)
     │                        │
     │  presigned URL         └─ Contract document embeds:
     ▼                             reviewers[]       (array)
    MinIO (PDF storage)            approver {}       (single object)
                                   modificationRequests[]  (append-only)
```

### Core Design Decisions and Why

**Single source of truth — Spring Boot owns all state.**
The frontend never writes to MongoDB directly. Every status change goes through a validated API call. This prevents the frontend from creating an inconsistent state — for example, marking a contract "approved" without the actual approver's JWT ever being involved.

**Embedded documents over references.**
Reviewer info, approver info, and modification history are stored inside the contract document rather than in separate collections. A full contract read is a single MongoDB query — no joins needed. The trade-off of slightly larger documents is acceptable because contracts are not high-frequency-write documents and the workflow data is naturally owned by the contract.

**Non-terminal rejection.**
A rejection never permanently ends the workflow. The contractor can revise the contract and resubmit. This reflects real-world contract negotiation — rejection is feedback, not finality.

**Append-only modification history.**
Every rejection and every resubmission is recorded permanently in `modificationRequests[]`. Entries are never deleted or edited. This creates a complete audit trail that legal and compliance teams can reference if a dispute arises about what was communicated and when.

**Controller separation.**
Workflow operations live in `ContractWorkflowController` while basic CRUD lives in `ContractController`. Both map to `/contracts` but handle entirely different concerns. This keeps each class focused and testable in isolation.

---

## 2. Authentication Model — Why JWT and Not Request Body

All endpoints extract the caller's identity from the JWT token in the `Authorization` header — never from the request body.

```
Authorization: Bearer <jwt_token>
```

**Why this matters:** If reviewer identity came from the request body (e.g., `{ "reviewerEmail": "me@example.com" }`), any authenticated user could impersonate another reviewer by sending a different email. Since the JWT is signed by the server and cannot be forged by the client, extracting identity from it guarantees that only the actual assigned reviewer can act on a contract.

**How it works in code:** Every workflow controller method calls `getEmail()`:

```java
private String getEmail() {
    return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
}
```

The JWT filter populates the `SecurityContext` on every request before the controller is reached. The principal is the user's email. This email is then passed down to the service layer where it is verified against `reviewers[].email` or `approver.email`.

### Role Summary

| Role | Who They Are | Capabilities |
|---|---|---|
| **Contractor (Owner)** | User who created the contract (`createdBy` field) | Submit, resubmit, PATCH metadata, upload file |
| **Reviewer** | User assigned in `reviewerEmails` at submit time | Mark reviewed, forward, reject; view inbox; view PDF |
| **Approver** | User assigned in `approverEmail` at submit time | Approve, reject; view inbox; view PDF |

These are per-contract roles, not global user roles. A user can be the contractor on their own contracts and simultaneously a reviewer or approver on someone else's.

---

## 3. Workflow Modes — The Three Paths

The contractor picks one of three modes when submitting a contract. The mode controls which actors participate and what the state machine looks like. **Once set at initial submission, the mode is permanently locked — it cannot be changed on any resubmission.**

**Why mode is locked:** If a contractor could switch from `REVIEW_AND_APPROVE` to `ONLY_APPROVE` after a reviewer rejected the contract, they could bypass the review requirement entirely. The mode was chosen with intent at initial submission; changing it after rejection would be a loophole.

---

### Mode 1 — ONLY_REVIEW

**Use when:** The contract needs human sign-off from subject matter experts (legal, finance, compliance) but no formal management approval is required.

**Requirements:** At least 1 reviewer email. No approver email — blocked if provided.

```
DRAFT
  │
  └─[POST /submit]──────────────────────────► IN_REVIEW
                                                   │
                           ┌───────────────────────┼───────────────────────┐
                           │                       │                       │
                   [all reviewers             [any reviewer          [reviewer calls
                    reviewed or               calls /reject]          /forward]
                    forwarded]                     │                       │
                           │                       ▼                  stays IN_REVIEW
                           ▼               REJECTED_BY_REVIEWER      (new reviewers
                   READY_FOR_SIGNATURE             │                   added, they
                                            [POST /submit             must also act)
                                             with new
                                             reviewerEmails]
                                                   │
                                               IN_REVIEW
                                           (fresh review cycle)
```

**Forwarding:** A reviewer can forward to additional reviewers. Their own entry status becomes `forwarded` and new reviewers are added as `pending`. The contract stays `IN_REVIEW` until **all** reviewers (original + forwarded) have a non-`pending` status.

**Rejection:** One rejection is sufficient — the contract immediately moves to `REJECTED_BY_REVIEWER`. The other reviewers' statuses are not changed; they simply do not need to act anymore.

---

### Mode 2 — ONLY_APPROVE

**Use when:** The contract goes directly to a manager or executive for approval without a preceding review step.

**Requirements:** Exactly 1 approver email. No reviewer emails — blocked if provided.

```
DRAFT
  │
  └─[POST /submit]──────────────────────────► IN_APPROVAL
                                                   │
                                     ┌─────────────┤
                                     │             │
                              [/approval      [/approval
                               /approve]       /reject]
                                     │             │
                                     ▼             ▼
                             READY_FOR_      REJECTED_BY_APPROVER
                             SIGNATURE             │
                                            [POST /submit
                                             same approver
                                             resets to pending]
                                                   │
                                               IN_APPROVAL
```

**On resubmission:** The **same approver** is used — the contractor cannot reassign the approver after rejection. The approver's status resets to `pending`; their `approvedAt`, `rejectedAt`, and `comments` are cleared. The original assignment details (`email`, `sentAt`, `sentBy`, `submissionMessage`) are preserved because they describe the original assignment event, not the latest action.

---

### Mode 3 — REVIEW_AND_APPROVE

**Use when:** The contract needs both subject matter review and formal management approval. Review must complete successfully before approval begins — the approver cannot act until all reviewers are done.

**Requirements:** At least 1 reviewer email AND exactly 1 approver email.

```
DRAFT
  │
  └─[POST /submit]──────────────────────────► IN_REVIEW
                                                   │
                           ┌───────────────────────┤
                           │                       │
                   [all reviewers           [any reviewer
                    done → auto              calls /reject]
                    advance]                       │
                           │                       ▼
                           ▼               REJECTED_BY_REVIEWER
                       IN_APPROVAL                 │
                           │            [POST /submit with new
                 ┌─────────┤             reviewerEmails — approver
                 │         │             preserved, review restarts]
          [/approval  [/approval                   │
           /approve]   /reject]               IN_REVIEW
                 │         │
                 ▼         ▼
         READY_FOR_  REJECTED_BY_APPROVER
         SIGNATURE         │
                    [POST /submit — approver resets,
                     review NOT repeated,
                     straight to IN_APPROVAL]
                           │
                       IN_APPROVAL
```

**Critical: approver rejection does not restart review.**
After the approver rejects, the contractor resubmits and the contract goes directly to `IN_APPROVAL` — the reviewers do not act again. The reviewers already verified the contract. Forcing the whole review to restart because of a separate business-level concern from the approver would waste the reviewers' time and invalidate a sign-off that was never questioned.

**Critical: reviewer rejection preserves the approver.**
When a reviewer rejects, the contractor provides new `reviewerEmails` on resubmission. The approver is preserved — they cannot be changed. The reasoning: the approver was chosen at initial submission for their role/authority; reviewer rejection does not change who needs to approve the contract.

---

## 4. Status System — Three Enums Working Together

Contract state is tracked by three enums at once. They serve different purposes and are consumed by different parts of the frontend and backend.

### 4.1 ContractStatus — Main Status

The primary status on the Contract document. Every workflow service method checks this first to verify the contract is in the right state before allowing any action.

| Value | Description | When Set |
|---|---|---|
| `DRAFT` | Contract created, not yet submitted | On contract creation |
| `IN_REVIEW` | Assigned to reviewers, awaiting action | On submit (ONLY_REVIEW / REVIEW_AND_APPROVE), or resubmit after reviewer rejection |
| `IN_APPROVAL` | Assigned to approver, awaiting action | On submit (ONLY_APPROVE), or when all reviewers complete in REVIEW_AND_APPROVE, or on resubmit after approver rejection |
| `READY_FOR_SIGNATURE` | Workflow fully complete | When reviewer completes in ONLY_REVIEW, or when approver approves |
| `IN_SIGNATURE` | E-signature process initiated | Signature service (outside this workflow) |
| `ACTIVE` | Contract is live | Lifecycle service |
| `EXPIRING` | Contract near its end date | Scheduler |
| `EXPIRED` | Contract past end date | Scheduler |
| `TERMINATED` | Manually terminated | Admin action |
| `REJECTED_BY_REVIEWER` | A reviewer rejected — non-terminal | When any reviewer calls `/review/reject` |
| `REJECTED_BY_APPROVER` | Approver rejected — non-terminal | When approver calls `/approval/reject` |

### 4.2 ReviewStatus — Aggregate Review Progress

Tracks the overall progress of the review step. Useful for the frontend to show a progress indicator without iterating through each individual reviewer entry.

| Value | Description | When Set |
|---|---|---|
| `PENDING` | Reviewers assigned, nobody has acted yet | On submit/resubmit that starts a new review cycle |
| `IN_PROGRESS` | At least one reviewer acted, others still pending | After the first reviewer completes/forwards while others remain pending |
| `COMPLETED` | All reviewers have acted (reviewed or forwarded) | When the last pending reviewer calls complete or forward |
| `REJECTED` | At least one reviewer rejected | When any reviewer calls reject |

`null` when the contract is in `ONLY_APPROVE` mode (no review step exists).

### 4.3 ApprovalStatus — Approval State

Single-approver status — simpler than ReviewStatus.

| Value | Description | When Set |
|---|---|---|
| `PENDING` | Approver assigned, no action yet | On submit for modes with an approver, or after resubmit that resets the approver |
| `APPROVED` | Approver approved | When approver calls `/approval/approve` |
| `REJECTED` | Approver rejected | When approver calls `/approval/reject` |

`null` when the contract is in `ONLY_REVIEW` mode.

### How the Three Enums Work Together — Example Timeline

Full `REVIEW_AND_APPROVE` cycle with one approver rejection and resubmission:

```
Event                                  ContractStatus        ReviewStatus   ApprovalStatus
─────────────────────────────────────────────────────────────────────────────────────────
Contract created                       DRAFT                 null           null
Owner submits                          IN_REVIEW             PENDING        PENDING
Reviewer 1 completes (R2 pending)      IN_REVIEW             IN_PROGRESS    PENDING
Reviewer 2 completes (all done)        IN_APPROVAL           COMPLETED      PENDING
Approver rejects                       REJECTED_BY_APPROVER  COMPLETED      REJECTED
Owner resubmits                        IN_APPROVAL           COMPLETED      PENDING
Approver approves                      READY_FOR_SIGNATURE   COMPLETED      APPROVED
```

Notice `ReviewStatus` stays `COMPLETED` throughout the approver rejection/resubmit cycle — the review is never undone.

---

## 5. Data Models — MongoDB Document Structure

All workflow data is embedded inside the single contract document. No additional MongoDB collections are created for this feature.

### 5.1 Contract — Workflow Fields Added

Six new fields were added to the existing `Contract` model. No existing fields were modified.

```java
private WorkflowMode workflowMode;                      // which mode — set at first submit, immutable
private List<ReviewerInfo> reviewers;                   // all assigned reviewers (grows on forward)
private ReviewStatus reviewStatus;                      // aggregate review state
private ApproverInfo approver;                          // single assigned approver
private ApprovalStatus approvalStatus;                  // aggregate approval state
private List<ModificationRequest> modificationRequests; // append-only rejection+resubmit audit trail
```

### 5.2 ReviewerInfo — Embedded in reviewers[]

One entry per reviewer. The array grows when reviewers are forwarded. On resubmission after reviewer rejection, the entire array is **replaced** with the new list.

| Field | Type | Description |
|---|---|---|
| `email` | `String` | Reviewer's email — used to identify them from JWT on action |
| `status` | `String` | `"pending"` → `"reviewed"` or `"forwarded"` or `"rejected"` |
| `reviewedAt` | `LocalDateTime` | Set when status becomes `reviewed` or `forwarded` |
| `rejectedAt` | `LocalDateTime` | Set when status becomes `rejected` (separate from `reviewedAt`) |
| `comments` | `String` | Optional text left by the reviewer when completing or rejecting |
| `submissionMessage` | `String` | Message the contractor (or forwarder) sent when assigning this reviewer |
| `sentAt` | `LocalDateTime` | When this reviewer was assigned |
| `sentBy` | `String` | Email of whoever added this reviewer — contractor on submit, forwarder on forward |

**Why separate `reviewedAt` and `rejectedAt`?** A single `actedAt` field would require checking `status` to know what the timestamp means. Two dedicated fields are self-documenting and allow the frontend to display "reviewed on X" and "rejected on X" independently.

**Why `sentBy`?** When reviewer 1 forwards to reviewer 2, reviewer 2's `sentBy` is reviewer 1's email, not the contractor's. This lets you trace exactly who in the chain added each reviewer — important for audit purposes.

### 5.3 ApproverInfo — Embedded as approver (single object)

Only one approver per contract. Cannot be changed after initial submission.

| Field | Type | Description |
|---|---|---|
| `email` | `String` | Approver's email — verified against JWT on action |
| `status` | `String` | `"pending"` → `"approved"` or `"rejected"` |
| `approvedAt` | `LocalDateTime` | Set when approver approves |
| `rejectedAt` | `LocalDateTime` | Set when approver rejects (dedicated field — not reusing `approvedAt`) |
| `comments` | `String` | Optional text from the approver |
| `submissionMessage` | `String` | Message the contractor sent when assigning the approver |
| `sentAt` | `LocalDateTime` | When this approver was assigned |
| `sentBy` | `String` | Email of the contractor who assigned them |

**On resubmission after approver rejection**, only `status`, `approvedAt`, `rejectedAt`, and `comments` are reset to their initial values. The `email`, `sentAt`, `sentBy`, and `submissionMessage` describe the original assignment event and are not touched.

### 5.4 ModificationRequest — Embedded in modificationRequests[]

Append-only. One entry created per rejection AND per contractor resubmission. Never modified or deleted.

| Field | Type | Description |
|---|---|---|
| `requestedBy` | `String` | Email of who triggered this entry |
| `role` | `String` | `"reviewer"` or `"approver"` or `"contractor"` |
| `message` | `String` | The rejection reason or a fixed resubmission label |
| `requestedAt` | `LocalDateTime` | Timestamp |

**When entries are created:**

| Trigger | role | message |
|---|---|---|
| Reviewer calls `/review/reject` | `"reviewer"` | Reviewer's rejection message |
| Approver calls `/approval/reject` | `"approver"` | Approver's rejection message |
| Contractor resubmits after reviewer rejection | `"contractor"` | `"Resubmitted after reviewer rejection"` |
| Contractor resubmits after approver rejection | `"contractor"` | `"Resubmitted after approver rejection"` |

**Why both reviewer `comments` and `modificationRequests`?** The reviewer's `comments` field gives quick access to the most recent rejection reason for that specific reviewer. `modificationRequests` gives the complete chronological history across all cycles — including earlier rejections that were overwritten on resubmission. Both are needed: one for "show me why this reviewer rejected," one for "show me everything that ever happened."

### 5.5 ContractListResponse DTO

Returned by `GET /contracts` (the owner's own contract list). Contains summary workflow fields.

| Workflow Field | Type | Purpose |
|---|---|---|
| `workflowMode` | `WorkflowMode` | Which mode was selected |
| `reviewStatus` | `ReviewStatus` | Aggregate review state |
| `approvalStatus` | `ApprovalStatus` | Aggregate approval state |

Does **not** include `reviewers[]` or `approver` — these require a detail call.

### 5.6 ContractResponse DTO

Extends `ContractListResponse`. Returned by `GET /contracts/{id}`, all workflow action endpoints, and **`GET /contracts/inbox`**.

Adds:

| Field | Type | Purpose |
|---|---|---|
| `reviewers` | `List<ReviewerInfo>` | All reviewer entries with per-reviewer status |
| `approver` | `ApproverInfo` | Approver entry with their current status |
| `modificationRequests` | `List<ModificationRequest>` | Full rejection and resubmission history |

---

## 6. API Reference — All 9 Endpoints

---

### 6.1 POST `/contracts/{id}/submit`

**Controller:** `ContractWorkflowController`
**Service:** `ContractWorkflowService.submit()`

A single endpoint that handles three cases: fresh submission from `DRAFT`, resubmission after reviewer rejection, and resubmission after approver rejection. The service reads `contract.getStatus()` and branches to the correct private handler.

**Auth:** JWT required. Caller must be the contract owner (`createdBy`). Any other user gets `404 Contract not found` — never `403` — so that a non-owner cannot tell that the contract exists.

**Path Parameter:** `id` — contract ID

**Request Body:**

```json
{
  "mode": "ONLY_REVIEW | ONLY_APPROVE | REVIEW_AND_APPROVE",
  "reviewerEmails": ["reviewer1@example.com", "reviewer2@example.com"],
  "approverEmail": "approver@example.com",
  "reviewerMessage": "Optional message shown to each reviewer",
  "approverMessage": "Optional message shown to the approver"
}
```

**Field behaviour per scenario:**

| Field | Fresh Submit (DRAFT) | Resubmit after reviewer rejection | Resubmit after approver rejection |
|---|---|---|---|
| `mode` | Required — locks the mode | Required — must match original | Required — must match original |
| `reviewerEmails` | Required for ONLY_REVIEW / REVIEW_AND_APPROVE | Required — replaces reviewer list | **Ignored** |
| `approverEmail` | Required for ONLY_APPROVE / REVIEW_AND_APPROVE | **Ignored** — approver preserved | **Ignored** — approver preserved |
| `reviewerMessage` | Optional | Optional — stored on new reviewers | Ignored |
| `approverMessage` | Optional | Ignored | Ignored |

**Service code path — `handleFreshSubmit`:**
1. `validateModeRequirements()` — verifies the right combination of reviewer/approver fields for the chosen mode
2. `validateNoDuplicateReviewers()` — checks for duplicate emails in the reviewer list
3. `validateNoOverlap()` — ensures no email appears in both reviewer and approver slots, and the caller is not in either slot
4. Sets `contract.workflowMode`
5. For modes with review: builds `List<ReviewerInfo>` (each with `status="pending"`, `sentBy=callerEmail`), sets `reviewStatus=PENDING`, `status=IN_REVIEW`
6. For modes with approver: builds `ApproverInfo` (same pattern), sets `approvalStatus=PENDING`
7. For `ONLY_APPROVE` only: sets `status=IN_APPROVAL`

**Service code path — `handleResubmitAfterReviewerRejection`:**
1. Blocks mode change
2. Validates `reviewerEmails` non-empty
3. Validates no duplicates, no overlap with the preserved approver
4. Replaces `contract.reviewers` entirely
5. Sets `reviewStatus=PENDING`, `status=IN_REVIEW`
6. Appends `ModificationRequest { role:"contractor", message:"Resubmitted after reviewer rejection" }`

**Service code path — `handleResubmitAfterApproverRejection`:**
1. Blocks mode change
2. Resets approver: `status→"pending"`, `approvedAt→null`, `rejectedAt→null`, `comments→null`
3. Sets `approvalStatus=PENDING`, `status=IN_APPROVAL`
4. Does **not** touch `reviewers` or `reviewStatus`
5. Appends `ModificationRequest { role:"contractor", message:"Resubmitted after approver rejection" }`

**Status after successful submit:**

| Mode | Resulting Status |
|---|---|
| `ONLY_REVIEW` | `IN_REVIEW` |
| `ONLY_APPROVE` | `IN_APPROVAL` |
| `REVIEW_AND_APPROVE` | `IN_REVIEW` |

**Success Response:** `200 OK` — full `ContractResponse`

**All validation errors:**

| Condition | HTTP | Message |
|---|---|---|
| `mode` missing | 400 | `Workflow mode is required` |
| Mode changed on resubmission | 400 | `Workflow mode cannot be changed on resubmission. Original mode: {X}` |
| ONLY_REVIEW + no reviewerEmails | 400 | `At least one reviewer is required for ONLY_REVIEW mode` |
| ONLY_REVIEW + approverEmail set | 400 | `Approver must not be set for ONLY_REVIEW mode` |
| ONLY_APPROVE + no approverEmail | 400 | `Approver is required for ONLY_APPROVE mode` |
| ONLY_APPROVE + reviewerEmails set | 400 | `Reviewers must not be set for ONLY_APPROVE mode` |
| REVIEW_AND_APPROVE + no reviewerEmails | 400 | `At least one reviewer is required for REVIEW_AND_APPROVE mode` |
| REVIEW_AND_APPROVE + no approverEmail | 400 | `Approver is required for REVIEW_AND_APPROVE mode` |
| Resubmit after reviewer rejection + no reviewerEmails | 400 | `At least one reviewer email is required for resubmission` |
| Blank email in reviewerEmails | 400 | `Reviewer email cannot be blank` |
| Same email in reviewer + approver | 400 | `Approver cannot also be a reviewer: {email}` |
| Caller email in reviewerEmails | 400 | `You cannot assign yourself as a reviewer` |
| Caller email as approverEmail | 400 | `You cannot assign yourself as the approver` |
| Duplicate emails in reviewerEmails | 400 | `Duplicate reviewer emails are not allowed` |
| Contract not DRAFT / REJECTED | 400 | `Contract is not in a submittable state. Current status: {X}` |
| Not found or not owner | 404 | `Contract not found` |

---

### 6.2 POST `/contracts/{id}/review/complete`

**Controller:** `ContractWorkflowController`
**Service:** `ContractWorkflowService.reviewComplete()`

A reviewer marks their review of the contract as complete. If this is the last pending reviewer, the contract automatically advances to the next stage — no separate "advance" call is needed.

**Auth:** JWT required. Caller must be an assigned reviewer with status `"pending"`. The private helper `findPendingReviewer()` enforces this in two steps: (1) verify the email exists in `reviewers[]` at all, (2) find the entry where status is `"pending"` — if they already acted, it throws "already acted."

**Path Parameter:** `id` — contract ID

**Request Body:**
```json
{
  "comments": "Optional review notes"
}
```

`comments` is optional — the reviewer can complete without leaving a note.

**What happens step by step:**

1. Verifies `contract.status == IN_REVIEW`
2. `findPendingReviewer()` locates the caller's entry
3. Sets: `reviewer.status = "reviewed"`, `reviewer.reviewedAt = now`, `reviewer.comments = request.getComments()`
4. Calls `advanceReviewIfComplete(contract)`:

```
Are ALL reviewers non-"pending"?
├── YES → setReviewStatus(COMPLETED)
│         mode == ONLY_REVIEW         → setStatus(READY_FOR_SIGNATURE)
│         mode == REVIEW_AND_APPROVE  → setStatus(IN_APPROVAL), setApprovalStatus(PENDING)
└── NO  → setReviewStatus(IN_PROGRESS)
```

**Success Response:** `200 OK` — full `ContractResponse`

**Errors:**

| Condition | HTTP | Message |
|---|---|---|
| Contract not `IN_REVIEW` | 400 | `Contract is not currently under review` |
| Caller not in reviewers list | 400 | `You are not an assigned reviewer for this contract` |
| Caller already acted | 400 | `You have already acted on this contract` |
| Contract not found | 404 | `Contract not found` |

---

### 6.3 POST `/contracts/{id}/review/forward`

**Controller:** `ContractWorkflowController`
**Service:** `ContractWorkflowService.reviewForward()`

A reviewer completes their own review and simultaneously assigns new reviewers. The contract stays `IN_REVIEW` until all new reviewers (and all still-pending original reviewers) also act. Forwarding is useful when a reviewer determines that other subject matter experts need to sign off before the contract can advance.

**Auth:** JWT required. Caller must be an assigned reviewer with status `"pending"`.

**Path Parameter:** `id` — contract ID

**Request Body:**
```json
{
  "additionalReviewerEmails": ["legal@example.com", "finance@example.com"],
  "message": "Optional message to the new reviewers"
}
```

| Field | Required | Notes |
|---|---|---|
| `additionalReviewerEmails` | Yes (min 1) | `@NotEmpty` enforced via Bean Validation |
| `message` | No | Stored as `submissionMessage` on each new reviewer; `sentBy` is the forwarder, not the contractor |

**What happens step by step:**

1. Verifies `contract.status == IN_REVIEW`
2. `findPendingReviewer()` locates the caller
3. Validation loop over `additionalReviewerEmails`:
   - Blank email → error
   - Forwarder forwarding to themselves → error
   - Email matches **contract creator** → error *(see explanation below)*
   - Email already in `reviewers[]` → error
   - Email matches assigned approver → error
4. Caller's entry: `status = "forwarded"`, `reviewedAt = now`
5. For each new email: new `ReviewerInfo` added to `contract.reviewers[]` with `status="pending"`, `sentBy=callerEmail`, `sentAt=now`, `submissionMessage=request.getMessage()`
6. Sets `reviewStatus = IN_PROGRESS`
7. Contract status stays `IN_REVIEW`
8. `advanceReviewIfComplete()` is NOT called here — forwarding adds pending reviewers, so advancement cannot happen on this call

**Why forwarding to the contract creator is explicitly blocked:**
The contract creator is the contractor who submitted the contract for review. The review process is an independent check of the contractor's own work. If the contractor could be added as a reviewer via forward, they could mark the contract "reviewed" themselves — effectively self-approving. This check is in addition to the "already a reviewer" check because the creator is never in `reviewers[]` to begin with.

**Success Response:** `200 OK` — full `ContractResponse`

**Errors:**

| Condition | HTTP | Message |
|---|---|---|
| Contract not `IN_REVIEW` | 400 | `Contract is not currently under review` |
| Caller not assigned reviewer | 400 | `You are not an assigned reviewer for this contract` |
| Caller already acted | 400 | `You have already acted on this contract` |
| `additionalReviewerEmails` empty | 400 | `At least one reviewer email is required to forward` |
| Blank email in list | 400 | `Reviewer email cannot be blank` |
| Forwarding to self | 400 | `You cannot forward to yourself` |
| Forwarding to contract creator | 400 | `Cannot forward to the contract creator: {email}` |
| Email already a reviewer | 400 | `{email} is already an assigned reviewer` |
| Email matches assigned approver | 400 | `Reviewer cannot also be the approver: {email}` |
| Contract not found | 404 | `Contract not found` |

---

### 6.4 POST `/contracts/{id}/review/reject`

**Controller:** `ContractWorkflowController`
**Service:** `ContractWorkflowService.reviewReject()`

A reviewer rejects the contract and provides a mandatory reason. One rejection immediately moves the contract to `REJECTED_BY_REVIEWER` — the contractor must revise and resubmit. Other pending reviewers do not need to act.

**Why one rejection is sufficient:** If a reviewer identifies a critical issue, there is no value in waiting for other reviewers to complete their review before returning the contract. Sending it back to the contractor as soon as a problem is identified shortens the correction cycle.

**Auth:** JWT required. Caller must be an assigned reviewer with status `"pending"`.

**Path Parameter:** `id` — contract ID

**Request Body:**
```json
{
  "message": "The liability clause in section 4 needs revision before I can proceed."
}
```

`message` is mandatory (`@NotBlank`). A rejection without a reason is not actionable for the contractor.

**What happens step by step:**

1. Verifies `contract.status == IN_REVIEW`
2. `findPendingReviewer()` locates the caller
3. Sets: `reviewer.status = "rejected"`, `reviewer.rejectedAt = now`, `reviewer.comments = message`
4. Sets: `contract.status = REJECTED_BY_REVIEWER`, `contract.reviewStatus = REJECTED`
5. Appends `ModificationRequest { requestedBy: callerEmail, role: "reviewer", message: message, requestedAt: now }`

The message goes into both `reviewer.comments` (quick access per-reviewer) and `modificationRequests` (permanent audit trail).

**Success Response:** `200 OK` — full `ContractResponse`

**Errors:**

| Condition | HTTP | Message |
|---|---|---|
| Contract not `IN_REVIEW` | 400 | `Contract is not currently under review` |
| Caller not assigned reviewer | 400 | `You are not an assigned reviewer for this contract` |
| Caller already acted | 400 | `You have already acted on this contract` |
| `message` blank | 400 | `Rejection message is required` |
| Contract not found | 404 | `Contract not found` |

---

### 6.5 POST `/contracts/{id}/approval/approve`

**Controller:** `ContractWorkflowController`
**Service:** `ContractWorkflowService.approvalApprove()`

The approver approves the contract. This is the final action in the workflow — after approval, the contract moves to `READY_FOR_SIGNATURE` where the e-signature process begins.

**Auth:** JWT required. Caller must be the assigned approver with status `"pending"`. The private helper `validatePendingApprover()` checks: (1) an approver is assigned at all, (2) the approver's email matches the caller's JWT email (case-insensitive), (3) the approver's status is `"pending"`.

**Path Parameter:** `id` — contract ID

**Request Body:**
```json
{
  "comments": "Approved. The contract terms are acceptable."
}
```

`comments` is optional.

**What happens step by step:**

1. Verifies `contract.status == IN_APPROVAL`
2. `validatePendingApprover()` confirms the caller is the correct pending approver
3. Sets: `approver.status = "approved"`, `approver.approvedAt = now`, `approver.comments = request.getComments()`
4. Sets: `contract.status = READY_FOR_SIGNATURE`, `contract.approvalStatus = APPROVED`

No `ModificationRequest` is appended on approval — that array is reserved for events that require the contractor to take corrective action.

**Success Response:** `200 OK` — full `ContractResponse`

**Errors:**

| Condition | HTTP | Message |
|---|---|---|
| Contract not `IN_APPROVAL` | 400 | `Contract is not currently under approval` |
| No approver on contract | 400 | `No approver is assigned to this contract` |
| Caller is not the assigned approver | 400 | `You are not the assigned approver for this contract` |
| Approver already acted | 400 | `You have already acted on this contract` |
| Contract not found | 404 | `Contract not found` |

---

### 6.6 POST `/contracts/{id}/approval/reject`

**Controller:** `ContractWorkflowController`
**Service:** `ContractWorkflowService.approvalReject()`

The approver rejects the contract. Non-terminal — the contractor can revise and resubmit directly to `IN_APPROVAL`. In `REVIEW_AND_APPROVE` mode, the review process is not repeated on resubmission.

**Auth:** JWT required. Caller must be the assigned approver with status `"pending"`.

**Path Parameter:** `id` — contract ID

**Request Body:**
```json
{
  "message": "The payment terms in section 6 need revision."
}
```

`message` is mandatory (`@NotBlank`).

**What happens step by step:**

1. Verifies `contract.status == IN_APPROVAL`
2. `validatePendingApprover()` confirms the caller
3. Sets: `approver.status = "rejected"`, `approver.rejectedAt = now`, `approver.comments = message`
4. Sets: `contract.status = REJECTED_BY_APPROVER`, `contract.approvalStatus = REJECTED`
5. Appends `ModificationRequest { requestedBy: callerEmail, role: "approver", message: message, requestedAt: now }`

**Success Response:** `200 OK` — full `ContractResponse`

**Errors:**

| Condition | HTTP | Message |
|---|---|---|
| Contract not `IN_APPROVAL` | 400 | `Contract is not currently under approval` |
| No approver on contract | 400 | `No approver is assigned to this contract` |
| Caller is not the assigned approver | 400 | `You are not the assigned approver for this contract` |
| Approver already acted | 400 | `You have already acted on this contract` |
| `message` blank | 400 | `Rejection message is required` |
| Contract not found | 404 | `Contract not found` |

---

### 6.7 GET `/contracts/inbox`

**Controller:** `ContractController`
**Service:** `ContractService.getInboxContracts()`
**Repository:** `ContractRepository.findByAssignedToEmail(email)`

Returns all contracts where the authenticated user is assigned as a reviewer OR as the approver, sorted by `createdAt` descending. This is the primary view for reviewers and approvers to see their assigned work.

**Why a separate endpoint from `GET /contracts`?**
`GET /contracts` returns only contracts the caller created (`createdBy` filter). A reviewer or approver sees contracts that belong to someone else's account — they will never appear in `GET /contracts`. The inbox uses a completely different MongoDB query that searches inside embedded documents.

**The MongoDB repository query:**
```java
@Query(
    value = "{ '$or': [ { 'reviewers.email': ?0 }, { 'approver.email': ?0 } ] }",
    sort  = "{ 'createdAt': -1 }"
)
List<Contract> findByAssignedToEmail(String email);
```

`reviewers.email` uses MongoDB's dot-notation on an array — this automatically searches all elements in the array. `approver.email` queries the single embedded object. The `$or` returns the contract if the email appears in either place. One query covers both reviewer and approver roles.

**Why the inbox returns `ContractResponse` instead of `ContractListResponse`:**

`ContractListResponse` has only `workflowMode`, `reviewStatus`, and `approvalStatus`. It does **not** include `reviewers[]` or `approver`. Without those fields, the frontend cannot tell whether the caller is a reviewer or the approver on each card — and therefore cannot render the correct badge or action buttons.

`ContractResponse` (which extends `ContractListResponse`) includes `reviewers[]` and `approver`. The frontend compares the current user's email against these to determine their role on each contract.

**Auth:** JWT required. Returns only contracts relevant to the calling user.

**Success Response:** `200 OK` — `List<ContractResponse>`

Each item includes the full `reviewers[]` array and `approver` object in addition to all standard contract fields. The frontend uses them to determine the caller's role (see Section 9).

**Example curl:**
```bash
curl -X GET "$BASE/contracts/inbox" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
```

**Important — route order in ContractController:**
The `/inbox` mapping must be declared **before** the `/{id}` mapping in the class. Spring matches routes top-to-bottom. If `/{id}` came first, a request to `/contracts/inbox` would match `{id} = "inbox"` and fail with "Contract not found" before ever reaching the inbox handler.

---

### 6.8 GET `/contracts/{id}/file/view-url`

**Controller:** `ContractController`
**Service:** `ContractService.generatePresignedViewUrl()`

Returns a 15-minute presigned MinIO URL used by the frontend to load the contract PDF in the document viewer. Originally this endpoint only served the contract owner. It was extended to also serve reviewers and the approver because they need to read the PDF in order to make a review or approval decision.

**Auth:** JWT required.

**Three-way access check:**
```java
boolean isOwner    = contract.getCreatedBy().equals(email);
boolean isReviewer = contract.getReviewers() != null &&
        contract.getReviewers().stream()
                .anyMatch(r -> r.getEmail().equalsIgnoreCase(email));
boolean isApprover = contract.getApprover() != null &&
        contract.getApprover().getEmail().equalsIgnoreCase(email);

if (!isOwner && !isReviewer && !isApprover) {
    throw new NotFoundException("Contract not found");
}
```

**Why `404` and not `403` for unauthorized access?**
Returning `403 Forbidden` would tell the caller that the contract exists but they cannot access it — an information leak. `404 Contract not found` is the same response they get for a non-existent contract. A user outside the workflow cannot tell whether a contract with that ID exists at all.

**Path Parameter:** `id` — contract ID

**Success Response:** `200 OK`
```json
{
  "url": "https://minio.example.com/contracts/abc123.pdf?X-Amz-Signature=..."
}
```

The URL expires in 15 minutes. The frontend should open it immediately and not cache it for later use.

**Errors:**

| Condition | HTTP | Message |
|---|---|---|
| Caller is not owner, reviewer, or approver | 404 | `Contract not found` |
| File not yet uploaded | 400 | `File not yet uploaded for this contract` |
| Contract not found | 404 | `Contract not found` |

**Example curl:**
```bash
curl -X GET "$BASE/contracts/$CONTRACT_ID/file/view-url" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
```

---

### 6.9 GET `/users`

**Controller:** `UserController`

Returns all registered users except the calling user. Used by the frontend to populate the reviewer and approver picker dropdowns when a contractor submits a contract for workflow.

**Auth:** JWT required. No admin privilege required — any authenticated user can see the list (they need it to assign reviewers and approvers).

**Why the caller is excluded from the list:**
The backend blocks self-assignment at submit time (validation in `validateNoOverlap()`). Showing the caller in the picker would allow them to select themselves and then receive a confusing validation error. Excluding them from the list prevents the error from ever occurring.

**N+1 prevention — Map join pattern:**
Users and profiles are in separate MongoDB collections. Fetching each user's profile individually in a loop would cost `1 + N` database queries. Instead:

```java
// Step 1: fetch ALL profiles in one query, index by email — O(1) lookup later
Map<String, UserProfile> profileMap = userProfileRepository.findAll()
        .stream()
        .collect(Collectors.toMap(UserProfile::getEmail, p -> p));

// Step 2: fetch all users, join with profile map in-memory — total: 2 queries
List<UserListResponse> users = userRepository.findAll()
        .stream()
        .filter(u -> !u.getEmail().equalsIgnoreCase(callerEmail))
        .map(u -> new UserListResponse(u, profileMap.get(u.getEmail())))
        .collect(Collectors.toList());
```

Total: always 2 database queries regardless of how many users exist.

**Security — `UserListResponse` safe fields only:**
The DTO constructor explicitly picks only: `id`, `email`, `fullName` (from profile), `department` (from profile), `organization` (from profile).

Excluded from the response: password hash, PAN card number, Aadhar card number, date of birth, gender, address, and any other PII stored in the User or UserProfile documents.

**Note on null profile:** `UserListResponse` accepts a null `UserProfile`. If the user has not yet created a profile, `fullName`, `department`, and `organization` will be `null` in the response — not an error.

**Success Response:** `200 OK` — `List<UserListResponse>`
```json
[
  {
    "id": "user_abc123",
    "email": "jane.smith@example.com",
    "fullName": "Jane Smith",
    "department": "Legal",
    "organization": "CostaCloud"
  }
]
```

**Example curl:**
```bash
curl -X GET "$BASE/users" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 7. PATCH Guard — Why Editing Is Blocked During Active Workflow

The `PATCH /contracts/{id}` endpoint (metadata update: title, client, description, dates, etc.) has a status guard that blocks edits while the contract is in an active workflow state.

**Location in code:** `ContractService.updateContract()` — the first thing checked after fetching the contract.

```java
ContractStatus s = contract.getStatus();
if (s == ContractStatus.IN_REVIEW
        || s == ContractStatus.IN_APPROVAL
        || s == ContractStatus.READY_FOR_SIGNATURE) {
    throw new BadRequestException(
            "Contract cannot be edited while it is "
            + s.name().toLowerCase().replace("_", " ")
    );
}
```

**Why these three statuses block editing:**

- **`IN_REVIEW`** — Reviewers are actively reading and evaluating the current version. If the contractor edited the metadata while a reviewer was reviewing, the reviewer would be approving content that has silently changed underneath them. This is a data integrity problem.
- **`IN_APPROVAL`** — Same concern: the approver is evaluating the current version.
- **`READY_FOR_SIGNATURE`** — The workflow has completed. The contract is frozen at the version that was reviewed and approved. Editing at this stage would allow changes after the sign-off, which would invalidate the approval.

**Why rejected states DO allow editing:**

`REJECTED_BY_REVIEWER` and `REJECTED_BY_APPROVER` exist specifically for the contractor to revise the contract. If editing were blocked in these states, the contractor could not fix the issues before resubmitting — the entire rejection flow would be pointless. These two states are when editing is most important.

**Why file upload (`PUT /contracts/{id}/file`) does NOT have this guard:**

Uploading a revised PDF is a distinct action from editing metadata. A reviewer may ask the contractor to upload a corrected version of the document while keeping all metadata the same (title, dates, parties have not changed). Blocking file upload in active states would force an unnecessary rejection-resubmit cycle just to deliver a revised PDF. File upload by the owner is always valid.

**PATCH status table:**

| Status | PATCH Allowed? | Error if blocked |
|---|---|---|
| `DRAFT` | Yes | — |
| `IN_REVIEW` | **No** | `400 Contract cannot be edited while it is in review` |
| `IN_APPROVAL` | **No** | `400 Contract cannot be edited while it is in approval` |
| `READY_FOR_SIGNATURE` | **No** | `400 Contract cannot be edited while it is ready for signature` |
| `REJECTED_BY_REVIEWER` | Yes | — |
| `REJECTED_BY_APPROVER` | Yes | — |

---

## 8. Resubmission — All 4 Cases in Detail

Resubmission always uses the same endpoint as initial submit: `POST /contracts/{id}/submit`. The service detects `contract.getStatus()` and branches to the correct handler automatically.

---

### Case 1 — REJECTED_BY_REVIEWER, mode: ONLY_REVIEW

**Trigger:** A reviewer called `/review/reject`.
**Contractor action:** Revise the contract. Call `POST /submit` with `mode: "ONLY_REVIEW"` and new `reviewerEmails`.

| What | What Happens |
|---|---|
| `status` | → `IN_REVIEW` |
| `reviewStatus` | → `PENDING` |
| `reviewers[]` | **Entirely replaced** with new list from request |
| Old rejected reviewer | Removed — unless contractor explicitly re-adds them |
| `approver` | N/A — no approver in ONLY_REVIEW |
| `approvalStatus` | N/A |
| `workflowMode` | Unchanged — locked |
| `modificationRequests[]` | New entry appended: role=`"contractor"`, message=`"Resubmitted after reviewer rejection"` |

The reviewer list is a complete replacement, not a merge. The contractor has full control to route to entirely different reviewers on resubmission — there is no rule that the original rejecting reviewer must be included again.

---

### Case 2 — REJECTED_BY_APPROVER, mode: ONLY_APPROVE

**Trigger:** The approver called `/approval/reject`.
**Contractor action:** Revise the contract. Call `POST /submit` with `mode: "ONLY_APPROVE"`. The `approverEmail` in the request is silently ignored.

| What | What Happens |
|---|---|
| `status` | → `IN_APPROVAL` |
| `approvalStatus` | → `PENDING` |
| `approver.status` | Reset to `"pending"` |
| `approver.approvedAt` | → `null` |
| `approver.rejectedAt` | → `null` |
| `approver.comments` | → `null` |
| `approver.email` | **Unchanged** — same person |
| `approver.sentAt / sentBy / submissionMessage` | **Unchanged** — describe original assignment |
| `reviewers[]` | N/A |
| `reviewStatus` | N/A |
| `modificationRequests[]` | New entry appended |

---

### Case 3 — REJECTED_BY_REVIEWER, mode: REVIEW_AND_APPROVE

**Trigger:** A reviewer called `/review/reject`.
**Contractor action:** Revise. Call `POST /submit` with `mode: "REVIEW_AND_APPROVE"` and new `reviewerEmails`. The `approverEmail` in the request is silently ignored.

| What | What Happens |
|---|---|
| `status` | → `IN_REVIEW` |
| `reviewStatus` | → `PENDING` |
| `reviewers[]` | **Entirely replaced** with new list from request |
| `approver` | **Completely preserved** — same email, same status (`"pending"`), untouched |
| `approvalStatus` | **Unchanged** — still `PENDING` from original submit |
| `workflowMode` | Unchanged |
| `modificationRequests[]` | New entry appended |
| **What happens next** | New reviewers complete review → auto-advance to `IN_APPROVAL` → same approver acts |

The approver does not experience any disruption. From their perspective, the contract will arrive in their inbox when review completes — they may not even know a review rejection happened.

---

### Case 4 — REJECTED_BY_APPROVER, mode: REVIEW_AND_APPROVE

**Trigger:** The approver called `/approval/reject`.
**Contractor action:** Revise. Call `POST /submit` with `mode: "REVIEW_AND_APPROVE"`. Both `reviewerEmails` and `approverEmail` in the request are silently ignored.

| What | What Happens |
|---|---|
| `status` | → `IN_APPROVAL` **directly** — review is skipped |
| `reviewStatus` | **Stays `COMPLETED`** — not reset |
| `reviewers[]` | **Untouched** — still show `"reviewed"` / `"forwarded"` from the completed cycle |
| `approver.status` | Reset to `"pending"` |
| `approver.approvedAt` | → `null` |
| `approver.rejectedAt` | → `null` |
| `approver.comments` | → `null` |
| `approver.email` | **Unchanged** |
| `modificationRequests[]` | New entry appended |

**Why review is not repeated:**
The reviewers' sign-off covered the technical and legal correctness of the contract. The approver's rejection is typically a business-level decision (scope, budget, authority). These are different concerns — one does not invalidate the other. Restarting the review would waste the reviewers' time and annul a sign-off that was never challenged.

---

### Resubmission Quick Reference

| Scenario | Goes to | Reviewer list | Approver | Review repeated? |
|---|---|---|---|---|
| Reviewer rejected — ONLY_REVIEW | `IN_REVIEW` | Replaced from request | N/A | Yes |
| Approver rejected — ONLY_APPROVE | `IN_APPROVAL` | N/A | Same, status reset | N/A |
| Reviewer rejected — REVIEW_AND_APPROVE | `IN_REVIEW` | Replaced from request | Preserved, untouched | Yes |
| Approver rejected — REVIEW_AND_APPROVE | `IN_APPROVAL` | Untouched (COMPLETED) | Same, status reset | **No** |

---

## 9. Frontend Integration Guide

### Determining the Caller's Role on an Inbox Card

The inbox returns `ContractResponse` which includes `reviewers[]` and `approver`. The frontend checks the logged-in user's email against these to determine their role for each card.

```javascript
const currentUserEmail = authStore.user.email; // from your auth/JWT state

function getCallerRole(contract) {
  // Check approver first — a person cannot be both approver and reviewer
  if (contract.approver?.email?.toLowerCase() === currentUserEmail.toLowerCase()) {
    return {
      role: 'approver',
      myStatus: contract.approver.status,   // "pending" | "approved" | "rejected"
      myEntry: contract.approver
    };
  }

  // Check reviewer list
  const reviewerEntry = contract.reviewers?.find(
    r => r.email.toLowerCase() === currentUserEmail.toLowerCase()
  );
  if (reviewerEntry) {
    return {
      role: 'reviewer',
      myStatus: reviewerEntry.status,  // "pending" | "reviewed" | "forwarded" | "rejected"
      myEntry: reviewerEntry
    };
  }

  return { role: null, myStatus: null, myEntry: null };
}
```

### Card Rendering Logic

| `role` | `myStatus` | Badge | Action Buttons |
|---|---|---|---|
| `reviewer` | `pending` | "Pending Review" (orange) | View PDF / Mark as Reviewed / Forward / Reject |
| `reviewer` | `reviewed` | "Reviewed" (green) | View PDF only |
| `reviewer` | `forwarded` | "Forwarded" (blue) | View PDF only |
| `reviewer` | `rejected` | "Rejected" (red) | View PDF only |
| `approver` | `pending` | "Pending Approval" (orange) | View PDF / Approve / Reject |
| `approver` | `approved` | "Approved" (green) | View PDF only |
| `approver` | `rejected` | "Rejected" (red) | View PDF only |

### Inbox vs Sendbox Split

```javascript
function splitInboxSendbox(contracts) {
  const inbox = [], sendbox = [];

  contracts.forEach(contract => {
    const { myStatus } = getCallerRole(contract);
    if (myStatus === 'pending') {
      inbox.push(contract);
    } else {
      sendbox.push(contract);
    }
  });

  return { inbox, sendbox };
}
```

Inbox = contracts where the caller still has a pending action.
Sendbox = contracts where the caller has already acted (reviewed, forwarded, rejected, approved).

### Reading the Rejection Reason (Contractor View)

When a contractor's contract is rejected, they need to see why. The rejection reason is stored in two places — use `modificationRequests` for the most reliable read:

```javascript
function getLatestRejectionReason(contract) {
  if (!contract.modificationRequests?.length) return null;

  // Walk backwards through history, find the last rejection entry
  for (let i = contract.modificationRequests.length - 1; i >= 0; i--) {
    const entry = contract.modificationRequests[i];
    if (entry.role === 'reviewer' || entry.role === 'approver') {
      return {
        rejectedBy: entry.requestedBy,
        role: entry.role,           // "reviewer" or "approver"
        reason: entry.message,
        at: entry.requestedAt
      };
    }
  }
  return null;
}
```

Alternatively, for quick reviewer-specific access: `contract.reviewers.find(r => r.status === 'rejected')?.comments`.

### Determining What Fields to Show on the Submit/Resubmit Form

```javascript
function getSubmitFormConfig(contract, isResubmit) {
  // Fresh submit from DRAFT
  if (!isResubmit) {
    return { showMode: true, showReviewers: true, showApprover: true };
  }

  const status = contract.status;

  if (status === 'REJECTED_BY_REVIEWER') {
    return {
      showMode: false,          // mode is locked — don't show the picker
      showReviewers: true,      // must provide new reviewer list
      showApprover: false,      // approver preserved — don't show
      lockedMode: contract.workflowMode
    };
  }

  if (status === 'REJECTED_BY_APPROVER') {
    return {
      showMode: false,          // mode is locked
      showReviewers: false,     // review already done — not needed
      showApprover: false,      // approver preserved — don't show
      lockedMode: contract.workflowMode
    };
  }
}
```

---

## 10. Complete Status Transition Map

```
                         ┌──────────────────────────────┐
                         │             DRAFT             │
                         └──────────────────────────────┘
                                        │
            ┌───────────────────────────┼───────────────────────────┐
            │                           │                           │
     mode=ONLY_REVIEW       mode=REVIEW_AND_APPROVE        mode=ONLY_APPROVE
            │                           │                           │
            ▼                           ▼                           ▼
        IN_REVIEW                   IN_REVIEW                  IN_APPROVAL
            │                           │                           │
     ┌──────┤                    ┌──────┤                    ┌──────┤
     │      │                   │       │                   │      │
 [all done] [reject]       [all done] [reject]          [approve] [reject]
     │      │                   │       │                   │      │
     ▼      ▼                   ▼       ▼                   ▼      ▼
 READY_ REJ_BY_            IN_APPROVAL  REJ_BY_        READY_ REJ_BY_
 FOR_SIG REVIEWER              │        REVIEWER      FOR_SIG APPROVER
             │             ┌───┤             │                     │
        [resubmit      [approve] [reject]   [resubmit         [resubmit
         new list]         │       │         new list,          same approver
             │             ▼       ▼         approver           resets]
         IN_REVIEW     READY_ REJ_BY_        preserved]             │
      (review          FOR_SIG APPROVER          │              IN_APPROVAL
       restarts)                    │        IN_REVIEW         (review NOT
                              [resubmit      (review           repeated)
                               same approver  restarts)
                               resets, review
                               NOT repeated]
                                    │
                               IN_APPROVAL

─── After READY_FOR_SIGNATURE ──────────────────────────────────────────────
READY_FOR_SIGNATURE → IN_SIGNATURE → ACTIVE → EXPIRING → EXPIRED
Any active state → TERMINATED (admin / manual action)
```

---

## 11. Business Rules Index

| # | Rule | Reason |
|---|---|---|
| 1 | Caller identity always from JWT, never from request body | Prevents impersonation — any user could claim to be any reviewer if email were in the body |
| 2 | Workflow mode locked after first submit | Prevents bypassing a rejected review step by switching modes |
| 3 | One reviewer rejection moves contract to REJECTED immediately | No point waiting for others when an issue is identified — return to contractor immediately |
| 4 | Reviewer list entirely replaced on resubmit after reviewer rejection | Contractor needs full control to route to different reviewers |
| 5 | Approver cannot be changed on any resubmission | Prevents routing around a disapproving authority by switching to a friendlier approver |
| 6 | Approver rejection resubmit goes to IN_APPROVAL, not IN_REVIEW | Review sign-off is not invalidated by a separate business-level approver concern |
| 7 | `modificationRequests[]` is append-only, never deleted or modified | Permanent audit trail for legal and compliance |
| 8 | Contract creator cannot be added as reviewer via forward | Prevents owner from reviewing their own contract — review must be independent |
| 9 | Reviewer and approver cannot be the same person | Prevents a single person from both reviewing and approving |
| 10 | PATCH metadata blocked in IN_REVIEW, IN_APPROVAL, READY_FOR_SIGNATURE | Prevents editing the contract while reviewers/approver are actively evaluating it |
| 11 | File upload (PUT) not blocked in active workflow states | Contractor may need to upload a revised PDF without requiring a formal resubmission |
| 12 | Inbox returns ContractResponse, not ContractListResponse | Frontend needs reviewers[] and approver fields to determine caller's role per card |
| 13 | GET /users excludes the caller | Cannot assign yourself — no point showing yourself in the picker |
| 14 | GET /users uses Map join to avoid N+1 query | In-memory join after 2 queries is always faster than 1+N queries in a loop |
| 15 | Unauthorized contract access returns 404, not 403 | 403 would reveal the contract exists — 404 leaks nothing |

---

## 12. Complete Error Reference

**Standard error response shape:**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Human-readable description of what went wrong"
}
```

### Submit Errors

| Message | HTTP |
|---|---|
| `Workflow mode is required` | 400 |
| `Workflow mode cannot be changed on resubmission. Original mode: {X}` | 400 |
| `At least one reviewer is required for ONLY_REVIEW mode` | 400 |
| `Approver must not be set for ONLY_REVIEW mode` | 400 |
| `Approver is required for ONLY_APPROVE mode` | 400 |
| `Reviewers must not be set for ONLY_APPROVE mode` | 400 |
| `At least one reviewer is required for REVIEW_AND_APPROVE mode` | 400 |
| `Approver is required for REVIEW_AND_APPROVE mode` | 400 |
| `At least one reviewer email is required for resubmission` | 400 |
| `Reviewer email cannot be blank` | 400 |
| `Approver cannot also be a reviewer: {email}` | 400 |
| `You cannot assign yourself as a reviewer` | 400 |
| `You cannot assign yourself as the approver` | 400 |
| `Duplicate reviewer emails are not allowed` | 400 |
| `Contract is not in a submittable state. Current status: {X}` | 400 |
| `Contract not found` | 404 |

### Review Errors

| Message | HTTP | Endpoint |
|---|---|---|
| `Contract is not currently under review` | 400 | complete, forward, reject |
| `You are not an assigned reviewer for this contract` | 400 | complete, forward, reject |
| `You have already acted on this contract` | 400 | complete, forward, reject |
| `At least one reviewer email is required to forward` | 400 | forward |
| `Reviewer email cannot be blank` | 400 | forward |
| `You cannot forward to yourself` | 400 | forward |
| `Cannot forward to the contract creator: {email}` | 400 | forward |
| `{email} is already an assigned reviewer` | 400 | forward |
| `Reviewer cannot also be the approver: {email}` | 400 | forward |
| `Rejection message is required` | 400 | reject |
| `Contract not found` | 404 | complete, forward, reject |

### Approval Errors

| Message | HTTP | Endpoint |
|---|---|---|
| `Contract is not currently under approval` | 400 | approve, reject |
| `No approver is assigned to this contract` | 400 | approve, reject |
| `You are not the assigned approver for this contract` | 400 | approve, reject |
| `You have already acted on this contract` | 400 | approve, reject |
| `Rejection message is required` | 400 | reject |
| `Contract not found` | 404 | approve, reject |

### Supporting Endpoint Errors

| Message | HTTP | Endpoint |
|---|---|---|
| `Contract not found` | 404 | GET /contracts/{id}/file/view-url (access denied or not found) |
| `File not yet uploaded for this contract` | 400 | GET /contracts/{id}/file/view-url |
| `Contract cannot be edited while it is in review` | 400 | PATCH /contracts/{id} |
| `Contract cannot be edited while it is in approval` | 400 | PATCH /contracts/{id} |
| `Contract cannot be edited while it is ready for signature` | 400 | PATCH /contracts/{id} |

---

## 13. End-to-End Testing Guide

### Postman Environment Setup

Create an environment named `Contract Management — Local`:

| Variable | How to Set | Value |
|---|---|---|
| `BASE` | Manual | `http://localhost:8080` |
| `OWNER_TOKEN` | Login post-script | JWT for the contract owner |
| `REVIEWER_TOKEN` | Login post-script | JWT for reviewer 1 |
| `REVIEWER2_TOKEN` | Login post-script | JWT for reviewer 2 |
| `APPROVER_TOKEN` | Login post-script | JWT for approver |
| `CONTRACT_ID` | Create contract post-script | Auto-captured from POST /contracts response |

**Login post-response script** (use one per user login request):
```javascript
pm.environment.set("OWNER_TOKEN", pm.response.json().token);
// Change to REVIEWER_TOKEN / APPROVER_TOKEN for other user logins
```

**Create contract post-response script:**
```javascript
pm.environment.set("CONTRACT_ID", pm.response.json().id);
```

---

### Flow 1 — ONLY_REVIEW Happy Path

```bash
# Step 1: Owner submits to reviewer
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ONLY_REVIEW","reviewerEmails":["reviewer@example.com"],"reviewerMessage":"Please review"}'
# Expect: status=IN_REVIEW, reviewStatus=PENDING

# Step 2: Reviewer checks inbox (must see the contract)
curl -X GET "$BASE/contracts/inbox" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
# Expect: list with 1 item, reviewers[0].status="pending"

# Step 3: Reviewer opens the PDF
curl -X GET "$BASE/contracts/$CONTRACT_ID/file/view-url" \
  -H "Authorization: Bearer $REVIEWER_TOKEN"
# Expect: 200 with presigned URL

# Step 4: Reviewer marks as reviewed → auto-advances
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/complete" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"All looks good"}'
# Expect: status=READY_FOR_SIGNATURE, reviewStatus=COMPLETED
```

---

### Flow 2 — ONLY_APPROVE Happy Path

```bash
# Step 1: Owner submits to approver
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ONLY_APPROVE","approverEmail":"approver@example.com"}'
# Expect: status=IN_APPROVAL, approvalStatus=PENDING

# Step 2: Approver approves
curl -X POST "$BASE/contracts/$CONTRACT_ID/approval/approve" \
  -H "Authorization: Bearer $APPROVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"Approved"}'
# Expect: status=READY_FOR_SIGNATURE, approvalStatus=APPROVED
```

---

### Flow 3 — REVIEW_AND_APPROVE Full Happy Path (2 reviewers + approver)

```bash
# Step 1: Owner submits
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"REVIEW_AND_APPROVE","reviewerEmails":["r1@example.com","r2@example.com"],"approverEmail":"approver@example.com"}'
# Expect: status=IN_REVIEW, reviewStatus=PENDING, approvalStatus=PENDING

# Step 2: Reviewer 1 completes (reviewer 2 still pending)
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/complete" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
# Expect: status=IN_REVIEW, reviewStatus=IN_PROGRESS

# Step 3: Reviewer 2 completes (all done → auto-advance to approval)
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/complete" \
  -H "Authorization: Bearer $REVIEWER2_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"Looks good"}'
# Expect: status=IN_APPROVAL, reviewStatus=COMPLETED

# Step 4: Approver approves
curl -X POST "$BASE/contracts/$CONTRACT_ID/approval/approve" \
  -H "Authorization: Bearer $APPROVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"Approved"}'
# Expect: status=READY_FOR_SIGNATURE, approvalStatus=APPROVED
```

---

### Flow 4 — Reviewer Rejects → Contractor Revises → Resubmits

```bash
# Step 1: Submit
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ONLY_REVIEW","reviewerEmails":["reviewer@example.com"]}'

# Step 2: Reviewer rejects
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/reject" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Section 4 payment terms need revision"}'
# Expect: status=REJECTED_BY_REVIEWER, reviewStatus=REJECTED, modificationRequests[0].role="reviewer"

# Step 3: Owner edits metadata (allowed — not blocked in REJECTED state)
curl -X PATCH "$BASE/contracts/$CONTRACT_ID" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"Revised — updated payment terms in section 4"}'
# Expect: 200 OK

# Step 4: Owner resubmits with new reviewer list
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ONLY_REVIEW","reviewerEmails":["reviewer@example.com"],"reviewerMessage":"Section 4 addressed"}'
# Expect: status=IN_REVIEW, reviewStatus=PENDING, modificationRequests has 2 entries
```

---

### Flow 5 — Mode Change Attempt on Resubmission (Must Fail)

```bash
# After REJECTED_BY_REVIEWER (mode was ONLY_REVIEW), try to change to ONLY_APPROVE
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ONLY_APPROVE","approverEmail":"approver@example.com"}'
# Expect: 400 — "Workflow mode cannot be changed on resubmission. Original mode: ONLY_REVIEW"
```

---

### Flow 6 — Approver Rejects → Resubmit Skips Review (REVIEW_AND_APPROVE)

```bash
# Step 1: Submit
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"REVIEW_AND_APPROVE","reviewerEmails":["reviewer@example.com"],"approverEmail":"approver@example.com"}'

# Step 2: Reviewer completes → auto-advance to IN_APPROVAL
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/complete" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"All good"}'
# Expect: status=IN_APPROVAL

# Step 3: Approver rejects
curl -X POST "$BASE/contracts/$CONTRACT_ID/approval/reject" \
  -H "Authorization: Bearer $APPROVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Liability cap too low — revise section 8"}'
# Expect: status=REJECTED_BY_APPROVER, approvalStatus=REJECTED

# Step 4: Owner resubmits — no reviewerEmails needed
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"REVIEW_AND_APPROVE"}'
# Expect: status=IN_APPROVAL (NOT IN_REVIEW), reviewStatus=COMPLETED (unchanged)

# Step 5: Verify reviewer entries are untouched
curl -X GET "$BASE/contracts/$CONTRACT_ID" \
  -H "Authorization: Bearer $OWNER_TOKEN"
# Expect: reviewers[0].status="reviewed" — not reset

# Step 6: Approver approves on second attempt
curl -X POST "$BASE/contracts/$CONTRACT_ID/approval/approve" \
  -H "Authorization: Bearer $APPROVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"Now acceptable"}'
# Expect: status=READY_FOR_SIGNATURE
```

---

### Flow 7 — Reviewer Forwards to Additional Reviewer

```bash
# Step 1: Submit with 1 reviewer
curl -X POST "$BASE/contracts/$CONTRACT_ID/submit" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ONLY_REVIEW","reviewerEmails":["r1@example.com"]}'

# Step 2: R1 forwards to legal
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/forward" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"additionalReviewerEmails":["legal@example.com"],"message":"Please check the legal clauses"}'
# Expect: r1.status="forwarded", new entry legal@example.com added with status="pending"

# Step 3: Legal sees it in their inbox
curl -X GET "$BASE/contracts/inbox" \
  -H "Authorization: Bearer $LEGAL_TOKEN"
# Expect: the contract appears, legal's reviewer entry with status="pending"

# Step 4: Legal completes — all reviewers now non-pending → READY_FOR_SIGNATURE
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/complete" \
  -H "Authorization: Bearer $LEGAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"Legal approved"}'
# Expect: status=READY_FOR_SIGNATURE, reviewStatus=COMPLETED
```

---

### Flow 8 — Forward to Contract Creator (Must Fail)

```bash
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/forward" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"additionalReviewerEmails":["owner@example.com"]}'
# Expect: 400 — "Cannot forward to the contract creator: owner@example.com"
```

---

### Flow 9 — Edit While In Review (Must Fail)

```bash
# While status=IN_REVIEW:
curl -X PATCH "$BASE/contracts/$CONTRACT_ID" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"Trying to edit while under review"}'
# Expect: 400 — "Contract cannot be edited while it is in review"
```

---

### Flow 10 — Unrelated User Tries to Get View URL (Must Return 404)

```bash
# A user who is neither owner, reviewer, nor approver
curl -X GET "$BASE/contracts/$CONTRACT_ID/file/view-url" \
  -H "Authorization: Bearer $RANDOM_USER_TOKEN"
# Expect: 404 — "Contract not found" (no information leak that contract exists)
```

---

### Flow 11 — Reviewer Tries to Approve (Wrong Role — Must Fail)

```bash
# A reviewer calling the approval endpoint (role mismatch)
curl -X POST "$BASE/contracts/$CONTRACT_ID/approval/approve" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"Trying to approve as reviewer"}'
# If contract is IN_APPROVAL: 400 — "You are not the assigned approver for this contract"
# If contract is IN_REVIEW:   400 — "Contract is not currently under approval"
```

---

### Flow 12 — Same Reviewer Tries to Act Twice (Must Fail)

```bash
# First action succeeds
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/complete" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"comments":"First action"}'
# Expect: 200 OK

# Second action on same contract by same reviewer
curl -X POST "$BASE/contracts/$CONTRACT_ID/review/reject" \
  -H "Authorization: Bearer $REVIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Trying to reject after already completing"}'
# Expect: 400 — "You have already acted on this contract"
```
