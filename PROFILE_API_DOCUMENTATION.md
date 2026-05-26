# Profile API Documentation

**Version:** 1.0.0
**Base URL:** `http://localhost:8080`
**Last Updated:** 2026-05-21

---

## Overview

The Profile API allows authenticated users to view and update their personal profile information. All endpoints require a valid JWT token obtained from the Authentication API.

Profile data is stored separately from authentication data. The email is always derived from the JWT token — it cannot be changed via the profile API.

---

## Authentication

All profile endpoints require a Bearer token in the `Authorization` header.

```
Authorization: Bearer <token>
```

Obtain the token from `POST /auth/login`. Token expires in **24 hours**.

---

## Endpoints

### 1. Get Profile

Retrieves the authenticated user's profile. If the user has not filled in their profile yet, all fields except `email` will be `null`.

```
GET /profile
```

#### Headers

| Header | Value | Required |
|---|---|---|
| Authorization | `Bearer <token>` | Yes |

#### Response — 200 OK

```json
{
  "email": "priya@gmail.com",
  "fullName": "Priya Sharma",
  "department": "Legal",
  "organization": "CostaCloud",
  "dateOfBirth": "1998-05-15",
  "gender": "FEMALE",
  "permanentAddress": "123 Main Street, Mumbai",
  "panCardNumber": "ABCDE1234F",
  "aadharCardNumber": "123456789012",
  "profileComplete": true
}
```

#### Response — First time (profile not yet filled)

```json
{
  "email": "priya@gmail.com",
  "fullName": null,
  "department": null,
  "organization": null,
  "dateOfBirth": null,
  "gender": null,
  "permanentAddress": null,
  "panCardNumber": null,
  "aadharCardNumber": null,
  "profileComplete": false
}
```

#### Error Responses

| Status | Reason |
|---|---|
| 401 | Missing or expired token |

---

### 2. Update Profile

Creates or updates the authenticated user's profile. All fields are optional — only the fields included in the request body will be updated. Existing fields not included in the request remain unchanged.

```
PUT /profile
```

#### Headers

| Header | Value | Required |
|---|---|---|
| Authorization | `Bearer <token>` | Yes |
| Content-Type | `application/json` | Yes |

#### Request Body

All fields are optional. Send only the fields you want to update.

```json
{
  "fullName": "Priya Sharma",
  "department": "Legal",
  "organization": "CostaCloud",
  "dateOfBirth": "1998-05-15",
  "gender": "FEMALE",
  "permanentAddress": "123 Main Street, Mumbai",
  "panCardNumber": "ABCDE1234F",
  "aadharCardNumber": "123456789012"
}
```

#### Field Reference

| Field | Type | Required | Rules |
|---|---|---|---|
| fullName | String | No | Max 100 characters |
| department | String | No | Max 100 characters |
| organization | String | No | Max 100 characters |
| dateOfBirth | String (date) | No | Format: `YYYY-MM-DD`. Must be a past date |
| gender | String | No | Accepted values: `MALE`, `FEMALE`, `OTHER` |
| permanentAddress | String | No | Max 255 characters |
| panCardNumber | String | No | Format: 5 uppercase letters + 4 digits + 1 uppercase letter. Example: `ABCDE1234F` |
| aadharCardNumber | String | No | Exactly 12 digits |

#### Response — 200 OK

Returns the full updated profile (same shape as GET /profile).

```json
{
  "email": "priya@gmail.com",
  "fullName": "Priya Sharma",
  "department": "Legal",
  "organization": "CostaCloud",
  "dateOfBirth": "1998-05-15",
  "gender": "FEMALE",
  "permanentAddress": "123 Main Street, Mumbai",
  "panCardNumber": "ABCDE1234F",
  "aadharCardNumber": "123456789012",
  "profileComplete": true
}
```

#### Error Responses

| Status | Reason | Example Message |
|---|---|---|
| 400 | Validation failed on one or more fields | `"Invalid PAN card format. Example: ABCDE1234F"` |
| 401 | Missing or expired token | `"Unauthorized"` |

---

## Response Fields

| Field | Type | Description |
|---|---|---|
| email | String | User's email. Sourced from JWT token. Read-only. |
| fullName | String | User's full name |
| department | String | Department the user belongs to (e.g. Legal, Finance) |
| organization | String | Organization or company name |
| dateOfBirth | String (date) | Format: `YYYY-MM-DD` |
| gender | String | One of: `MALE`, `FEMALE`, `OTHER` |
| permanentAddress | String | User's permanent residential address |
| panCardNumber | String | Indian PAN card number. Format: `ABCDE1234F` |
| aadharCardNumber | String | Indian Aadhar number. 12 digits |
| profileComplete | Boolean | `true` only when all fields are filled. `false` if any field is null |

---

## Error Response Format

All errors follow this consistent structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid PAN card format. Example: ABCDE1234F"
}
```

| Field | Description |
|---|---|
| status | HTTP status code |
| error | Short error category |
| message | Human-readable description of what went wrong |

---

## Validation Rules Detail

### PAN Card Number
- Pattern: `[A-Z]{5}[0-9]{4}[A-Z]{1}`
- 5 uppercase letters, followed by 4 digits, followed by 1 uppercase letter
- Valid example: `ABCDE1234F`
- Invalid examples: `abcde1234f`, `ABCDE123`, `1234ABCDEF`

### Aadhar Card Number
- Must be exactly 12 digits
- No spaces or dashes
- Valid example: `123456789012`
- Invalid examples: `1234 5678 9012`, `12345`, `ABCD12345678`

### Date of Birth
- Format: `YYYY-MM-DD`
- Must be a date in the past
- Valid example: `1998-05-15`
- Invalid examples: `15-05-1998`, `2030-01-01` (future date)

### Gender
- Accepted values (case-sensitive): `MALE`, `FEMALE`, `OTHER`
- Invalid examples: `male`, `Male`, `M`

---

## Partial Update Behaviour

The PUT endpoint supports partial updates. Only fields included in the request body are updated. Fields not included remain unchanged in the database.

**Example:** User already has `fullName = "Priya"` and sends:

```json
{
  "department": "Finance"
}
```

Result: `department` is updated to `"Finance"`. `fullName` stays `"Priya"`. All other fields remain as they were.

---

## `profileComplete` Flag

The `profileComplete` field in the response is `true` only when **all** of the following fields are filled:

- fullName
- department
- organization
- dateOfBirth
- gender
- permanentAddress
- panCardNumber
- aadharCardNumber

Frontend can use this flag to show a "Complete your profile" prompt or progress indicator.

---

## Testing via Swagger UI

1. Open `http://localhost:8080/swagger-ui/index.html`
2. Click **Authorize** (top right)
3. Paste your JWT token (without `Bearer ` prefix)
4. Click **Authorize** then **Close**
5. Expand the **Profile** section
6. Use `GET /profile` or `PUT /profile` directly from the UI

---

## Testing via cURL

### Get Profile
```bash
curl -X GET http://localhost:8080/profile \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### Update Profile (all fields)
```bash
curl -X PUT http://localhost:8080/profile \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Priya Sharma",
    "department": "Legal",
    "organization": "CostaCloud",
    "dateOfBirth": "1998-05-15",
    "gender": "FEMALE",
    "permanentAddress": "123 Main Street, Mumbai",
    "panCardNumber": "ABCDE1234F",
    "aadharCardNumber": "123456789012"
  }'
```

### Partial Update (one field only)
```bash
curl -X PUT http://localhost:8080/profile \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fullName": "Priya Verma"}'
```

### Test Validation Error
```bash
curl -X PUT http://localhost:8080/profile \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"panCardNumber": "invalid", "aadharCardNumber": "123"}'
```

Expected response:
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid PAN card format. Example: ABCDE1234F"
}
```

---

## Security Notes

- The `email` field in the response always comes from the JWT token. The client cannot spoof or change the email by modifying the request body.
- All profile endpoints require authentication. Requests without a valid token receive a `401` response.
- Sensitive identity fields (PAN, Aadhar) are stored as plain strings in MongoDB. Consider encryption at rest for production deployments.
