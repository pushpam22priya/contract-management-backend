package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.service.UnifiedWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/contracts")
@Tag(name = "Unified Workflow",
        description = "New sequential review, approval and signature flow. " +
                "Existing /review and /approval endpoints remain untouched.")
public class UnifiedWorkflowController {

    private final UnifiedWorkflowService unifiedWorkflowService;

    public UnifiedWorkflowController(UnifiedWorkflowService unifiedWorkflowService) {
        this.unifiedWorkflowService = unifiedWorkflowService;
    }

    @Operation(summary = "Submit contract into unified flow — assign reviewers and approvers with sequential orders")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/flow/submit")
    public ResponseEntity<ContractResponse> submit(
            @PathVariable String id,
            @Valid @RequestBody FlowSubmitRequest request) {
        return ResponseEntity.ok(unifiedWorkflowService.submit(id, request, getEmail()));
    }

    @Operation(summary = "Mark complete — both reviewer and approver upload the edited PDF and submit")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/flow/complete")
    public ResponseEntity<ContractResponse> markComplete(
            @PathVariable String id,
            @RequestBody FlowCompleteRequest request) throws Exception {
        return ResponseEntity.ok(unifiedWorkflowService.markComplete(id, request, getEmail()));
    }

    @Operation(summary = "Reject contract with a required reason — moves contract back to contractor")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/flow/reject")
    public ResponseEntity<ContractResponse> reject(
            @PathVariable String id,
            @Valid @RequestBody FlowRejectRequest request) {
        return ResponseEntity.ok(unifiedWorkflowService.reject(id, request, getEmail()));
    }

    @Operation(summary = "Initiate chunked PDF upload — active reviewer or approver")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/flow/upload/initiate")
    public ResponseEntity<SignUploadInitResponse> initiateUpload(
            @PathVariable String id) throws Exception {
        return ResponseEntity.ok(unifiedWorkflowService.initiateUpload(id, getEmail()));
    }

    @Operation(summary = "Get presigned URL for a single upload chunk — active reviewer or approver")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/flow/upload/presign")
    public ResponseEntity<Map<String, Object>> getPresignedPartUrl(
            @PathVariable String id,
            @RequestParam String uploadId,
            @RequestParam int partNumber) throws Exception {
        String url = unifiedWorkflowService.getPresignedPartUrl(id, uploadId, partNumber, getEmail());
        return ResponseEntity.ok(Map.of("url", url, "partNumber", partNumber));
    }

    @Operation(summary = "Abort in-progress chunked upload — active reviewer or approver")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/flow/upload/abort")
    public ResponseEntity<Void> abortUpload(
            @PathVariable String id,
            @RequestParam String uploadId) throws Exception {
        unifiedWorkflowService.abortUpload(id, uploadId, getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Initiate working-copy PDF upload — owner edits the shared _signed working copy (until finalized)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/working-copy/upload/initiate")
    public ResponseEntity<SignUploadInitResponse> initiateWorkingCopyUpload(
            @PathVariable String id) throws Exception {
        return ResponseEntity.ok(unifiedWorkflowService.initiateWorkingCopyUpload(id, getEmail()));
    }

    @Operation(summary = "Get presigned URL for a single working-copy upload chunk — owner only")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/working-copy/upload/presign")
    public ResponseEntity<Map<String, Object>> getWorkingCopyPresignedPartUrl(
            @PathVariable String id,
            @RequestParam String uploadId,
            @RequestParam int partNumber) throws Exception {
        String url = unifiedWorkflowService.getWorkingCopyPresignedPartUrl(id, uploadId, partNumber, getEmail());
        return ResponseEntity.ok(Map.of("url", url, "partNumber", partNumber));
    }

    @Operation(summary = "Abort in-progress working-copy chunked upload — owner only")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/working-copy/upload/abort")
    public ResponseEntity<Void> abortWorkingCopyUpload(
            @PathVariable String id,
            @RequestParam String uploadId) throws Exception {
        unifiedWorkflowService.abortWorkingCopyUpload(id, uploadId, getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Finalize owner working-copy save — overwrites _signed.pdf and persists field edits (until finalized)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/working-copy/complete")
    public ResponseEntity<ContractResponse> completeWorkingCopy(
            @PathVariable String id,
            @RequestBody FlowCompleteRequest request) throws Exception {
        return ResponseEntity.ok(unifiedWorkflowService.completeWorkingCopy(id, request, getEmail()));
    }

    @Operation(summary = "Get current PDF URL — returns latest signed PDF if any approver has signed, else original")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/flow/file-url")
    public ResponseEntity<Map<String, Object>> getParticipantFileUrl(
            @PathVariable String id) throws Exception {
        return ResponseEntity.ok(unifiedWorkflowService.getParticipantFileUrl(id, getEmail()));
    }

    @Operation(summary = "Send for external signature — enforces org-field gate before delegating to signature flow")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/flow/send-for-signature")
    public ResponseEntity<ContractResponse> sendForSignature(
            @PathVariable String id,
            @Valid @RequestBody SubmitForSignatureRequest request) {
        return ResponseEntity.ok(unifiedWorkflowService.sendForSignature(id, request, getEmail()));
    }

    @Operation(summary = "Get full flow status — current order, all participant statuses, org-gate state")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/flow/status")
    public ResponseEntity<FlowStatusResponse> getFlowStatus(@PathVariable String id) {
        return ResponseEntity.ok(unifiedWorkflowService.getFlowStatus(id, getEmail()));
    }

    @Operation(summary = "Inbox — contracts where the caller is an active reviewer or approver")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/flow/inbox")
    public ResponseEntity<List<ContractListResponse>> getFlowInbox() {
        return ResponseEntity.ok(unifiedWorkflowService.getFlowInbox(getEmail()));
    }

    @Operation(summary = "Sent — contracts where the caller has already completed their review or approval (read-only)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/flow/sent")
    public ResponseEntity<List<ContractListResponse>> getFlowSent() {
        return ResponseEntity.ok(unifiedWorkflowService.getFlowSent(getEmail()));
    }

    private String getEmail() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}

