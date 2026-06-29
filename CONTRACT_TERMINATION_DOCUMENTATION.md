# Contract Termination — API Documentation

**Project:** Contract Management System  
**Version:** 1.0.0  
**Base URL:** `http://localhost:8080`  
**Last Updated:** 2026-06-22  
**Scope:** Contract termination endpoint, business rules, status architecture, frontend contract  

---

## Table of Contents

1. [Overview](#1-overview)
2. [Status Architecture — DB vs Computed](#2-status-architecture--db-vs-computed)
   - 2.1 [Why SIGNED is Stored, Not EXPIRED](#21-why-signed-is-stored-not-expired)
   - 2.2 [How EXPIRED is Determined](#22-how-expired-is-determined)
3. [Business Rules](#3-business-rules)
4. [Authentication & Authorization](#4-authentication--authorization)
5. [API Endpoint](#5-api-endpoint)
   - 5.1 [Terminate Contract](#51-terminate-contract)
6. [Response Reference](#6-response-reference)
   - 6.1 [Success Response](#61-success-response)
   - 6.2 [Idempotent Response](#62-idempotent-response)
   - 6.3 [Error Responses](#63-error-responses)
7. [Termination Flow](#7-termination-flow)
   - 7.1 [End-to-End Flow Diagram](#71-end-to-end-flow-diagram)
   - 7.2 [Service Layer Decision Tree](#72-service-layer-decision-tree)
8. [Fields Added to Contract](#8-fields-added-to-contract)
   - 8.1 [Termination Fields](#81-termination-fields)
   - 8.2 [Renewal Fields (Added for Future Use)](#82-renewal-fields-added-for-future-use)
9. [Contract Response After Termination](#9-contract-response-after-termination)
10. [Frontend Contract](#10-frontend-contract)
    - 10.1 [What the Frontend Sends](#101-what-the-frontend-sends)
    - 10.2 [What the Frontend Receives](#102-what-the-frontend-receives)
    - 10.3 [What the Frontend Should NOT Do](#103-what-the-frontend-should-not-do)
11. [Implementation Details](#11-implementation-details)
    - 11.1 [Files Changed](#111-files-changed)
    - 11.2 [computeEffectiveStatusLabel Logic](#112-computeeffectivestatuslabel-logic)
    - 11.3 [Why JWT Email is Used, Not Request Body](#113-why-jwt-email-is-used-not-request-body)
12. [Testing Guide](#12-testing-guide)
    - 12.1 [Test Scenarios](#121-test-scenarios)
    - 12.2 [Testing via curl](#122-testing-via-curl)
    - 12.3 [Run Unit Tests](#123-run-unit-tests)

---

## 1. Overview

Contract termination is a **manual, permanent, irreversible action** that allows a contract owner to formally end a contract that has passed its end date (`EXPIRED`). Once a contract is terminated:

- Its status permanently changes to `TERMINATED` in the database.
- The termination timestamp and acting user are recorded.
- Any in-progress renewal linkage is cleared.
- The status can never be changed again.

### When to Terminate

Termination is intended for contracts that have already expired (past their `endDate`) but still need to be formally closed in the system — for legal record-keeping, compliance tracking, or to explicitly mark them as closed rather than just expired.

### What Termination Is NOT

| Not for | Use instead |
|---|---|
| Ending an ACTIVE contract early | Not supported — only EXPIRED contracts can be terminated |
| Cancelling a contract that is still in signing | Abort the signature flow |
| Rejecting during review or approval | Use the review/approval reject endpoints |
| Undoing a termination | Not possible — termination is irreversible |

---

## 2. Status Architecture — DB vs Computed

Understanding this is essential before working with the termination endpoint.

### 2.1 Why SIGNED is Stored, Not EXPIRED

The database **always stores `SIGNED`** for any finalized contract — it never writes `ACTIVE`, `EXPIRING`, or `EXPIRED` to the database. These three values are **computed at read time** from the contract's `startDate`, `endDate`, and the current date.

```
MongoDB document:             API response:
────────────────              ─────────────
status: "SIGNED"    →  GET    status: "ACTIVE"   (if running, >30 days left)
status: "SIGNED"    →  GET    status: "EXPIRING" (if endDate within 30 days)
status: "SIGNED"    →  GET    status: "EXPIRED"  (if endDate is in the past)
status: "SIGNED"    →  GET    status: "SIGNED"   (if startDate is in future)
```

This means:
- The **termination eligibility check** cannot read `contract.status == EXPIRED` from the database — the DB value will be `SIGNED`.
- The backend must **compute** the effective status from dates at the time of the termination request.

### 2.2 How EXPIRED is Determined

A contract is considered `EXPIRED` when **all three** of these are true:

| Condition | Detail |
|---|---|
| `contract.status == SIGNED` in DB | The contract has been finalized |
| `contract.endDate != null` | An end date was set |
| `contract.endDate < today` | The end date has passed |

```
Effective status computation (mirrors ContractListResponse):

  stored != SIGNED             →  return stored value as-is (DRAFT, IN_REVIEW, etc.)
  endDate == null              →  SIGNED  (no date to compute from)
  endDate < today              →  EXPIRED ← only state eligible for termination
  expiresInDays ≤ 30 days      →  EXPIRING
  startDate ≤ today            →  ACTIVE
  startDate > today            →  SIGNED  (not yet started)
```

---

## 3. Business Rules

| Rule | Detail |
|---|---|
| **Only EXPIRED contracts can be terminated** | The effective computed status must be `EXPIRED`. Any other status — ACTIVE, EXPIRING, DRAFT, IN_REVIEW, IN_APPROVAL, READY_FOR_SIGNATURE, IN_SIGNATURE, SIGNED_BY_EVERYONE, REJECTED_* — returns 400. |
| **Termination is irreversible** | Once status is set to `TERMINATED`, no endpoint changes it back. |
| **TERMINATED is idempotent** | Calling terminate on an already-terminated contract returns `{ success: true, alreadyTerminated: true }` with HTTP 200 — no error is thrown. |
| **Active renewal blocks termination** | If the contract has a renewal draft in progress (`renewalStatus == "in_progress"`), termination is blocked with HTTP 409. The renewal must be cancelled or completed first. |
| **Ownership enforced** | Only the contract owner (`createdBy`) can terminate. Any other authenticated user receives 404 (not 403) to prevent leaking contract IDs. |
| **terminatedBy is taken from JWT** | The acting user's email is read from the JWT token — the request body is empty. The frontend does not supply `terminatedBy`. |
| **Renewal linkage is cleared on success** | `renewalStatus` and `renewedContractId` are set to `null` on the terminated contract. |
| **terminatedAt is server-set** | The termination timestamp is always set by the server (`LocalDateTime.now()`) — the client never provides it. |

---

## 4. Authentication & Authorization

All termination requests require a valid JWT token.

```
Authorization: Bearer <token>
```

| Check | Behaviour on Failure |
|---|---|
| No token / expired token | 401 Unauthorized |
| Valid token, contract not found | 404 Not Found |
| Valid token, contract exists but belongs to another user | 404 Not Found (ownership is hidden) |
| Valid token, contract owned by caller | Proceed to business rule checks |

---

## 5. API Endpoint

### 5.1 Terminate Contract

Permanently terminates an expired contract. No request body is needed — the acting user is identified entirely from the JWT token.

```
POST /contracts/{id}/terminate
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Type | Description |
|---|---|---|
| id | String | MongoDB ID of the contract to terminate |

**Request Body**

None. The endpoint requires no body. The `terminatedBy` value is derived from the JWT token.

---

## 6. Response Reference

### 6.1 Success Response — `200 OK`

Returned when the contract was `EXPIRED` and has been successfully terminated.

```json
{
  "success": true,
  "alreadyTerminated": false
}
```

| Field | Type | Description |
|---|---|---|
| `success` | boolean | Always `true` on a 200 response |
| `alreadyTerminated` | boolean | `false` — contract was just terminated now |

### 6.2 Idempotent Response — `200 OK`

Returned when the contract was **already** `TERMINATED` before this call. No changes are made to the database.

```json
{
  "success": true,
  "alreadyTerminated": true
}
```

| Field | Type | Description |
|---|---|---|
| `success` | boolean | `true` — the desired end state is already achieved |
| `alreadyTerminated` | boolean | `true` — contract was terminated in a previous request |

> This is intentionally not a 4xx error. The termination goal has already been achieved — returning success is the correct semantic.

### 6.3 Error Responses

All error responses follow the standard error envelope:

```json
{
  "status": <http_code>,
  "error": "<error_type>",
  "message": "<detail>"
}
```

#### 400 — Wrong Status

Returned when the contract's computed effective status is anything other than `EXPIRED`. The message includes the actual computed status so the caller knows what state the contract is in.

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Cannot terminate a contract with status \"ACTIVE\". Only expired contracts can be terminated."
}
```

The quoted status in the message reflects the **computed** presentation status — not the raw `SIGNED` stored in the database. Possible values in the message:

| Quoted status | Meaning |
|---|---|
| `"ACTIVE"` | Contract is running, endDate is more than 30 days away |
| `"EXPIRING"` | endDate is within 30 days |
| `"SIGNED"` | Finalized but startDate is in the future, or no endDate set |
| `"DRAFT"` | Contract not yet submitted |
| `"IN_REVIEW"` | Under review workflow |
| `"IN_APPROVAL"` | Under approval workflow |
| `"READY_FOR_SIGNATURE"` | Approved, awaiting signature initiation |
| `"IN_SIGNATURE"` | Signature round in progress |
| `"SIGNED_BY_EVERYONE"` | All signed, awaiting finalization |
| `"REJECTED_BY_REVIEWER"` | Rejected at review stage |
| `"REJECTED_BY_APPROVER"` | Rejected at approval stage |

#### 404 — Contract Not Found

Returned when the contract ID does not exist, or exists but belongs to a different user.

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Contract not found"
}
```

> 404 is returned for both "not found" and "wrong owner" to prevent contract ID enumeration attacks.

#### 409 — Renewal In Progress

Returned when the contract has an active renewal draft (`renewalStatus == "in_progress"`). The renewal must be cancelled or completed before termination is allowed.

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Cannot terminate a contract that has an active renewal in progress. Cancel or complete the renewal first."
}
```

#### 401 — Unauthorized

Returned when the `Authorization` header is missing or the token has expired.

```
HTTP 401 Unauthorized
```

---

## 7. Termination Flow

### 7.1 End-to-End Flow Diagram

```
Frontend                        Spring Boot                      MongoDB
────────                        ───────────                      ───────
POST /contracts/{id}/terminate
Authorization: Bearer <token>
(no body)
        │
        │
        ▼
                        JwtAuthFilter
                        - Validates token signature
                        - Extracts email from sub claim
                        - Sets SecurityContext
                                │
                                ▼
                        ContractController
                        .terminateContract(id)
                        - Reads email from SecurityContext
                                │
                                ▼
                        ContractService
                        .terminateContract(id, email)
                                │
                                ▼
                        findByIdAndOwner(id, email)
                        ─────────────────────────────────────► findById(id)
                                                              ◄─────────────
                        if not found OR createdBy != email
                        → throw NotFoundException(404)
                                │
                                ▼
                        contract.status == TERMINATED?
                        ├── YES → return { success:true, alreadyTerminated:true }
                        └── NO ──────────────────────────────────────────────────┐
                                                                                 │
                                                                                 ▼
                                                                    computeEffectiveStatusLabel(contract)
                                                                    - Checks stored status + dates
                                                                    - Returns "EXPIRED", "ACTIVE", etc.
                                                                                 │
                                                                    effective != "EXPIRED"?
                                                                    ├── YES → throw BadRequestException(400)
                                                                    └── NO ──────┐
                                                                                 │
                                                                    renewalStatus == "in_progress"?
                                                                    ├── YES → throw ConflictException(409)
                                                                    └── NO ──────┐
                                                                                 │
                                                                    contract.status    = TERMINATED
                                                                    contract.terminatedAt = now()
                                                                    contract.terminatedBy = jwtEmail
                                                                    contract.renewalStatus = null
                                                                    contract.renewedContractId = null
                                                                    contract.updatedAt = now()
                                                                    ─────────────────────────────► save(contract)
                                                                                 │
                        ◄────────────────────────────────────────────────────────┘
{ "success": true,
  "alreadyTerminated": false }
```

### 7.2 Service Layer Decision Tree

```
terminateContract(id, email)
│
├─ Step 1: findByIdAndOwner(id, email)
│     ├─ Contract not found    → 404 "Contract not found"
│     └─ Not owner             → 404 "Contract not found"  (ownership hidden)
│
├─ Step 2: Is status TERMINATED?
│     └─ YES → return { success:true, alreadyTerminated:true }  (stop here, no DB write)
│
├─ Step 3: computeEffectiveStatusLabel()
│     ├─ stored status NOT in [SIGNED, ACTIVE, EXPIRING, EXPIRED]
│     │     → return stored status name (DRAFT, IN_REVIEW, etc.)
│     ├─ endDate == null        → "SIGNED"
│     ├─ endDate < today        → "EXPIRED"  ✓ (only value that proceeds)
│     ├─ expiresInDays ≤ 30     → "EXPIRING"
│     ├─ startDate ≤ today      → "ACTIVE"
│     └─ startDate > today      → "SIGNED"
│
│     effectiveStatus != "EXPIRED"?
│     └─ YES → 400 "Cannot terminate a contract with status \"<effectiveStatus>\".
│                    Only expired contracts can be terminated."
│
├─ Step 4: renewalStatus == "in_progress"?
│     └─ YES → 409 "Cannot terminate a contract that has an active renewal in progress..."
│
└─ Step 5: Persist termination
      contract.status          = TERMINATED
      contract.terminatedAt    = LocalDateTime.now()
      contract.terminatedBy    = jwtEmail
      contract.renewalStatus   = null
      contract.renewedContractId = null
      contract.updatedAt       = LocalDateTime.now()
      contractRepository.save(contract)
      return { success:true, alreadyTerminated:false }
```

---

## 8. Fields Added to Contract

### 8.1 Termination Fields

These fields are set on the `Contract` MongoDB document when termination succeeds. Both are `null` on all other contracts.

| Field | Type | Set By | Description |
|---|---|---|---|
| `terminatedAt` | `LocalDateTime` | Server — `LocalDateTime.now()` | Timestamp when the termination was executed. Never supplied by the client. |
| `terminatedBy` | `String` | JWT token — email claim | Email of the user who triggered termination. Always the contract owner. |

**MongoDB document after termination:**

```json
{
  "status":        "TERMINATED",
  "terminatedAt":  "2026-06-22T16:45:00.000",
  "terminatedBy":  "admin@gmail.com",
  "renewalStatus": null,
  "renewedContractId": null,
  "updatedAt":     "2026-06-22T16:45:00.000"
}
```

### 8.2 Renewal Fields (Added for Future Use)

These fields are added to the `Contract` model now because:
1. `renewalStatus` is needed **immediately** for the 409 termination guard.
2. `renewedContractId` must be cleared (`null`) on successful termination per spec.
3. The remaining fields (`renewedFromId`, `renewalStartDate`, `renewalNotes`) are needed by the upcoming renewal feature and are added now to avoid a future schema migration.

| Field | Type | Used By | Description |
|---|---|---|---|
| `renewalStatus` | `String \| null` | Termination (409 guard) | `"in_progress"` when a renewal draft has been saved. `null` otherwise. Cleared on termination. |
| `renewedContractId` | `String \| null` | Termination (cleared on success) | ID of the renewal draft contract created from this one. Cleared on termination. |
| `renewedFromId` | `String \| null` | Future renewal feature | ID of the original contract this was renewed from. Set when creating a renewal draft. |
| `renewalStartDate` | `String \| null` | Future renewal feature | ISO date string — start date of the pending renewal, shown as tooltip in UI. |
| `renewalNotes` | `String \| null` | Future renewal feature | Optional notes entered at renewal creation time. |

> `renewedFromId`, `renewalStartDate`, and `renewalNotes` are inert until the renewal feature is implemented. They are `null` for all existing contracts.

---

## 9. Contract Response After Termination

All contract response DTOs (`ContractListResponse`, `ContractResponse`) now include the termination and renewal fields. These are returned as `null` for non-terminated contracts and populated for terminated ones.

**Sample `GET /contracts/{id}` response after termination:**

```json
{
  "id":              "6a38c355b975764806422a7b",
  "title":           "T04",
  "client":          "Test",
  "status":          "TERMINATED",
  "startDate":       "2025-06-21",
  "endDate":         "2026-06-22",
  "expiresInDays":   0,
  "createdBy":       "admin@gmail.com",
  "createdAt":       "2026-06-22T05:08:37",
  "updatedAt":       "2026-06-22T16:45:00",

  "terminatedAt":    "2026-06-22T16:45:00",
  "terminatedBy":    "admin@gmail.com",

  "renewalStatus":        null,
  "renewedContractId":    null,
  "renewedFromId":        null,
  "renewalStartDate":     null,
  "renewalNotes":         null
}
```

**Field behaviour post-termination:**

| Field | Value |
|---|---|
| `status` | `"TERMINATED"` — stored in DB, passes through status computation unchanged |
| `terminatedAt` | ISO datetime string of when it was terminated |
| `terminatedBy` | Email of the contract owner who terminated it |
| `renewalStatus` | `null` — cleared on termination |
| `renewedContractId` | `null` — cleared on termination |
| `expiresInDays` | Still computed from endDate — negative value since endDate is in the past |

---

## 10. Frontend Contract

### 10.1 What the Frontend Sends

```
POST /contracts/{id}/terminate
Authorization: Bearer <token>
(no request body)
```

No JSON body is required or expected. The `terminatedBy` value that the old Next.js internal route required in the body is now derived entirely from the JWT token on the backend.

> **Migration note:** The old internal Next.js route required `{ "terminatedBy": "email" }` in the body because it had no JWT auth. If the frontend still sends a body, it will be silently ignored — no error is thrown.

### 10.2 What the Frontend Receives

**On success:**
```json
{ "success": true, "alreadyTerminated": false }
```

**If already terminated:**
```json
{ "success": true, "alreadyTerminated": true }
```

After either success response, the frontend calls `loadContracts()` to refresh the contract list. The terminated contract will now show with `status: "TERMINATED"` and a grey badge.

### 10.3 What the Frontend Should NOT Do

| Do NOT | Reason |
|---|---|
| Send `terminatedBy` in the request body | It is ignored. The backend uses the JWT email. |
| Allow the Terminate button for ACTIVE or EXPIRING contracts | The backend will return 400. Guard the button by checking `contract.status === "EXPIRED"` from the API response. |
| Allow the Terminate button when `contract.renewalStatus === "in_progress"` | The backend will return 409. Disable or hide the Terminate button when a renewal is in flight. |
| Re-enable the Terminate button after a 409 error | Show an error message explaining that the renewal must be resolved first. |
| Attempt to change status back from TERMINATED | No endpoint supports undoing termination. |

---

## 11. Implementation Details

### 11.1 Files Changed

| File | Change |
|---|---|
| `model/Contract.java` | Added `terminatedAt`, `terminatedBy`, `renewalStatus`, `renewedContractId`, `renewedFromId`, `renewalStartDate`, `renewalNotes` |
| `dto/TerminationResponse.java` | **New file** — `{ success, alreadyTerminated }` |
| `dto/ContractListResponse.java` | Added all 7 new fields to declared fields and constructor mapping |
| `service/ContractService.java` | Added `terminateContract()` public method and `computeEffectiveStatusLabel()` private helper |
| `controller/ContractController.java` | Added `POST /{id}/terminate` endpoint |

**Deleted:**

| File | Reason |
|---|---|
| `dto/TerminationRequest.java` | Removed — no request body needed; `terminatedBy` comes from JWT |

### 11.2 computeEffectiveStatusLabel Logic

This private method mirrors the `ContractListResponse` constructor's status computation. It is used **only** in the termination eligibility check — to produce the correct error message when a non-EXPIRED contract is passed.

```java
private String computeEffectiveStatusLabel(Contract c) {
    ContractStatus s = c.getStatus();

    // Non-post-finalization statuses — return as-is
    if (s != ContractStatus.SIGNED && s != ContractStatus.ACTIVE
            && s != ContractStatus.EXPIRING && s != ContractStatus.EXPIRED) {
        return s.name();
    }

    // Post-finalization — compute from dates
    if (c.getEndDate() == null)                          return "SIGNED";
    if (c.getEndDate().isBefore(LocalDate.now()))        return "EXPIRED";
    long days = ChronoUnit.DAYS.between(LocalDate.now(), c.getEndDate());
    if (days <= 30)                                      return "EXPIRING";
    if (c.getStartDate() != null
            && !c.getStartDate().isAfter(LocalDate.now())) return "ACTIVE";
    return "SIGNED";
}
```

**Why this is separate from `ContractListResponse`:** `ContractListResponse` sets the status on a DTO field (the `ContractStatus` enum). `computeEffectiveStatusLabel` returns a `String` label for error messaging. Keeping them separate avoids coupling the service layer to the DTO layer.

### 11.3 Why JWT Email is Used, Not Request Body

The old Next.js internal route required `{ terminatedBy: email }` in the body because it had no authentication — it was a direct MongoDB call. Since Spring Boot uses JWT authentication, the caller's identity is already established before the controller method is invoked.

Using the JWT email:
- **Cannot be spoofed** — the JWT is signed with the server secret
- **Is consistent** with how all other user-scoped operations work (`createdBy`, `reviewedBy`, etc.)
- **Simplifies the API** — no body means no validation, no DTO, no `@RequestBody`

---

## 12. Testing Guide

### 12.1 Test Scenarios

| # | Scenario | Expected HTTP | Expected Body |
|---|---|---|---|
| 1 | Terminate an EXPIRED contract (endDate in past) | 200 | `{ success: true, alreadyTerminated: false }` |
| 2 | Terminate an already TERMINATED contract | 200 | `{ success: true, alreadyTerminated: true }` |
| 3 | Terminate an ACTIVE contract | 400 | `message: "...status \"ACTIVE\"..."` |
| 4 | Terminate an EXPIRING contract | 400 | `message: "...status \"EXPIRING\"..."` |
| 5 | Terminate a DRAFT contract | 400 | `message: "...status \"DRAFT\"..."` |
| 6 | Terminate a SIGNED contract (future startDate) | 400 | `message: "...status \"SIGNED\"..."` |
| 7 | Terminate with renewalStatus = "in_progress" | 409 | `message: "...active renewal in progress..."` |
| 8 | Terminate with no Authorization header | 401 | Unauthorized |
| 9 | Terminate a contract that doesn't exist | 404 | `message: "Contract not found"` |
| 10 | Terminate another user's contract | 404 | `message: "Contract not found"` |

### 12.2 Testing via curl

**Step 1 — Login and get token:**

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"admin@gmail.com\", \"password\": \"your_password\"}"
```

Copy the `token` value from the response.

---

**Step 2 — Terminate an EXPIRED contract:**

```bash
curl -X POST http://localhost:8080/contracts/YOUR_CONTRACT_ID/terminate \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

**Expected:** `200 OK` with `{ "success": true, "alreadyTerminated": false }`

---

**Step 3 — Verify idempotency (run the same request again):**

```bash
curl -X POST http://localhost:8080/contracts/YOUR_CONTRACT_ID/terminate \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

**Expected:** `200 OK` with `{ "success": true, "alreadyTerminated": true }`

---

**Step 4 — Verify contract now shows TERMINATED:**

```bash
curl -X GET http://localhost:8080/contracts/YOUR_CONTRACT_ID \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

**Expected:** `status: "TERMINATED"`, `terminatedAt` and `terminatedBy` populated.

---

**Step 5 — Test wrong status (ACTIVE contract):**

```bash
curl -X POST http://localhost:8080/contracts/ACTIVE_CONTRACT_ID/terminate \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

**Expected:** `400 Bad Request` with message `"Cannot terminate a contract with status \"ACTIVE\"..."`

---

**Step 6 — Test without token:**

```bash
curl -X POST http://localhost:8080/contracts/YOUR_CONTRACT_ID/terminate
```

**Expected:** `401 Unauthorized`

---

**Step 7 — Test wrong owner:**

```bash
# Login as a different user first, get their token
curl -X POST http://localhost:8080/contracts/SOMEONE_ELSES_CONTRACT_ID/terminate \
  -H "Authorization: Bearer OTHER_USERS_TOKEN"
```

**Expected:** `404 Not Found` with `"Contract not found"`

---

### 12.3 Run Unit Tests

```bash
# Run termination-specific tests (once written)
mvn test -Dtest=ContractServiceTerminationTest

# Run all contract service tests
mvn test -Dtest="ContractService*"

# Run full test suite
mvn test
```
