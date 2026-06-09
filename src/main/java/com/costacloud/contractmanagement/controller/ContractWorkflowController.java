package com.costacloud.contractmanagement.controller;

import com.costacloud.contractmanagement.dto.*;
import com.costacloud.contractmanagement.service.ContractWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/contracts")
@Tag(name = "Contract Workflow", description = "Review and approval workflow for contracts")
public class ContractWorkflowController {

    private final ContractWorkflowService workflowService;

    public ContractWorkflowController(ContractWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Operation(summary = "Submit for review/approval — or resubmit after rejection")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/submit")
    public ResponseEntity<ContractResponse> submit(
            @PathVariable String id,
            @Valid @RequestBody SubmitWorkflowRequest request) {
        return ResponseEntity.ok(workflowService.submit(id, request, getEmail()));
    }

    @Operation(summary = "Reviewer marks contract as reviewed")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/review/complete")
    public ResponseEntity<ContractResponse> reviewComplete(
            @PathVariable String id,
            @RequestBody ReviewCompleteRequest request) {
        return ResponseEntity.ok(workflowService.reviewComplete(id, request, getEmail()));
    }

    @Operation(summary = "Reviewer marks as reviewed and forwards to additional reviewers")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/review/forward")
    public ResponseEntity<ContractResponse> reviewForward(
            @PathVariable String id,
            @Valid @RequestBody ForwardReviewRequest request) {
        return ResponseEntity.ok(workflowService.reviewForward(id, request, getEmail()));
    }

    @Operation(summary = "Reviewer rejects the contract with a required message")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/review/reject")
    public ResponseEntity<ContractResponse> reviewReject(
            @PathVariable String id,
            @Valid @RequestBody RejectRequest request) {
        return ResponseEntity.ok(workflowService.reviewReject(id, request, getEmail()));
    }

    @Operation(summary = "Approver approves the contract")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/approval/approve")
    public ResponseEntity<ContractResponse> approvalApprove(
            @PathVariable String id,
            @RequestBody ApproveRequest request) {
        return ResponseEntity.ok(workflowService.approvalApprove(id, request, getEmail()));
    }

    @Operation(summary = "Approver rejects the contract with a required message")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/approval/reject")
    public ResponseEntity<ContractResponse> approvalReject(
            @PathVariable String id,
            @Valid @RequestBody RejectRequest request) {
        return ResponseEntity.ok(workflowService.approvalReject(id, request, getEmail()));
    }

    private String getEmail() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
