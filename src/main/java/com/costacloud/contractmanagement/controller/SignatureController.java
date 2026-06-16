package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.service.SignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "Signature", description = "Contract signature workflow — submit, sign, track, finalize")
public class SignatureController {

    private final SignatureService signatureService;

    public SignatureController(SignatureService signatureService) {
        this.signatureService = signatureService;
    }

    // ─── Contractor Endpoints (JWT required) ─────────────────────

    @Operation(summary = "Submit contract for signature — starts or resumes the signing workflow")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/contracts/{id}/submit-for-signature")
    public ResponseEntity<ContractResponse> submitForSignature(
            @PathVariable String id,
            @Valid @RequestBody SubmitForSignatureRequest request) {
        return ResponseEntity.ok(signatureService.submitForSignature(id, request, getEmail()));
    }

    @Operation(summary = "Get signing progress for a contract")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/contracts/{id}/signature-status")
    public ResponseEntity<SignatureStatusResponse> getSignatureStatus(@PathVariable String id) {
        return ResponseEntity.ok(signatureService.getSignatureStatus(id, getEmail()));
    }

    @Operation(summary = "Initiate chunked PDF upload for internal signer")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/contracts/{id}/sign/upload/initiate")
    public ResponseEntity<SignUploadInitResponse> initiateInternalSignedPdfUpload(
            @PathVariable String id) throws Exception {
        return ResponseEntity.ok(signatureService.initiateInternalSignedPdfUpload(id, getEmail()));
    }

    @Operation(summary = "Get presigned URL for uploading a single chunk (internal signer)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/contracts/{id}/sign/upload/presign")
    public ResponseEntity<Map<String, Object>> getPresignedInternalSignPart(
            @PathVariable String id,
            @RequestParam String uploadId,
            @RequestParam int partNumber) throws Exception {
        String url = signatureService.generatePresignedInternalSignPart(id, uploadId, partNumber, getEmail());
        return ResponseEntity.ok(Map.of("url", url, "partNumber", partNumber));
    }

    @Operation(summary = "Abort chunked PDF upload for internal signer")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/contracts/{id}/sign/upload/abort")
    public ResponseEntity<Void> abortInternalSignedPdfUpload(
            @PathVariable String id,
            @RequestParam String uploadId) throws Exception {
        signatureService.abortInternalSignedPdfUpload(id, uploadId, getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Internal signer completes their signature from the inbox")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/contracts/{id}/internal-sign")
    public ResponseEntity<SignCompleteResponse> internalSign(
            @PathVariable String id,
            @Valid @RequestBody InternalSignCompleteRequest request) throws Exception {
        return ResponseEntity.ok(signatureService.completeInternalSigning(id, request, getEmail()));
    }

    @Operation(summary = "Finalize contract after all parties have signed")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/contracts/{id}/finalize")
    public ResponseEntity<ContractResponse> finalizeContract(@PathVariable String id) {
        return ResponseEntity.ok(signatureService.finalizeContract(id, getEmail()));
    }

    // ─── Public Endpoints (NO JWT — signing token is the auth) ───

    @Operation(summary = "Load signing page data using a token")
    @GetMapping("/sign-requests/{token}")
    public ResponseEntity<SignatureRequestResponse> getSigningData(@PathVariable String token) {
        return ResponseEntity.ok(signatureService.getSigningData(token));
    }

    @Operation(summary = "Get presigned MinIO URL so browser fetches PDF directly (no proxy)")
    @GetMapping("/sign-requests/{token}/file-url")
    public ResponseEntity<Map<String, String>> getSigningFileUrl(@PathVariable String token) throws Exception {
        String url = signatureService.getSigningPdfUrl(token);
        return ResponseEntity.ok(Map.of("url", url));
    }

    @Operation(summary = "Initiate chunked PDF upload for external signer")
    @PostMapping("/sign-requests/{token}/upload/initiate")
    public ResponseEntity<SignUploadInitResponse> initiateSignedPdfUpload(
            @PathVariable String token) throws Exception {
        return ResponseEntity.ok(signatureService.initiateSignedPdfUpload(token));
    }

    @Operation(summary = "Get presigned URL for uploading a single chunk (external signer)")
    @GetMapping("/sign-requests/{token}/upload/presign")
    public ResponseEntity<Map<String, Object>> getPresignedSignPart(
            @PathVariable String token,
            @RequestParam String uploadId,
            @RequestParam int partNumber) throws Exception {
        String url = signatureService.generatePresignedSignPart(token, uploadId, partNumber);
        return ResponseEntity.ok(Map.of("url", url, "partNumber", partNumber));
    }

    @Operation(summary = "Complete chunked upload + submit signature (set autoSave=true for auto-save)")
    @PostMapping("/sign-requests/{token}/upload/complete")
    public ResponseEntity<SignCompleteResponse> completeSignedPdfUpload(
            @PathVariable String token,
            @RequestBody SignUploadCompleteRequest request) throws Exception {
        return ResponseEntity.ok(signatureService.completeSignedPdfUpload(token, request));
    }

    @Operation(summary = "Abort chunked upload if something went wrong")
    @PostMapping("/sign-requests/{token}/upload/abort")
    public ResponseEntity<Void> abortSignedPdfUpload(
            @PathVariable String token,
            @RequestParam String uploadId) throws Exception {
        signatureService.abortSignedPdfUpload(token, uploadId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Mark signing link as viewed when external signer opens it")
    @PatchMapping("/sign-requests/{token}/viewed")
    public ResponseEntity<Void> markViewed(@PathVariable String token) {
        signatureService.markAsViewed(token);
        return ResponseEntity.ok().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private String getEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
