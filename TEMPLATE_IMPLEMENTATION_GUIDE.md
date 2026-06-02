# Template API Documentation

**Version:** 1.0.0  
**Base URL:** `http://localhost:8080`  
**Last Updated:** 2026-05-26  

---

## Table of Contents

1. [Overview](#1-overview)
2. [Authentication](#2-authentication)
3. [Error Handling](#3-error-handling)
4. [Data Models](#4-data-models)
   - 4.1 [Template (Full)](#41-template-full)
   - 4.2 [Template (List Item)](#42-template-list-item)
   - 4.3 [FormField](#43-formfield)
   - 4.4 [Party](#44-party)
   - 4.5 [Category](#45-category)
5. [Category Endpoints](#5-category-endpoints)
   - 5.1 [List Categories](#51-list-categories)
   - 5.2 [Create Category](#52-create-category)
   - 5.3 [Delete Category](#53-delete-category)
6. [Template Endpoints](#6-template-endpoints)
   - 6.1 [List Templates](#61-list-templates)
   - 6.2 [Get Template](#62-get-template)
   - 6.3 [Create Template](#63-create-template)
   - 6.4 [Update Template](#64-update-template)
   - 6.5 [Delete Template](#65-delete-template)
7. [File Endpoints](#7-file-endpoints)
   - 7.1 [Upload PDF — Single Shot](#71-upload-pdf--single-shot)
   - 7.2 [Download PDF](#72-download-pdf)
   - 7.3 [Get Presigned View URL](#73-get-presigned-view-url)
8. [Chunked Upload Endpoints](#8-chunked-upload-endpoints)
   - 8.1 [How Chunked Upload Works](#81-how-chunked-upload-works)
   - 8.2 [Initiate Chunked Upload](#82-initiate-chunked-upload)
   - 8.3 [Get Presigned URL](#83-get-presigned-url)
   - 8.4 [Upload Chunk to MinIO](#84-upload-chunk-to-minio)
   - 8.5 [Complete Chunked Upload](#85-complete-chunked-upload)
   - 8.6 [Abort Chunked Upload](#86-abort-chunked-upload)
9. [End-to-End Flows](#9-end-to-end-flows)
   - 9.1 [Create Template with Single-Shot Upload](#91-create-template-with-single-shot-upload)
   - 9.2 [Create Template with Chunked Upload](#92-create-template-with-chunked-upload)
   - 9.3 [Load Template in Apryse Viewer](#93-load-template-in-apryse-viewer)
   - 9.4 [Delete Template](#94-delete-template)
10. [Orphaned Upload Cleanup](#10-orphaned-upload-cleanup)
11. [Endpoint Summary](#11-endpoint-summary)

---

## 1. Overview

The Template API manages contract templates — their metadata, PDF files, and form field definitions. Templates are the foundation of the contract creation flow.

### Storage Architecture

Template data is split across two storage systems:

| Data | Storage | Reason |
|---|---|---|
| Name, description, category, parties | MongoDB | Queryable metadata |
| xfdfData, formFields | MongoDB | Returned to Apryse PDF viewer on load |
| Raw PDF binary | MinIO | Object storage — binary does not belong in MongoDB |

### MinIO Object Key Pattern

Every template PDF is stored under:

```
templates/{mongoId}.pdf
```

The `mongoId` is the MongoDB `_id` generated at template creation time.

### Two-Step Template Creation

Template creation always requires two separate API calls:

```
Step 1 — POST /templates         Send metadata (JSON) → receive { id }
Step 2 — PUT /templates/{id}/file   Send PDF binary using the id from Step 1
```

The `id` from Step 1 is the key that links the metadata in MongoDB to the PDF file in MinIO. No file is accepted in Step 1, and no metadata is accepted in Step 2.

---

## 2. Authentication

All endpoints require a valid JWT token in the `Authorization` header.

```
Authorization: Bearer <token>
```

Obtain a token from `POST /auth/login`. Tokens expire in **24 hours**. After expiry, re-login to get a new token.

### Role Requirements

| Role | How to Obtain | Permissions |
|---|---|---|
| USER | Login with any email except `admin@gmail.com` | Read templates and categories, download PDFs |
| ADMIN | Login with `admin@gmail.com` | Full access — create, update, delete, upload files |

---

## 3. Error Handling

All error responses follow this consistent structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Template name is required"
}
```

| Field | Type | Description |
|---|---|---|
| status | Integer | HTTP status code |
| error | String | Short error category |
| message | String | Human-readable description of what went wrong |

### Status Code Reference

| Code | Error | Common Cause |
|---|---|---|
| 400 | Bad Request | Validation failed — required field missing or format invalid |
| 401 | Unauthorized | Token missing, expired, or resource not found |
| 403 | Forbidden | Authenticated but insufficient role |
| 500 | Internal Server Error | Unexpected server-side failure |
| 503 | Service Unavailable | MinIO storage operation failed |

---

## 4. Data Models

### 4.1 Template (Full)

Returned by `GET /templates/{id}`. Contains all fields including `xfdfData` and `formFields`.

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "name": "Vendor NDA",
  "description": "Non-disclosure agreement for vendors",
  "category": "NDA",
  "fileName": "vendor-nda.pdf",
  "fileUrl": "templates/683a1f2c9d4e5b0012345678.pdf",
  "uploadedBy": "admin@gmail.com",
  "createdAt": "2026-05-26T10:00:00",
  "updatedAt": "2026-05-26T10:05:00",
  "timesUsed": 3,
  "hasFormFields": true,
  "fileUploaded": true,
  "parties": [
    { "id": "party_1", "label": "Buyer", "color": "#4CAF50", "order": 1 },
    { "id": "party_2", "label": "Seller", "color": "#2196F3", "order": 2 }
  ],
  "xfdfData": "<?xml version=\"1.0\"?>...",
  "formFields": []
}
```

### 4.2 Template (List Item)

Returned by `GET /templates`. Excludes `xfdfData` and `formFields` to keep list responses lightweight. Use `GET /templates/{id}` when those fields are needed.

```json
{
  "id": "683a1f2c9d4e5b0012345678",
  "name": "Vendor NDA",
  "description": "Non-disclosure agreement for vendors",
  "category": "NDA",
  "fileName": "vendor-nda.pdf",
  "fileUrl": "templates/683a1f2c9d4e5b0012345678.pdf",
  "uploadedBy": "admin@gmail.com",
  "createdAt": "2026-05-26T10:00:00",
  "updatedAt": "2026-05-26T10:05:00",
  "timesUsed": 3,
  "hasFormFields": true,
  "fileUploaded": true,
  "parties": []
}
```

### 4.3 FormField

Represents one interactive field placed on the PDF canvas in Apryse.

```json
{
  "name": "buyer_name",
  "type": "text",
  "label": "Buyer Full Name",
  "x": 100.0,
  "y": 200.0,
  "width": 250.0,
  "height": 30.0,
  "pageNumber": 1,
  "annotationId": "annot-001",
  "required": true,
  "readOnly": false,
  "multiline": false,
  "defaultValue": "",
  "placeholder": "Enter full name",
  "options": [],
  "assignedParty": "party_1",
  "partyLabel": "Buyer",
  "profileKey": "fullName"
}
```

| Field | Type | Accepted Values | Description |
|---|---|---|---|
| type | String | `text`, `signature`, `checkbox`, `date`, `dropdown` | Input type of the field |
| assignedParty | String | `party_1`, `party_2`, `unassigned` | Which signing party fills this field |
| profileKey | String | `fullName`, `email`, `__date_today__`, `null` | Links field to user profile for autofill |
| options | Array | Any strings | Choices for `dropdown` or `radio` fields |

### 4.4 Party

Represents a signing party in the contract.

```json
{
  "id": "party_1",
  "label": "Buyer",
  "color": "#4CAF50",
  "order": 1
}
```

| Field | Description |
|---|---|
| id | Identifier referenced by FormField `assignedParty`. Format: `party_N` |
| label | Human-readable name shown in UI |
| color | Hex color used to visually distinguish party fields in Apryse |
| order | Display order |

### 4.5 Category

```json
{
  "id": "683a1f2c9d4e5b0087654321",
  "name": "NDA",
  "createdBy": "admin@gmail.com",
  "createdAt": "2026-05-26T09:00:00"
}
```

---

## 5. Category Endpoints

### 5.1 List Categories

Returns all categories. Available to any authenticated user.

```
GET /categories
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Response — 200 OK**

```json
[
  {
    "id": "683a1f2c9d4e5b0087654321",
    "name": "NDA",
    "createdBy": "admin@gmail.com",
    "createdAt": "2026-05-26T09:00:00"
  },
  {
    "id": "683a1f2c9d4e5b0087654322",
    "name": "Employment",
    "createdBy": "admin@gmail.com",
    "createdAt": "2026-05-26T09:01:00"
  }
]
```

---

### 5.2 Create Category

Creates a new category. Category names are unique (case-insensitive).

```
POST /categories
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Request Body**

```json
{
  "name": "NDA"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| name | String | Yes | Max 50 characters. Duplicate names rejected (case-insensitive). |

**Response — 200 OK**

```json
{
  "id": "683a1f2c9d4e5b0087654321",
  "name": "NDA",
  "createdBy": "admin@gmail.com",
  "createdAt": "2026-05-26T09:00:00"
}
```

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Category name is required | `name` is blank |
| 401 | Category already exists | Duplicate name (case-insensitive) |
| 403 | Forbidden | Caller is not ADMIN |

---

### 5.3 Delete Category

Permanently deletes a category by ID.

```
DELETE /categories/{id}
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the category to delete |

**Response — 204 No Content**

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Category not found | ID does not exist |
| 403 | Forbidden | Caller is not ADMIN |

---

## 6. Template Endpoints

### 6.1 List Templates

Returns all templates without `xfdfData` and `formFields`. Use this for listing/browsing templates.

```
GET /templates
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Response — 200 OK**

Array of [Template (List Item)](#42-template-list-item) objects.

```json
[
  {
    "id": "683a1f2c9d4e5b0012345678",
    "name": "Vendor NDA",
    "fileUploaded": true,
    "hasFormFields": true,
    ...
  }
]
```

---

### 6.2 Get Template

Returns a single template with all fields including `xfdfData` and `formFields`. Use this before loading the template into the Apryse viewer.

```
GET /templates/{id}
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Response — 200 OK**

[Template (Full)](#41-template-full) object.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |

---

### 6.3 Create Template

Saves template metadata to MongoDB. Returns a template `id`. **No PDF is uploaded here.** Use the file endpoints after this call to attach the PDF.

```
POST /templates
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Request Body**

```json
{
  "name": "Vendor NDA",
  "description": "Non-disclosure agreement for vendors",
  "category": "NDA",
  "fileName": "vendor-nda.pdf",
  "xfdfData": "<?xml version=\"1.0\"?>...",
  "formFields": [],
  "parties": []
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| name | String | Yes | Max 50 characters |
| description | String | No | — |
| category | String | Yes | — |
| fileName | String | No | Display label only — not the binary file |
| xfdfData | String | No | XFDF XML exported from Apryse |
| formFields | Array | No | Array of [FormField](#43-formfield) objects |
| parties | Array | No | Array of [Party](#44-party) objects |

> `uploadedBy` is always sourced from the JWT token. It cannot be set in the request body.

**Response — 200 OK**

```json
{
  "id": "683a1f2c9d4e5b0012345678"
}
```

Use this `id` immediately to upload the PDF via [Section 7](#7-file-endpoints) or [Section 8](#8-chunked-upload-endpoints).

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Template name is required | `name` is blank |
| 400 | Category is required | `category` is blank |
| 403 | Forbidden | Caller is not ADMIN |

---

### 6.4 Update Template

Updates template metadata. Does not affect the uploaded PDF file.

```
PUT /templates/{id}
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Request Body**

Same shape as [Create Template](#63-create-template). All provided fields are replaced.

**Response — 200 OK**

[Template (Full)](#41-template-full) object with updated values.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 400 | Template name is required | `name` is blank |
| 400 | Category is required | `category` is blank |
| 401 | Template not found | ID does not exist |
| 403 | Forbidden | Caller is not ADMIN |

---

### 6.5 Delete Template

Deletes the template record from MongoDB and its PDF from MinIO (if one was uploaded).

```
DELETE /templates/{id}
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Response — 204 No Content**

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 403 | Forbidden | Caller is not ADMIN |
| 503 | Storage Error | MinIO delete operation failed |

---

## 7. File Endpoints

### 7.1 Upload PDF — Single Shot

Uploads the entire PDF in one request. Use this for files under 50MB. For larger files use the [Chunked Upload](#8-chunked-upload-endpoints) flow.

The request body is the raw PDF binary — not JSON, not form-data.

```
PUT /templates/{id}/file
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/pdf` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Request Body**

Raw PDF binary bytes.

- Postman: Body → **binary** → Select File → pick your `.pdf`
- curl: `--data-binary @/path/to/file.pdf`

**Validations Run by Backend**

| Check | Rule | Error if fails |
|---|---|---|
| Content-Type header | Must be `application/pdf` | Only PDF files are accepted |
| File size | Must not exceed 50MB | File size must not exceed 50MB |
| PDF magic bytes | First 4 bytes must equal `%PDF` | File is not a valid PDF |

**Response — 200 OK**

Empty body. MongoDB updated: `fileUploaded: true`.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 401 | Only PDF files are accepted | Wrong Content-Type |
| 401 | File size must not exceed 50MB | File exceeds limit |
| 401 | File is not a valid PDF | Magic bytes check failed |
| 503 | Storage Error | MinIO upload failed |

---

### 7.2 Download PDF

Streams the template PDF from MinIO as a binary response.

```
GET /templates/{id}/file
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Response — 200 OK**

| Header | Value |
|---|---|
| Content-Type | `application/pdf` |

Body is raw PDF binary stream.

- Postman: click **Save Response** → Save to a file → open as `.pdf`
- curl: `--output downloaded.pdf`

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 401 | File not yet uploaded for this template | `fileUploaded` is false |
| 503 | Storage Error | MinIO fetch failed |

---

### 7.3 Get Presigned View URL

Returns a short-lived presigned MinIO URL for viewing the template PDF. Pass this URL directly to Apryse WebViewer as `initialDoc`. MinIO natively supports HTTP range requests (`206 Partial Content`), so Apryse can load large PDFs page by page without downloading the entire file.

> **Use this instead of `GET /templates/{id}/file` when loading into Apryse.** Blob URLs created from binary streams do not support range requests and will cause Apryse to fail on large PDFs.

```
GET /templates/{id}/file/view-url
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Response — 200 OK**

```json
{
  "url": "http://localhost:9000/contract-management/templates/683a...78.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=...&X-Amz-Expires=900&X-Amz-Signature=..."
}
```

| Field | Description |
|---|---|
| url | Presigned MinIO GET URL. Valid for **15 minutes**. Pass directly to Apryse as `initialDoc`. No `Authorization` header needed — the signature is embedded in the URL. |

**Frontend Usage**

```js
const res = await fetch(`/templates/${id}/file/view-url`, {
  headers: { Authorization: `Bearer ${token}` }
});
const { url } = await res.json();

WebViewer({ initialDoc: url }, viewerDiv);
```

**Important Notes**

| Rule | Detail |
|---|---|
| No Authorization header to MinIO | The presigned URL is self-authenticating — adding an `Authorization` header will cause MinIO to reject it |
| URL expires in 15 minutes | Refresh by calling this endpoint again if the viewer session is long-lived |
| `fileUploaded` must be true | Returns 500 if no PDF has been uploaded yet — always check `fileUploaded` from `GET /templates/{id}` first |

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 500 | File not yet uploaded for this template | `fileUploaded` is false |

---

## 8. Chunked Upload Endpoints

### 8.1 How Chunked Upload Works

Chunked upload splits a large file into smaller parts and uploads each part independently. If a part fails, only that part is retried — not the whole file.

**Key principle:** The PDF bytes go **directly from the client to MinIO** — they never pass through the Spring Boot backend. The backend only handles coordination (creating the upload slot, signing URLs, finalizing).

```
CLIENT                          BACKEND (8080)                  MINIO (9000)
──────────────────────────────────────────────────────────────────────────────
POST /initiate            ───>  Creates multipart slot   ──-->  Reserves uploadId
                          <───  Returns uploadId

GET  /presign?part=1      ───>  Signs a MinIO URL         
                          <───  Returns presigned URL

PUT  presigned URL        ────────────────────────────────────> Stores chunk 1
                          <──────────────────────────────────── ETag in header

GET  /presign?part=2      ───>  Signs a MinIO URL
                          <───  Returns presigned URL

PUT  presigned URL        ────────────────────────────────────> Stores chunk 2
                          <──────────────────────────────────── ETag in header

POST /complete            ───>  Sends part list           ──-->  Assembles chunks
                                Updates MongoDB            <────  Final PDF ready
```

**MinIO Constraints**

| Rule | Value |
|---|---|
| Minimum part size | 5MB (last part can be any size) |
| Maximum parts | 10,000 |
| Recommended chunk size | 5–10MB |
| Presigned URL expiry | 15 minutes per part |

---

### 8.2 Initiate Chunked Upload

Registers a multipart upload with MinIO and saves the `uploadId` to MongoDB. No file data is sent here.

```
POST /templates/{id}/file/initiate
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Request Body**

None.

**Response — 200 OK**

```json
{
  "uploadId": "ZTRiZjQ2OGIt...",
  "templateId": "683a1f2c9d4e5b0012345678"
}
```

| Field | Description |
|---|---|
| uploadId | Unique ID for this multipart upload session. Pass this in every subsequent chunked upload call. |
| templateId | Echo of the template ID |

MongoDB state after this call: `fileUploaded: false`, `uploadId: "ZTRiZjQ2OGIt..."`.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 503 | Storage Error | MinIO initiation failed |

---

### 8.3 Get Presigned URL

Generates a presigned MinIO URL for uploading one specific chunk. The URL is valid for 15 minutes. Call this once per part before each chunk upload.

```
GET /templates/{id}/file/presign
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| uploadId | String | Yes | The `uploadId` from `/initiate` |
| partNumber | Integer | Yes | Part index. Starts at `1`. Increment by 1 for each chunk. |

**Response — 200 OK**

```json
{
  "url": "http://localhost:9000/contract-management/templates/683a...bf5.pdf?uploadId=ZTRi...&partNumber=1&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=...&X-Amz-Signature=...",
  "partNumber": 1
}
```

| Field | Description |
|---|---|
| url | Full presigned MinIO URL. Valid for 15 minutes. Use this directly in the next step. |
| partNumber | Echo of the requested part number |

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |

---

### 8.4 Upload Chunk to MinIO

Uploads the raw bytes of one chunk directly to MinIO using the presigned URL.

> **This request goes to MinIO on port 9000 — not to the Spring Boot backend.**  
> Do not include an `Authorization` header — the signature is already embedded in the presigned URL.

```
PUT <presigned URL from /presign>
```

**Headers**

| Header | Required | Value |
|---|---|---|
| Content-Type | Yes | `application/octet-stream` |

**Request Body**

Raw binary bytes of this chunk.

- Postman: Body → **binary** → Select File → pick your PDF
- curl: `--data-binary @/path/to/chunk.pdf`

**Response — 200 OK**

Empty body. Check the response **Headers** tab for:

| Response Header | Example Value | Description |
|---|---|---|
| ETag | `"d8e8fca2dc0f896fd7cb4cb0031ba249"` | Checksum for this chunk. Required in `/complete`. |

> Save the ETag for every part. You must include all ETags in the `/complete` request.

---

### 8.5 Complete Chunked Upload

Sends the full list of uploaded parts with their ETags to MinIO. MinIO verifies each ETag, assembles all chunks into one PDF, and deletes the temporary part data. The backend then updates MongoDB: `fileUploaded: true`, `uploadId: null`.

```
POST /templates/{id}/file/complete
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |
| Content-Type | Yes | `application/json` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Request Body**

```json
{
  "uploadId": "ZTRiZjQ2OGIt...",
  "parts": [
    { "partNumber": 1, "eTag": "d8e8fca2dc0f896fd7cb4cb0031ba249" },
    { "partNumber": 2, "eTag": "a87ff679a2f3e71d9181a67b7542122c" },
    { "partNumber": 3, "eTag": "eccbc87e4b5ce2fe28308fd9f2a7baf3" }
  ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| uploadId | String | Yes | From `/initiate` response |
| parts | Array | Yes | All uploaded parts — must be complete and in order |
| parts[].partNumber | Integer | Yes | Must match the `partNumber` used during upload |
| parts[].eTag | String | Yes | ETag from MinIO response header for that part |

**Response — 200 OK**

Empty body. MongoDB state: `fileUploaded: true`, `uploadId: null`.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 503 | Storage Error | MinIO assembly failed or ETag mismatch |

---

### 8.6 Abort Chunked Upload

Cancels an in-progress chunked upload. MinIO deletes all temporary chunk data for this `uploadId`. MongoDB is reset: `fileUploaded: false`, `uploadId: null`.

Call this if the upload fails midway, the user cancels, or you need to restart from the beginning.

```
POST /templates/{id}/file/abort
```

**Role required:** ADMIN

**Headers**

| Header | Required | Value |
|---|---|---|
| Authorization | Yes | `Bearer <token>` |

**Path Parameters**

| Parameter | Description |
|---|---|
| id | MongoDB ID of the template |

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| uploadId | String | Yes | The `uploadId` to cancel |

**Response — 200 OK**

Empty body.

**Error Responses**

| Status | Message | Cause |
|---|---|---|
| 401 | Template not found | ID does not exist |
| 503 | Storage Error | MinIO abort failed |

---

## 9. End-to-End Flows

### 9.1 Create Template with Single-Shot Upload

Use this flow for PDF files under 50MB.

```
Step 1   POST /auth/login
         Body: { email, password }
         ← { token }

Step 2   POST /templates
         Headers: Authorization: Bearer <token>
         Body: { name, description, category, fileName, xfdfData, formFields, parties }
         ← { id }

Step 3   PUT /templates/{id}/file
         Headers: Authorization: Bearer <token>, Content-Type: application/pdf
         Body: raw PDF binary
         ← 200 OK  →  fileUploaded: true in MongoDB

Step 4   GET /templates/{id}
         ← full template with fileUploaded: true
```

---

### 9.2 Create Template with Chunked Upload

Use this flow for large PDF files. The frontend splits the file into chunks and uploads each one directly to MinIO.

```
Step 1   POST /auth/login
         ← { token }

Step 2   POST /templates
         Body: { name, description, category, fileName, xfdfData, formFields, parties }
         ← { id }

Step 3   POST /templates/{id}/file/initiate
         ← { uploadId }

         [Frontend calculates: totalParts = ceil(fileSize / chunkSize)]

         loop for partNumber = 1 to totalParts:

Step 4     GET /templates/{id}/file/presign?uploadId=X&partNumber=N
           ← { url }

Step 5     PUT <url>   (to MinIO:9000 directly — no Authorization header)
           Headers: Content-Type: application/octet-stream
           Body: chunk bytes
           ← 200 OK, ETag in response header

           [Frontend saves { partNumber, eTag }]

         end loop

Step 6   POST /templates/{id}/file/complete
         Body: { uploadId, parts: [{ partNumber, eTag }, ...] }
         ← 200 OK  →  fileUploaded: true in MongoDB

Step 7   GET /templates/{id}
         ← full template with fileUploaded: true
```

---

### 9.3 Load Template in Apryse Viewer

```
Step 1   GET /templates/{id}
         ← { xfdfData, formFields, parties, fileUploaded, ... }

         [Check fileUploaded: true before proceeding]

Step 2   GET /templates/{id}/file/view-url
         ← { url }   (presigned MinIO URL, valid 15 min)

Step 3   WebViewer({ initialDoc: url }, viewerDiv)
         Apryse talks directly to MinIO using range requests
         ← PDF renders page by page (works for any file size)

Frontend:
  - Pass presigned url as initialDoc — do NOT convert to blob URL
  - Import xfdfData → annotation fields appear on PDF
  - Map formFields[].profileKey to GET /profile data → autofills fields
    (profileKey: "fullName" → profile.fullName)
    (profileKey: "__date_today__" → today's date)
  - If viewer session exceeds 15 min, call /file/view-url again for a fresh URL
```

> `GET /templates/{id}/file` (binary stream) is available for direct download use cases only. Do not use it as `initialDoc` for Apryse — blob URLs do not support the range requests Apryse requires for large PDF incremental loading.

---

### 9.4 Delete Template

```
Step 1   DELETE /templates/{id}
         Headers: Authorization: Bearer <token>

Backend:
  1. Removes PDF from MinIO: templates/{id}.pdf
  2. Deletes document from MongoDB

         ← 204 No Content
```

---

## 10. Orphaned Upload Cleanup

If a chunked upload is started but never completed — due to a browser crash, network failure, or user abandonment — partial chunks remain in MinIO indefinitely consuming storage.

A scheduled background job runs automatically every day at **2:00 AM** to clean this up.

**Cleanup criteria:** Templates where `fileUploaded: false` AND `uploadInitiatedAt` is older than 24 hours.

**Cleanup actions per orphaned record:**
1. Calls MinIO `abortMultipartUpload` → all temporary chunk data deleted
2. Deletes the orphaned template record from MongoDB

No action is required from the frontend or admin. This runs automatically in the background.

---

## 11. Endpoint Summary

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/categories` | Authenticated | List all categories |
| POST | `/categories` | ADMIN | Create a new category |
| DELETE | `/categories/{id}` | ADMIN | Delete a category |
| GET | `/templates` | Authenticated | List all templates (excludes xfdfData, formFields) |
| GET | `/templates/{id}` | Authenticated | Get full template with all fields |
| POST | `/templates` | ADMIN | Create template metadata — returns `{ id }` |
| PUT | `/templates/{id}` | ADMIN | Update template metadata |
| DELETE | `/templates/{id}` | ADMIN | Delete template and its PDF from MinIO |
| PUT | `/templates/{id}/file` | ADMIN | Upload PDF — single shot (max 50MB) |
| GET | `/templates/{id}/file` | Authenticated | Download PDF binary |
| GET | `/templates/{id}/file/view-url` | Authenticated | Get presigned MinIO URL for Apryse viewer (15 min expiry) |
| POST | `/templates/{id}/file/initiate` | ADMIN | Start a chunked upload session |
| GET | `/templates/{id}/file/presign` | ADMIN | Get presigned URL for one chunk |
| POST | `/templates/{id}/file/complete` | ADMIN | Finalize chunked upload |
| POST | `/templates/{id}/file/abort` | ADMIN | Abort and clean up a chunked upload |
